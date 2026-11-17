package com.xiye.formaspace.generation.inference;

import com.xiye.formaspace.common.exception.ErrorCode;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.generation.config.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
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
@ConditionalOnProperty(prefix="formaspace.generation", name="mode", havingValue="HUNYUAN_LOCAL")
public class HunyuanLocalInferenceClient implements ModelInferenceClient {
    private static final List<String> PARAMETERS = List.of("caption", "image", "mv_image_front", "mv_image_back",
            "mv_image_left", "mv_image_right", "steps", "guidance_scale", "seed", "octree_resolution",
            "check_box_rembg", "num_chunks", "randomize_seed");
    private final HunyuanLocalProperties properties;
    private final FileStorageProperties limits;
    private final ObjectMapper json;
    private final Clock clock;
    private final RestClient http;
    private final RestClient metadata;
    private final String origin;
    private volatile Readiness cached;
    private volatile Instant checkedAt = Instant.EPOCH;

    public HunyuanLocalInferenceClient(GenerationProperties generation, FileStorageProperties limits,
            ObjectMapper json, Clock clock) {
        properties = generation.getHunyuanLocal();
        this.limits = limits;
        this.json = json;
        this.clock = clock;
        URI base = properties.getBaseUrl();
        if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalStateException("Hunyuan base-url 必须为无凭证、查询参数或片段的 HTTP(S) 服务地址");
        }
        for (Duration duration : List.of(properties.getConnectTimeout(), properties.getReadTimeout(),
                properties.getTaskTimeout(), properties.getRetryInterval())) {
            if (duration.toMillis() < 1) { throw new IllegalStateException("Hunyuan 超时和重试间隔必须为正数"); }
        }
        var preset=properties.getStandardPreset();
        if (preset.inferenceSteps()>100 || preset.octreeResolution()<16 || preset.octreeResolution()>512
                || !Double.isFinite(preset.guidanceScale())) {
            throw new IllegalStateException("Hunyuan profile 超出当前已核对的 Gradio 组件协议限制");
        }
        origin = base.toString().replaceAll("/+$", "");
        http = client(properties.getReadTimeout());
        metadata = client(Duration.ofSeconds(2));
    }

    private RestClient client(Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(readTimeout);
        var builder = RestClient.builder().requestFactory(factory);
        if (properties.getAuthorization() != null && !properties.getAuthorization().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, properties.getAuthorization());
        }
        return builder.build();
    }

    public synchronized Readiness readiness() {
        if (cached != null && checkedAt.plusSeconds(5).isAfter(clock.instant())) { return cached; }
        checkedAt = clock.instant();
        try {
            var config = readJson(metadata, endpoint("/config"));
            var info = readJson(metadata, endpoint(properties.getApiPrefix() + "/info"));
            var api = info.path("named_endpoints").path("/" + properties.getApiName());
            var names = new ArrayList<String>();
            for (var parameter : api.path("parameters")) { names.add(parameter.path("parameter_name").asString()); }
            boolean rgba = false;
            boolean modelMarker = false;
            for (var component : config.path("components")) {
                var props = component.path("props");
                modelMarker |= props.path("value").asString("").contains(properties.getModelId() + "/" + properties.getSubfolder());
            }
            for (var dependency : config.path("dependencies")) {
                if (!properties.getApiName().equals(dependency.path("api_name").asString(""))) { continue; }
                int inputId = dependency.path("inputs").path(1).asInt(-1);
                for (var component : config.path("components")) {
                    if (component.path("id").asInt(-2) == inputId) {
                        rgba = "RGBA".equals(component.path("props").path("image_mode").asString(""));
                    }
                }
            }
            boolean compatible = "6.32.0".equals(config.path("version").asString(""))
                    && properties.getApiPrefix().equals(config.path("api_prefix").asString(""))
                    && PARAMETERS.equals(names) && "File".equals(api.path("returns").path(0).path("component").asString(""))
                    && rgba && modelMarker && !config.path("app_id").isMissingNode();
            cached = new Readiness(compatible, config.path("app_id").asString(""));
        } catch (IOException | RuntimeException exception) {
            cached = new Readiness(false, null);
        }
        return cached;
    }

    @Override
    public SubmissionResult submit(Submission submission) throws IOException {
        var ready = readiness();
        if (!ready.ready()) { throw new InferenceClientException(ErrorCode.INFERENCE_UNAVAILABLE); }
        var parameters = submission.parameters();
        if (parameters.generateTexture() || !"GLB".equals(parameters.format()) || parameters.seed() > 10_000_000L) {
            throw new InferenceClientException(ErrorCode.UNSUPPORTED_CAPABILITY);
        }
        String uploaded = upload(submission.sourceImage());
        var file = Map.of("path", uploaded, "meta", Map.of("_type", "gradio.FileData"));
        // Exact local 6.32.0 metadata order. Never enable Gradio's randomize_seed.
        var values = Arrays.asList(null, file, null, null, null, null, parameters.inferenceSteps(),
                parameters.guidanceScale(), parameters.seed(), parameters.octreeResolution(),
                parameters.removeBackground(), parameters.numChunks(), false);
        JsonNode response;
        try {
            response = http.post().uri(endpoint(properties.getApiPrefix() + "/call/" + properties.getApiName()))
                    .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(Map.of("data", values)))
                    .exchange((request, result) -> {
                        if (result.getStatusCode().is4xxClientError()) { throw new InferenceClientException(ErrorCode.INFERENCE_FAILED); }
                        if (!result.getStatusCode().is2xxSuccessful()) { throw new InferenceClientException(ErrorCode.SUBMISSION_UNCERTAIN); }
                        return parse(result.getBody(), 65536);
                    });
        } catch (RestClientException exception) {
            IOException failure=unwrap(exception, ErrorCode.SUBMISSION_UNCERTAIN);
            if (failure instanceof InferenceClientException known && known.getErrorCode()==ErrorCode.INFERENCE_PROTOCOL_ERROR) {
                throw new InferenceClientException(ErrorCode.SUBMISSION_UNCERTAIN);
            }
            throw failure;
        }
        String event = response.path("event_id").asString("");
        if (!event.matches("[a-zA-Z0-9_-]{1,128}")) { throw new InferenceClientException(ErrorCode.SUBMISSION_UNCERTAIN); }
        Instant deadline = submission.deadlineAt() == null ? clock.instant().plus(properties.getTaskTimeout()) : submission.deadlineAt();
        return new SubmissionResult(json.writeValueAsString(new RemoteReference(origin, properties.getApiName(), event,
                ready.appId(), deadline.toEpochMilli())));
    }

    private String upload(SourceImage source) throws IOException {
        try (var input = source.content().getInputStream()) {
            var resource = new InputStreamResource(input) {
                @Override public String getFilename() { return "source-" + source.fileId() + (source.contentType().equals("image/png") ? ".png" : ".jpg"); }
                @Override public long contentLength() { return -1; }
            };
            var headers = new HttpHeaders(); headers.setContentType(MediaType.parseMediaType(source.contentType()));
            var body = new LinkedMultiValueMap<String,Object>(); body.add("files", new HttpEntity<>(resource, headers));
            JsonNode result = http.post().uri(endpoint(properties.getApiPrefix() + "/upload"))
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(body).exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) { throw new InferenceClientException(ErrorCode.INFERENCE_UNAVAILABLE); }
                        return parse(response.getBody(), 65536);
                    });
            String path = result.path(0).asString("");
            validateFilePath(path, false);
            return path;
        } catch (RestClientException exception) { throw unwrap(exception, ErrorCode.INFERENCE_UNAVAILABLE); }
    }

    @Override
    public QueryResult query(String remoteReference) throws IOException {
        RemoteReference reference = reference(remoteReference);
        var ready = readiness();
        if (!ready.ready()) { throw new IOException("Model service temporarily unavailable"); }
        if (!reference.appId().equals(ready.appId())) { throw new InferenceClientException(ErrorCode.INFERENCE_REFERENCE_LOST); }
        if (clock.instant().toEpochMilli() >= reference.deadlineEpochMillis()) { throw new InferenceClientException(ErrorCode.INFERENCE_TIMEOUT); }
        try {
            return http.get().uri(endpoint(properties.getApiPrefix() + "/call/" + reference.apiName() + "/" + reference.eventId()))
                    .accept(MediaType.TEXT_EVENT_STREAM).exchange((request, response) -> {
                        if (response.getStatusCode().value() == 404) { throw new InferenceClientException(ErrorCode.INFERENCE_REFERENCE_LOST); }
                        if (!response.getStatusCode().is2xxSuccessful()) { throw new IOException("Event stream unavailable"); }
                        return readEvents(response.getBody(), reference);
                    });
        } catch (RestClientException exception) { throw unwrap(exception, null); }
    }

    private QueryResult readEvents(InputStream stream, RemoteReference reference) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(new LimitedInputStream(stream, 4*1024*1024), StandardCharsets.UTF_8))) {
            String event = "";
            var data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (clock.instant().toEpochMilli() >= reference.deadlineEpochMillis()) { throw new InferenceClientException(ErrorCode.INFERENCE_TIMEOUT); }
                if (line.isEmpty()) {
                    if (event.equals("error")) { return new QueryResult(true, null, ErrorCode.INFERENCE_FAILED); }
                    if (event.equals("complete")) {
                        JsonNode outputs;
                        try { outputs = json.readTree(data.toString()); }
                        catch (RuntimeException exception) { throw new InferenceClientException(ErrorCode.INFERENCE_PROTOCOL_ERROR); }
                        var file = outputs.path(0);
                        if ("update".equals(file.path("__type__").asString(""))) { file = file.path("value"); }
                        String path = file.path("path").asString("");
                        validateFilePath(path, true);
                        return new QueryResult(true, json.writeValueAsString(new ResultReference(origin, reference.appId(), path, reference.deadlineEpochMillis())), null);
                    }
                    event = ""; data.setLength(0);
                } else if (line.startsWith("event:")) { event = line.substring(6).strip(); }
                else if (line.startsWith("data:")) { if (!data.isEmpty()) { data.append('\n'); } data.append(line.substring(5).stripLeading()); }
            }
        }
        throw new EOFException("Model event stream disconnected before completion");
    }

    @Override
    public InputStream openResult(String resultReference) throws IOException {
        ResultReference result;
        try { result = json.readValue(resultReference, ResultReference.class); }
        catch (RuntimeException exception) { throw new InferenceClientException(ErrorCode.INFERENCE_PROTOCOL_ERROR); }
        if (!origin.equals(result.baseUrl())) { throw new InferenceClientException(ErrorCode.INFERENCE_REFERENCE_LOST); }
        validateFilePath(result.path(), true);
        // Stream to a bounded temporary file inside exchange; the HTTP response closes before publication.
        var temporary = java.nio.file.Files.createTempFile("formaspace-hunyuan-", ".glb");
        try {
            http.get().uri(endpoint(properties.getApiPrefix() + "/file=" + result.path()))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() == 404) { throw new InferenceClientException(ErrorCode.INFERENCE_REFERENCE_LOST); }
                        if (!response.getStatusCode().is2xxSuccessful()) { throw new IOException("Model download unavailable"); }
                        if (response.getHeaders().getContentLength() > limits.getMaxModelBytes()) {
                            throw new InferenceClientException(ErrorCode.INFERENCE_PROTOCOL_ERROR);
                        }
                        try (var input = new LimitedInputStream(response.getBody(), limits.getMaxModelBytes(), result.deadlineEpochMillis());
                                var output = java.nio.file.Files.newOutputStream(temporary)) { input.transferTo(output); }
                        return null;
                    });
            return new FilterInputStream(java.nio.file.Files.newInputStream(temporary)) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { java.nio.file.Files.deleteIfExists(temporary); }
                }
            };
        } catch (IOException | RuntimeException exception) {
            java.nio.file.Files.deleteIfExists(temporary);
            if (exception instanceof InferenceClientException failure) { throw failure; }
            if (exception instanceof RestClientException failure) { throw unwrap(failure, null); }
            throw new IOException("Model result download failed");
        }
    }

    private RemoteReference reference(String value) throws IOException {
        try {
            if (value == null || value.length() > 4096) { throw new IllegalArgumentException(); }
            var reference = json.readValue(value, RemoteReference.class);
            if (!origin.equals(reference.baseUrl()) || !properties.getApiName().equals(reference.apiName())
                    || reference.appId()==null || !reference.eventId().matches("[a-zA-Z0-9_-]{1,128}")) { throw new IllegalArgumentException(); }
            return reference;
        } catch (RuntimeException exception) { throw new InferenceClientException(ErrorCode.INFERENCE_REFERENCE_LOST); }
    }

    private void validateFilePath(String path, boolean glb) throws InferenceClientException {
        if (path == null || path.length() > 2048 || !path.startsWith("/") || path.startsWith("//")
                || path.contains("\\") || path.contains("\r") || path.contains("\n") || path.indexOf(0) >= 0
                || Arrays.asList(path.split("/")).contains("..") || (glb && !path.toLowerCase(Locale.ROOT).endsWith(".glb"))) {
            throw new InferenceClientException(ErrorCode.INFERENCE_PROTOCOL_ERROR);
        }
    }

    private URI endpoint(String suffix) {
        try {
            URI base = properties.getBaseUrl();
            return new URI(base.getScheme(), base.getAuthority(), base.getPath().replaceAll("/+$", "") + suffix, null, null);
        } catch (URISyntaxException exception) { throw new IllegalArgumentException("Invalid model endpoint"); }
    }

    private JsonNode readJson(RestClient client, URI uri) throws IOException {
        try {
            return client.get().uri(uri).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) { throw new IOException("Model metadata unavailable"); }
                return parse(response.getBody(), 2*1024*1024);
            });
        } catch (RestClientException exception) { throw new IOException("Model metadata unavailable"); }
    }

    private IOException unwrap(RestClientException exception, ErrorCode fallback) {
        Throwable cause=exception;
        while (cause!=null) {
            if (cause instanceof InferenceClientException failure) { return failure; }
            cause=cause.getCause();
        }
        return fallback==null ? new IOException("Model service connection interrupted") : new InferenceClientException(fallback);
    }

    private JsonNode parse(InputStream input, long maximum) throws IOException {
        try { return json.readTree(new LimitedInputStream(input, maximum).readAllBytes()); }
        catch (RuntimeException exception) { throw new InferenceClientException(ErrorCode.INFERENCE_PROTOCOL_ERROR); }
    }

    record RemoteReference(String baseUrl, String apiName, String eventId, String appId, long deadlineEpochMillis) {}
    record ResultReference(String baseUrl, String appId, String path, long deadlineEpochMillis) {}
    public record Readiness(boolean ready, String appId) {}

    private class LimitedInputStream extends FilterInputStream {
        private final long maximum;
        private final long deadline;
        private long count;
        LimitedInputStream(InputStream source, long maximum) { this(source,maximum,Long.MAX_VALUE); }
        LimitedInputStream(InputStream source, long maximum,long deadline) {
            super(source); this.maximum=maximum; this.deadline=deadline;
        }
        @Override public int read() throws IOException {
            int value=super.read(); if (value >= 0) { check(1); } return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int size=in.read(bytes,offset,length); if (size > 0) { check(size); } return size;
        }
        private void check(int size) throws IOException {
            if (clock.instant().toEpochMilli()>=deadline) { throw new InferenceClientException(ErrorCode.INFERENCE_TIMEOUT); }
            count+=size; if (count>maximum) { throw new InferenceClientException(ErrorCode.INFERENCE_PROTOCOL_ERROR); }
        }
    }
}
