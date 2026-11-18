package com.xiye.formaspace.generation;

import com.xiye.formaspace.auth.config.JwtProperties;
import com.xiye.formaspace.auth.service.TokenService;
import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.generation.dto.CreateGenerationDTO;
import com.xiye.formaspace.generation.enums.*;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.realtime.*;
import com.xiye.formaspace.generation.service.GenerationTaskService;
import com.xiye.formaspace.testsupport.MediaFixtures;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import com.xiye.formaspace.user.vo.UserVO;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"formaspace.generation.mode=PYTHON_REALTIME","formaspace.email-code.enabled=false","formaspace.cors.allowed-origins=http://localhost:5173"})
@ActiveProfiles("test")
@DirtiesContext
@EnabledIfEnvironmentVariable(named="BROWSER_LIVE_IMAGE",matches=".+")
class BrowserWebSocketLiveTests {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry)throws Exception{
        String url=System.getenv("BROWSER_LIVE_TEST_DB_URL");if(!URI.create(url.substring(5)).getPath().matches("/[A-Za-z0-9_]+_test"))throw new IllegalStateException("仅允许测试库");
        registry.add("formaspace.generation.python-realtime.token",()->System.getenv("INFERENCE_API_TOKEN"));
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->System.getenv("BROWSER_LIVE_TEST_DB_USERNAME"));registry.add("spring.datasource.password",()->System.getenv("BROWSER_LIVE_TEST_DB_PASSWORD"));
        registry.add("formaspace.jwt.secret",()->Base64.getEncoder().encodeToString(new byte[64]));registry.add("formaspace.email-code.hmac-key",()->Base64.getEncoder().encodeToString("browser-ws-independent-hmac-key-32".getBytes()));
        Path root=Files.createTempDirectory("formaspace-browser-ws-").toRealPath();registry.add("formaspace.storage.root",()->root.toString());
    }
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @BeforeEach void clearDisposableDatabase(){
        for(String table:List.of("fs_generation_browser_dedup","fs_generation_browser_state","fs_generation_preview","fs_model_asset","fs_generation_task","fs_file","fs_user","fs_email_verification","fs_auth_rate_limit"))jdbc.update("DELETE FROM "+table);
    }
    @LocalServerPort private int port;
    @Autowired private UserMapper users;
    @Autowired private TokenService tokens;
    @Autowired private JwtProperties jwt;
    @Autowired private GenerationTaskService tasks;
    @Autowired private GenerationTaskMapper mapper;
    @Autowired private FileService files;
    @Autowired private GenerationPreviewService previews;
    @Autowired private GenerationEventPublisher events;
    @Autowired private ObjectMapper json;
    private final HttpClient http=HttpClient.newHttpClient();
    private UserEntity user(){UserEntity user=new UserEntity();String name="ws_"+UUID.randomUUID().toString().substring(0,8);user.setUsername(name);user.setEmail(name+"@example.com");user.setNickname(name);user.setStatus(UserStatus.ACTIVE);user.setPasswordHash("{test}unused");users.insert(user);return user;}
    private String token(UserEntity user){return tokens.issue(UserVO.from(user)).accessToken();}
    private String ticket(String token)throws Exception{
        HttpResponse<String> response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ws/tickets")).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");return json.readTree(response.body()).path("data").path("ticket").asString();
    }
    private WebSocket connect(String ticket,String origin,Inbox inbox)throws Exception{return http.newWebSocketBuilder().header("Origin",origin).buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/generation?ticket="+ticket),inbox).get(5,TimeUnit.SECONDS);}
    private void subscribe(WebSocket socket,long id){socket.sendText("{\"protocolVersion\":1,\"type\":\"subscribe\",\"taskId\":\""+id+"\"}",true).join();}
    @Test @Timeout(value=5,unit=TimeUnit.MINUTES)
    void actualPythonEventsReachNativeBrowserSocketAndRecoverAfterReconnect()throws Exception{
        UserEntity owner=user();String token=token(owner);
        String file=files.upload(owner.getId(),new MockMultipartFile("file","source.png","image/png",Files.readAllBytes(Path.of(System.getenv("BROWSER_LIVE_IMAGE"))))).id();
        CreateGenerationDTO request=new CreateGenerationDTO();request.setSourceFileId(file);
        com.xiye.formaspace.generation.dto.AdvancedGenerationOptionsDTO advanced=new com.xiye.formaspace.generation.dto.AdvancedGenerationOptionsDTO();advanced.setSeed(1234L);request.setAdvancedOptions(advanced);
        long id=Long.parseLong(tasks.create(owner.getId(),UUID.randomUUID().toString(),request).task().id());
        Inbox inbox=new Inbox();WebSocket socket=connect(ticket(token),"http://localhost:5173",inbox);
        Inbox batchInbox=new Inbox();WebSocket batchSocket=null;
        Set<String> types=new HashSet<>(),stages=new HashSet<>();JsonNode cloud=null,completed=null;boolean reconnected=false;long lastSequence=0,lastPing=0;
        long end=System.nanoTime()+Duration.ofMinutes(4).toNanos();
        try{
            subscribe(socket,id);JsonNode first=inbox.next();assertThat(first.path("type").asString()).isEqualTo("task_snapshot");lastSequence=first.path("sequence").asLong();
            batchSocket=connect(ticket(token),"http://localhost:5173",batchInbox);
            batchSocket.sendText("{\"protocolVersion\":1,\"type\":\"subscribe\",\"taskId\":\""+id+"\",\"pointBatches\":true}",true).join();
            assertThat(batchInbox.next().path("type").asString()).isEqualTo("task_snapshot");
            while(System.nanoTime()<end){
                if(System.nanoTime()-lastPing>Duration.ofSeconds(10).toNanos()){
                    socket.sendText("{\"protocolVersion\":1,\"type\":\"ping\",\"requestId\":\"live\"}",true).join();lastPing=System.nanoTime();
                    batchSocket.sendText("{\"protocolVersion\":1,\"type\":\"ping\",\"requestId\":\"batch-live\"}",true).join();
                }
                JsonNode frame=inbox.messages.poll(20,TimeUnit.SECONDS);if(frame==null)continue;
                String type=frame.path("type").asString();types.add(type);if(frame.has("stage"))stages.add(frame.path("stage").asString());
                if(frame.has("sequence")){assertThat(frame.path("sequence").asLong()).isGreaterThan(lastSequence);lastSequence=frame.path("sequence").asLong();}
                assertThat(type).isNotEqualTo("task_failed");assertThat(type).isNotEqualTo("error");
                if(type.equals("result_saving"))assertThat(mapper.selectById(id).getStatus()).isEqualTo(GenerationStatus.PROCESSING);
                if(type.equals("preview_ready") && !reconnected){
                    cloud=frame.path("payload");assertThat(cloud.path("positions").size()).isEqualTo(5000);assertThat(cloud.path("sourceStep").asInt()).isEqualTo(24);
                    JsonNode announcement;do{announcement=batchInbox.next();}while(!announcement.path("type").asString().equals("preview_ready"));
                    assertThat(announcement.path("payload").path("streamed").asBoolean()).isTrue();
                    var accumulated=json.createArrayNode();
                    for(int offset=0;offset<5000;offset+=50){
                        JsonNode packet;do{packet=batchInbox.next();}while(packet.path("type").asString().equals("pong"));
                        assertThat(packet.path("type").asString()).isEqualTo("point_batch");
                        assertThat(packet.path("payload").path("offset").asInt()).isEqualTo(offset);
                        assertThat(packet.path("payload").path("positions").size()).isEqualTo(50);
                        assertThat(packet.path("baseSequence").asLong()).isEqualTo(announcement.path("sequence").asLong());
                        packet.path("payload").path("positions").forEach(accumulated::add);
                    }
                    assertThat(accumulated).isEqualTo(cloud.path("positions"));
                    socket.abort();inbox=new Inbox();socket=connect(ticket(token),"http://localhost:5173",inbox);subscribe(socket,id);
                    JsonNode snapshot=inbox.next();assertThat(snapshot.path("type").asString()).isEqualTo("task_snapshot");assertThat(snapshot.path("payload").path("latestPreview").path("version").asInt()).isEqualTo(1);
                    lastSequence=snapshot.path("sequence").asLong();stages.add(snapshot.path("stage").asString());reconnected=true;
                    String path=cloud.path("positionsPath").asString();HttpResponse<String> restored=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofString());
                    assertThat(restored.statusCode()).isEqualTo(200);assertThat(json.readTree(restored.body()).path("data").path("positions")).isEqualTo(cloud.path("positions"));
                }
                if(type.equals("task_completed")){completed=frame;break;}
            }
            assertThat(completed).isNotNull();assertThat(reconnected).isTrue();assertThat(types).contains("sampling_step","preview_ready","result_saving","task_completed");
            assertThat(completed.path("payload").path("status").asString()).isEqualTo("SUCCEEDED");String assetId=completed.path("payload").path("assetId").asString();
            assertThat(tasks.detail(id,owner.getId()).assetId()).isEqualTo(assetId);
            HttpResponse<byte[]> model=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/assets/"+assetId+"/model")).header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(model.statusCode()).isEqualTo(200);assertThat(model.body().length).isGreaterThan(1000);
            assertThat(java.nio.ByteBuffer.wrap(model.body()).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()).isEqualTo(0x46546c67);
            subscribe(socket,id);assertThat(inbox.next().path("payload").path("status").asString()).isEqualTo("SUCCEEDED");
            System.out.println("REAL_BROWSER_WS_SUCCESS task="+id+" asset="+assetId+" modelBytes="+model.body().length+" points=5000 batches=100 batchSize=50 reconnect=true stages="+stages+" types="+types);
        }finally{socket.abort();if(batchSocket!=null)batchSocket.abort();}
    }
    private final class Inbox implements WebSocket.Listener{
        final BlockingQueue<JsonNode> messages=new LinkedBlockingQueue<>();final CompletableFuture<Integer> closed=new CompletableFuture<>();final StringBuilder parts=new StringBuilder();
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){parts.append(text);if(last){messages.offer(json.readTree(parts.toString()));parts.setLength(0);}socket.request(1);return CompletableFuture.completedFuture(null);}
        @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason){closed.complete(code);return CompletableFuture.completedFuture(null);}
        JsonNode next()throws Exception{JsonNode value=messages.poll(5,TimeUnit.SECONDS);assertThat(value).isNotNull();return value;}
    }
}
