package com.xiye.formaspace.generation;

import com.xiye.formaspace.auth.config.JwtProperties;
import com.xiye.formaspace.auth.service.TokenService;
import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.dto.CreateGenerationDTO;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"formaspace.generation.mode=DISABLED","formaspace.email-code.enabled=false","formaspace.cors.allowed-origins=http://localhost:5173"})
@ActiveProfiles("test")
@DirtiesContext
@EnabledIfEnvironmentVariable(named="BROWSER_WS_TEST_DB_URL",matches="jdbc:mysql:.*")
class BrowserWebSocketMysqlTests {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry)throws Exception{
        String url=System.getenv("BROWSER_WS_TEST_DB_URL");if(!URI.create(url.substring(5)).getPath().matches("/[A-Za-z0-9_]+_test"))throw new IllegalStateException("仅允许测试库");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->System.getenv("BROWSER_WS_TEST_DB_USERNAME"));registry.add("spring.datasource.password",()->System.getenv("BROWSER_WS_TEST_DB_PASSWORD"));
        registry.add("formaspace.jwt.secret",()->Base64.getEncoder().encodeToString(new byte[64]));registry.add("formaspace.email-code.hmac-key",()->Base64.getEncoder().encodeToString("browser-ws-independent-hmac-key-32".getBytes()));
        Path root=Files.createTempDirectory("formaspace-browser-ws-").toRealPath();registry.add("formaspace.storage.root",()->root.toString());
    }
    @LocalServerPort private int port;
    @Autowired private UserMapper users;
    @Autowired private TokenService tokens;
    @Autowired private JwtProperties jwt;
    @Autowired private GenerationProperties config;
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
    private GenerationTaskEntity task(UserEntity user)throws Exception{
        config.setMode(GenerationMode.MOCK);String file=files.upload(user.getId(),new MockMultipartFile("file","source.png","image/png",MediaFixtures.png())).id();
        CreateGenerationDTO request=new CreateGenerationDTO();request.setSourceFileId(file);
        long id=Long.parseLong(tasks.create(user.getId(),UUID.randomUUID().toString(),request).task().id());
        GenerationTaskEntity task=mapper.selectById(id);task.setExecutionMode("PYTHON_REALTIME");task.setSimulated(false);task.setStatus(GenerationStatus.PROCESSING);task.setTotalSteps(30);task.setCompletedSteps(0);task.setInferenceStage("preprocessing");task.setConnectionState("CONNECTED");mapper.updateById(task);return task;
    }
    private void subscribe(WebSocket socket,long id){socket.sendText("{\"protocolVersion\":1,\"type\":\"subscribe\",\"taskId\":\""+id+"\"}",true).join();}
    @Test void nativeTicketHandshakeSnapshotOwnershipProgressAndFullPointCloud()throws Exception{
        UserEntity owner=user(),other=user();String ownerToken=token(owner);GenerationTaskEntity task=task(owner);
        String ticket=ticket(ownerToken);Inbox inbox=new Inbox();WebSocket socket=connect(ticket,"http://localhost:5173",inbox);
        try{
            Inbox denied=new Inbox();WebSocket foreign=connect(ticket(token(other)),"http://localhost:5173",denied);
            try{subscribe(foreign,task.getId());assertThat(denied.next().path("code").asString()).isEqualTo("TASK_NOT_FOUND");}finally{foreign.abort();}
            subscribe(socket,task.getId());JsonNode snapshot=inbox.next();assertThat(snapshot.path("type").asString()).isEqualTo("task_snapshot");assertThat(snapshot.path("taskId").isTextual()).isTrue();long initial=snapshot.path("sequence").asLong();
            task.setCompletedSteps(10);task.setInferenceStage("sampling");mapper.updateById(task);events.publish(task.getId(),"sampling_step",12);
            JsonNode step=inbox.next();assertThat(step.path("sequence").asLong()).isGreaterThan(initial);assertThat(step.path("completedSteps").asInt()).isEqualTo(10);
            events.publish(task.getId(),"sampling_step",12);assertThat(inbox.messages.poll(200,TimeUnit.MILLISECONDS)).isNull();
            previews.save(task.getId(),new PythonRealtimeProtocol.PointCloud(1,24,5000,Collections.nCopies(5000,List.of(0.1,0.2,0.3))));previews.recordDecodeSeconds(task.getId(),1,6.2);
            task=mapper.selectById(task.getId());task.setCompletedSteps(24);mapper.updateById(task);events.publish(task.getId(),"preview_ready",28);
            JsonNode preview=inbox.next();assertThat(preview.path("payload").path("positions").size()).isEqualTo(5000);assertThat(preview.path("payload").path("decodeSeconds").asDouble()).isEqualTo(6.2);
            String path=preview.path("payload").path("positionsPath").asString();HttpResponse<String> restored=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+ownerToken).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(restored.statusCode()).isEqualTo(200);assertThat(json.readTree(restored.body()).path("data").path("positions")).isEqualTo(preview.path("payload").path("positions"));
            task.setCompletedSteps(30);task.setInferenceStage("final_decoding");mapper.updateById(task);events.publish(task.getId(),"finalizing",35);
            assertThat(inbox.next().path("type").asString()).isEqualTo("finalizing");assertThat(mapper.selectById(task.getId()).getStatus()).isEqualTo(GenerationStatus.PROCESSING);
            task.setConnectionState("RECONNECTING");mapper.updateById(task);events.publish(task.getId(),"connection_status",35);JsonNode status=inbox.next();assertThat(status.path("type").asString()).isEqualTo("connection_status");assertThat(status.has("sequence")).isFalse();
            socket.sendText("{\"protocolVersion\":1,\"type\":\"ping\",\"requestId\":\"hello\"}",true).join();assertThat(inbox.next().path("type").asString()).isEqualTo("pong");
            assertThatThrownBy(()->connect(ticket,"http://localhost:5173",new Inbox())).isInstanceOf(ExecutionException.class);
        }finally{socket.abort();}
    }
    @Test void invalidOriginDoesNotConsumeTicketAndExpiredJwtClosesConnection()throws Exception{
        UserEntity owner=user();String valid=ticket(token(owner));
        assertThatThrownBy(()->connect(valid,"http://malicious.invalid",new Inbox())).isInstanceOf(ExecutionException.class);
        WebSocket validSocket=connect(valid,"http://localhost:5173",new Inbox());validSocket.abort();
        assertThatThrownBy(()->connect("invalid","http://localhost:5173",new Inbox())).isInstanceOf(ExecutionException.class);
        Duration original=jwt.getAccessTokenTtl();jwt.setAccessTokenTtl(Duration.ofSeconds(2));String shortToken=token(owner);jwt.setAccessTokenTtl(original);
        Inbox inbox=new Inbox();WebSocket socket=connect(ticket(shortToken),"http://localhost:5173",inbox);
        try{assertThat(inbox.closed.get(5,TimeUnit.SECONDS)).isEqualTo(4001);}finally{socket.abort();}
    }
    @Test void subscriptionSnapshotPrecedesConcurrentEventsWithoutLosingLatestSequence()throws Exception{
        UserEntity owner=user();GenerationTaskEntity task=task(owner);Inbox inbox=new Inbox();WebSocket socket=connect(ticket(token(owner)),"http://localhost:5173",inbox);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            Future<?> producer=executor.submit(()->{
                for(int step=1;step<=20;step++){
                    task.setCompletedSteps(step);task.setInferenceStage("sampling");mapper.updateById(task);events.publish(task.getId(),"sampling_step",step);
                }
            });
            subscribe(socket,task.getId());JsonNode first=inbox.next();assertThat(first.path("type").asString()).isEqualTo("task_snapshot");long last=first.path("sequence").asLong();producer.get(10,TimeUnit.SECONDS);
            JsonNode frame;while((frame=inbox.messages.poll(400,TimeUnit.MILLISECONDS))!=null){
                if(!frame.has("sequence"))continue;
                assertThat(frame.path("sequence").asLong()).isGreaterThan(last);last=frame.path("sequence").asLong();
            }
            assertThat(tasks.detail(task.getId(),owner.getId()).completedSteps()).isEqualTo(20);
        }finally{socket.abort();executor.shutdownNow();}
    }
    private final class Inbox implements WebSocket.Listener{
        final BlockingQueue<JsonNode> messages=new LinkedBlockingQueue<>();final CompletableFuture<Integer> closed=new CompletableFuture<>();final StringBuilder parts=new StringBuilder();
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){parts.append(text);if(last){messages.offer(json.readTree(parts.toString()));parts.setLength(0);}socket.request(1);return CompletableFuture.completedFuture(null);}
        @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason){closed.complete(code);return CompletableFuture.completedFuture(null);}
        JsonNode next()throws Exception{JsonNode value=messages.poll(5,TimeUnit.SECONDS);assertThat(value).isNotNull();return value;}
    }
}
