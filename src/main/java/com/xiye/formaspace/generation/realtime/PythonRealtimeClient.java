package com.xiye.formaspace.generation.realtime;

import com.xiye.formaspace.generation.config.*;
import com.xiye.formaspace.generation.inference.ModelInferenceClient;
import com.xiye.formaspace.common.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix="formaspace.generation", name="mode", havingValue="PYTHON_REALTIME")
public class PythonRealtimeClient implements ModelInferenceClient {
    private final PythonRealtimeProperties config;
    private final ObjectMapper json;
    private final PythonRealtimeProtocol protocol;
    private final RestClient submitHttp, snapshotHttp, downloadHttp;
    private final ThreadPoolExecutor socketExecutor;
    private final HttpClient socketClient;
    private final URI wsBase;
    private final String serviceKey;
    private volatile Instant checkedAt = Instant.EPOCH;
    private volatile boolean available;

    public PythonRealtimeClient(GenerationProperties generation, ObjectMapper json, PythonRealtimeProtocol protocol) {
        this.config = generation.getPythonRealtime(); this.json=json; this.protocol=protocol;
        if (config.getToken()==null || config.getToken().isBlank() || config.getToken().contains("\r") || config.getToken().contains("\n"))
            throw new IllegalStateException("PYTHON_REALTIME 必须设置 INFERENCE_API_TOKEN");
        validateBase(config.getBaseUrl(), Set.of("http", "https"));
        wsBase = config.getWsBaseUrl()==null ? URI.create(config.getBaseUrl().toString().replaceFirst("^http", "ws")) : config.getWsBaseUrl();
        validateBase(wsBase, Set.of("ws", "wss"));
        for (var duration : List.of(config.getConnectTimeout(),config.getSubmitTimeout(),config.getSnapshotTimeout(),
                config.getDownloadTimeout(),config.getTaskDeadline(),config.getReconnectInitialDelay(),
                config.getReconnectMaxDelay(),config.getReconciliationInterval())) {
            if (duration.toMillis()<1) throw new IllegalStateException("实时服务超时和重试间隔必须为正数");
        }
        if (config.getReconnectInitialDelay().compareTo(config.getReconnectMaxDelay())>0
                || (config.isPreviewEnabled() && !config.isMultiStagePreview() && config.getPreviewStep()>=config.getSteps())
                || !Set.of(128,256).contains(config.getFinalResolution()) || !Set.of(64,128).contains(config.getPreviewResolution()))
            throw new IllegalStateException("实时服务预览步数或退避配置不合法");
        serviceKey = serviceKey(config);
        submitHttp=http(config.getSubmitTimeout()); snapshotHttp=http(config.getSnapshotTimeout()); downloadHttp=http(config.getDownloadTimeout());
        socketExecutor=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),
                runnable -> { var thread=new Thread(runnable,"python-ws-io");thread.setDaemon(true);return thread; },new ThreadPoolExecutor.AbortPolicy());
        socketClient=HttpClient.newBuilder().executor(socketExecutor).connectTimeout(config.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private void validateBase(URI uri, Set<String> schemes) {
        if (uri==null || !schemes.contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                || uri.getQuery()!=null || uri.getFragment()!=null || uri.getPath().contains(".."))
            throw new IllegalStateException("推理服务地址必须是受控 HTTP(S)/WS(S) 地址，不含凭证或查询参数");
    }
    private RestClient http(Duration timeout) {
        var factory=new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection,String method) throws IOException {
                super.prepareConnection(connection,method);connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(config.getConnectTimeout()); factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).defaultHeader(HttpHeaders.AUTHORIZATION,"Bearer "+config.getToken()).build();
    }
    private URI endpoint(URI base,String path) { return URI.create(base.toString().replaceAll("/+$","")+path); }
    public String serviceKey() { return serviceKey; }
    public static String serviceKey(PythonRealtimeProperties config) {
        URI ws = config.getWsBaseUrl()==null ? URI.create(config.getBaseUrl().toString().replaceFirst("^http","ws")) : config.getWsBaseUrl();
        String identity=config.getBaseUrl().normalize().toString().replaceAll("/+$","")+"|"+ws.normalize().toString().replaceAll("/+$","");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public synchronized boolean ready() {
        if (checkedAt.plusSeconds(3).isAfter(Instant.now())) return available;
        checkedAt=Instant.now();
        try {
            available=snapshotHttp.get().uri(endpoint(config.getBaseUrl(),"/readyz")).exchange((request,response)-> {
                if (response.getStatusCode().value()!=200) return false;
                var body=parse(response.getBody(),config.getSnapshotTimeout());
                return (!body.has("ready") || body.path("ready").asBoolean())
                        && (!body.has("modelId") || config.getModelId().equals(body.path("modelId").asString()));
            });
        } catch (RuntimeException exception) { available=false; }
        return available;
    }
    public JsonNode snapshot(String id) throws IOException { return get(PythonRealtimeProtocol.taskPath(id)); }
    public JsonNode preview(String id,int version) throws IOException {
        if (version<1) throw new IOException("Invalid preview version");
        return get(PythonRealtimeProtocol.taskPath(id)+"/previews/"+version);
    }
    private JsonNode get(String path) throws IOException {
        try {
            return snapshotHttp.get().uri(endpoint(config.getBaseUrl(),path)).exchange((request,response)-> {
                checkStatus(response.getStatusCode().value(),response.getHeaders());
                return parse(response.getBody(),config.getSnapshotTimeout());
            });
        } catch (RestClientException exception) { throw unwrap(exception); }
    }
    public void submitTask(String id, String parameters, SourceImage image) throws IOException {
        try (var input=image.content().getInputStream()) {
            var resource=new InputStreamResource(input) {
                @Override public String getFilename() { return "source"+(image.contentType().equals("image/png")?".png":".jpg"); }
                @Override public long contentLength() { return -1; }
            };
            var headers=new HttpHeaders(); headers.setContentType(MediaType.parseMediaType(image.contentType()));
            var parts=new LinkedMultiValueMap<String,Object>();
            parts.add("taskId",id);parts.add("image",new HttpEntity<>(resource,headers));parts.add("parameters",parameters);
            try {
                submitHttp.post().uri(endpoint(config.getBaseUrl(),"/api/v1/tasks")).contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(parts).exchange((request,response)-> {
                            checkStatus(response.getStatusCode().value(),response.getHeaders());
                            PythonRealtimeProtocol.require(response.getStatusCode().value()==202 || response.getStatusCode().value()==200);
                            var node=parse(response.getBody(),config.getSubmitTimeout());
                            String path=PythonRealtimeProtocol.taskPath(id);
                            PythonRealtimeProtocol.require(id.equals(node.path("taskId").asString())
                                    && path.equals(node.path("snapshotPath").asString()) && (path+"/events").equals(node.path("eventsPath").asString())
                                    && Set.of("queued","running","completed","failed").contains(node.path("status").asString()));
                            return null;
                        });
            } catch (RestClientException exception) { throw unwrap(exception); }
        }
    }
    public WebSocket connect(String id, WebSocket.Listener listener) throws IOException {
        var future=socketClient.newWebSocketBuilder().connectTimeout(config.getConnectTimeout())
                .header(HttpHeaders.AUTHORIZATION,"Bearer "+config.getToken())
                .buildAsync(endpoint(wsBase,PythonRealtimeProtocol.taskPath(id)+"/events"),listener);
        try { return future.get(config.getConnectTimeout().toMillis(),TimeUnit.MILLISECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt();future.cancel(true);throw new IOException("WebSocket interrupted"); }
        catch (ExecutionException | TimeoutException exception) { future.cancel(true);throw new IOException("WebSocket unavailable"); }
    }
    @Override public InputStream openResult(String id) throws IOException {
        Path temporary=Files.createTempFile("formaspace-python-result-",".glb");
        try {
            try {
                downloadHttp.get().uri(endpoint(config.getBaseUrl(),PythonRealtimeProtocol.taskPath(id)+"/result")).exchange((request,response)-> {
                    checkStatus(response.getStatusCode().value(),response.getHeaders());
                    long size=0,deadline=System.nanoTime()+config.getDownloadTimeout().toNanos();
                    byte[] buffer=new byte[16384];
                    try (var input=response.getBody();var output=Files.newOutputStream(temporary)) {
                        int count;
                        while ((count=input.read(buffer))!=-1) {
                            size+=count;
                            if (size>config.getMaximumDownloadBytes() || System.nanoTime()>deadline) throw new IOException("Model download exceeds limits");
                            output.write(buffer,0,count);
                        }
                    }
                    return null;
                });
            } catch (RestClientException exception) { throw unwrap(exception); }
            return new FilterInputStream(Files.newInputStream(temporary)) {
                @Override public void close() throws IOException { try { super.close(); } finally { Files.deleteIfExists(temporary); } }
            };
        } catch (IOException | RuntimeException exception) { Files.deleteIfExists(temporary);throw exception; }
    }
    private JsonNode parse(InputStream input, Duration timeout) throws IOException {
        var output=new ByteArrayOutputStream(); byte[] buffer=new byte[8192];long deadline=System.nanoTime()+timeout.toNanos();
        int count;
        while ((count=input.read(buffer))!=-1) {
            if (output.size()+count>config.getMaximumMessageBytes() || System.nanoTime()>deadline) throw new IOException("Python response exceeds limits");
            output.write(buffer,0,count);
        }
        try { return json.readTree(output.toByteArray()); }
        catch (RuntimeException exception) { throw new IOException("Invalid Python response"); }
    }
    private void checkStatus(int status,HttpHeaders headers) throws IOException {
        if (status>=200 && status<300) return;
        long delay=0;
        try { delay=Math.min(300,Math.max(0,Long.parseLong(headers.getFirst(HttpHeaders.RETRY_AFTER)))); }
        catch (RuntimeException ignored) { }
        throw new RemoteHttpException(status,delay);
    }
    private IOException unwrap(RestClientException exception) {
        for (Throwable cause=exception;cause!=null;cause=cause.getCause()) if (cause instanceof IOException io) return io;
        return new IOException("Python transport interrupted");
    }
    // Realtime submission requires the identity persisted by GenerationTaskService, not an ad-hoc UUID.
    @Override public SubmissionResult submit(Submission submission) throws IOException { throw new IOException("Persisted Python task identity required"); }
    @Override public QueryResult query(String reference) throws IOException {
        throw new IOException("Realtime tasks must be reconciled using their persisted snapshot");
    }
    @PreDestroy public void close() { socketClient.close();socketExecutor.shutdownNow(); }
    public static class RemoteHttpException extends IOException {
        private final int status;private final long retryAfter;
        public RemoteHttpException(int status,long retryAfter) { super("Python HTTP request failed");this.status=status;this.retryAfter=retryAfter; }
        public int status() { return status; } public long retryAfter() { return retryAfter; }
    }
}
