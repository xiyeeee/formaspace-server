package com.xiye.formaspace.testsupport;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Loopback HTTP and RFC6455 fixture, with real JDK client handshake and fragmented text frames. */
public class PythonRealtimeFixture implements AutoCloseable {
    public static final String TOKEN="fixture-service-token";
    public final JsonMapper json=JsonMapper.builder().build();
    private final HttpServer http;
    private final ServerSocket ws;
    private final ExecutorService io=Executors.newFixedThreadPool(4);
    private final List<Socket> sockets=new CopyOnWriteArrayList<>();
    public final AtomicInteger submissions=new AtomicInteger(),handshakes=new AtomicInteger(),previewGets=new AtomicInteger(),resultGets=new AtomicInteger();
    public final AtomicInteger submissionStatus=new AtomicInteger(202),resultStatus=new AtomicInteger(200);
    public final AtomicReference<JsonNode> snapshot=new AtomicReference<>();
    public volatile String taskId,parameters,multipart;
    public volatile boolean httpAuthorized=true,wsAuthorized=true,externalPaths;
    public volatile Consumer<Socket> onConnect=socket->{};
    public PythonRealtimeFixture() throws IOException {
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange->{
            int status=200;Object body=Map.of("ready",true,"modelId","tencent/Hunyuan3D-2mini");
            String path=exchange.getRequestURI().getPath();
            boolean authorized=("Bearer "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            if (!path.equals("/healthz")) httpAuthorized &= authorized;
            if (!authorized && !path.equals("/healthz")) { status=401;body=Map.of(); }
            else if (path.equals("/api/v1/tasks") && exchange.getRequestMethod().equals("POST")) {
                submissions.incrementAndGet();multipart=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.ISO_8859_1);
                String id=part("taskId"),params=part("parameters");
                int configured=submissionStatus.get();
                if (configured!=202) {status=configured;body=Map.of();exchange.getResponseHeaders().set("Retry-After","1");}
                else if (taskId!=null && (!taskId.equals(id) || !parameters.equals(params))) {status=409;body=Map.of();}
                else {
                    taskId=id;parameters=params;status=202;
                    if (snapshot.get()==null) snapshot.set(state("queued",0,0,0));
                    body=Map.of("taskId",taskId,"status","queued","snapshotPath",externalPaths?"https://untrusted.invalid/result":path(),"eventsPath",path()+"/events");
                }
            } else if (taskId!=null && path.equals(path())) body=snapshot.get();
            else if (taskId!=null && path.equals(path()+"/previews/1")) {previewGets.incrementAndGet();body=preview();}
            else if (taskId!=null && path.equals(path()+"/result")) {
                resultGets.incrementAndGet();status=resultStatus.get();
                if (status==200) {
                    byte[] bytes=MediaFixtures.glb();exchange.getResponseHeaders().set("Content-Type","model/gltf-binary");
                    exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
                } else body=Map.of();
            } else if (!path.equals("/readyz") && !path.equals("/healthz")) {status=404;body=Map.of();}
            byte[] bytes=json.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });http.start();
        ws=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        io.submit(()->{while (!ws.isClosed()) {
            try { Socket socket=ws.accept();sockets.add(socket);io.submit(()->handshake(socket)); }
            catch (IOException ignored) { }
        }});
    }
    private String part(String name) {
        int index=multipart.indexOf("name=\""+name+"\"");
        if (index<0) return "";
        int start=multipart.indexOf("\r\n\r\n",index)+4;
        return multipart.substring(start,multipart.indexOf("\r\n",start));
    }
    public URI httpUrl() {return URI.create("http://127.0.0.1:"+http.getAddress().getPort());}
    public URI wsUrl() {return URI.create("ws://127.0.0.1:"+ws.getLocalPort());}
    public String path() {return "/api/v1/tasks/"+taskId;}
    private void handshake(Socket socket) {
        try {
            var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            String request=reader.readLine();Map<String,String> headers=new HashMap<>();String line;
            while ((line=reader.readLine())!=null && !line.isEmpty()) {int split=line.indexOf(':');headers.put(line.substring(0,split).toLowerCase(Locale.ROOT),line.substring(split+1).strip());}
            boolean authorized=("Bearer "+TOKEN).equals(headers.get("authorization"));wsAuthorized &= authorized;
            if (!authorized || !request.startsWith("GET "+path()+"/events ")) {
                socket.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));socket.close();return;
            }
            String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                    (headers.get("sec-websocket-key")+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();handshakes.incrementAndGet();
            send(socket,envelope("task_snapshot",snapshot.get(),snapshot.get()),true);
            onConnect.accept(socket);
            while (socket.getInputStream().read()!=-1) { }
        } catch (Exception ignored) { }
        finally {try {socket.close();}catch(IOException ignored){} sockets.remove(socket);}
    }
    public JsonNode state(String status,long sequence,int completed,int preview) {
        var state=new LinkedHashMap<String,Object>();
        state.put("taskId",taskId);state.put("status",status);state.put("sequence",sequence);
        state.put("stage",status.equals("completed")?"completed":status.equals("failed")?"failed":status.equals("queued")?"queued":completed==30?"final_decoding":"sampling");
        state.put("completedSteps",completed);state.put("totalSteps",30);state.put("previewVersion",preview);
        state.put("latestPreview",preview==0?null:Map.of("version",preview,"sourceStep",24,"pointCount",5000,"positionsPath",path()+"/previews/"+preview));
        state.put("result",status.equals("completed")?Map.of("modelPath",path()+"/result","format","glb"):null);
        state.put("error",status.equals("failed")?Map.of("code","SERVICE_RESTARTED","message","service restarted"):null);
        return json.valueToTree(state);
    }
    public Map<String,Object> preview() {
        return Map.of("taskId",taskId,"previewVersion",1,"sourceStep",24,"pointCount",5000,
                "positions",Collections.nCopies(5000,List.of(0.1,0.2,0.3)));
    }
    public void broadcast(String text) {
        for (var socket:sockets) {try{send(socket,text,true);}catch(UncheckedIOException ignored){}}
    }
    public String envelope(String type,JsonNode state,Object payload) {
        return json.writeValueAsString(Map.of("protocolVersion",1,"taskId",taskId,"sequence",state.path("sequence").asLong(),
                "type",type,"stage",state.path("stage").asString(),"completedSteps",state.path("completedSteps").asInt(),
                "totalSteps",30,"previewVersion",state.path("previewVersion").asInt(),"timestamp",Instant.now().toString(),"payload",payload));
    }
    public static void send(Socket socket,String text,boolean fragmented) {
        try {
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
            synchronized(socket) {
                if (fragmented) {int middle=bytes.length/2;frame(socket,Arrays.copyOfRange(bytes,0,middle),1);frame(socket,Arrays.copyOfRange(bytes,middle,bytes.length),128);}
                else frame(socket,bytes,129);
            }
        } catch (IOException exception) {throw new UncheckedIOException(exception);}
    }
    private static void frame(Socket socket,byte[] bytes,int opcode) throws IOException {
        var output=new DataOutputStream(socket.getOutputStream());output.writeByte(opcode);
        if(bytes.length<126) output.writeByte(bytes.length);
        else if(bytes.length<=65535) {output.writeByte(126);output.writeShort(bytes.length);}
        else {output.writeByte(127);output.writeLong(bytes.length);}
        output.write(bytes);output.flush();
    }
    @Override public void close() {
        http.stop(0);try{ws.close();}catch(IOException ignored){}
        sockets.forEach(socket->{try{socket.close();}catch(IOException ignored){}});io.shutdownNow();
    }
}
