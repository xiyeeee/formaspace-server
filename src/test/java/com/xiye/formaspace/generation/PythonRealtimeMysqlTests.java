package com.xiye.formaspace.generation;

import com.xiye.formaspace.auth.service.TokenService;
import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.generation.dto.CreateGenerationDTO;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.realtime.*;
import com.xiye.formaspace.generation.service.GenerationTaskService;
import com.xiye.formaspace.testsupport.*;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import com.xiye.formaspace.user.vo.UserVO;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"formaspace.generation.mode=PYTHON_REALTIME","formaspace.generation.python-realtime.initial-delay-ms=3600000",
        "formaspace.generation.python-realtime.reconnect-initial-delay=30ms","formaspace.generation.python-realtime.reconnect-max-delay=100ms",
        "formaspace.generation.python-realtime.reconciliation-interval=100ms","formaspace.email-code.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext
@EnabledIfEnvironmentVariable(named="REALTIME_TEST_DB_URL",matches="jdbc:mysql:.*")
class PythonRealtimeMysqlTests {
    private static PythonRealtimeFixture server;
    private static synchronized PythonRealtimeFixture fixture() {
        if(server==null)try{server=new PythonRealtimeFixture();}catch(Exception error){throw new IllegalStateException(error);}
        return server;
    }
    @AfterAll static void stopServer(){if(server!=null)server.close();}
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) throws Exception {
        String url=System.getenv("REALTIME_TEST_DB_URL");
        if(!URI.create(url.substring(5)).getPath().matches("/[A-Za-z0-9_]+_test"))throw new IllegalStateException("只允许独立 _test 数据库");
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->System.getenv("REALTIME_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->System.getenv("REALTIME_TEST_DB_PASSWORD"));
        registry.add("formaspace.jwt.secret",()->Base64.getEncoder().encodeToString(new byte[64]));
        registry.add("formaspace.email-code.hmac-key",()->Base64.getEncoder().encodeToString("independent-test-hmac-key-32-bytes".getBytes()));
        Path storage=Files.createTempDirectory("formaspace-realtime-mysql-").toRealPath();
        registry.add("formaspace.storage.root",()->storage.toString());
        registry.add("formaspace.generation.python-realtime.base-url",()->fixture().httpUrl().toString());
        registry.add("formaspace.generation.python-realtime.ws-base-url",()->fixture().wsUrl().toString());
        registry.add("formaspace.generation.python-realtime.token",()->PythonRealtimeFixture.TOKEN);
    }
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserMapper users;
    @Autowired private TokenService tokens;
    @Autowired private FileService files;
    @Autowired private GenerationTaskService tasks;
    @Autowired private GenerationTaskMapper mapper;
    @Autowired private PythonRealtimeWorker worker;
    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext context;

    @BeforeEach void clearDisposableDatabase() {
        for(var table:List.of("fs_generation_preview","fs_model_asset","fs_generation_task","fs_file","fs_user","fs_email_verification","fs_auth_rate_limit"))
            jdbc.update("DELETE FROM "+table);
    }

    private UserEntity user(String prefix) {
        var user=new UserEntity();String name=prefix+UUID.randomUUID().toString().substring(0,8);
        user.setUsername(name);user.setEmail(name+"@example.com");user.setNickname(name);user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("{test}unused");users.insert(user);return user;
    }
    private void await(BooleanSupplier condition,PythonRealtimeWorker worker) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(!condition.getAsBoolean() && System.nanoTime()<end){worker.tick();Thread.sleep(30);}
        assertThat(condition.getAsBoolean()).withFailMessage("HTTP submits=%s WS handshakes=%s preview GETs=%s state=%s",
                server.submissions.get(),server.handshakes.get(),server.previewGets.get(),server.snapshot.get()).isTrue();
    }
    @Test void persistsRealSqlStateRecoversGapAndPreviewThenRepublishesAfterJavaRestart() throws Exception {
        var owner=user("rt_owner_");var other=user("rt_other_");
        String ownerToken=tokens.issue(UserVO.from(owner)).accessToken(),otherToken=tokens.issue(UserVO.from(other)).accessToken();
        var uploaded=files.upload(owner.getId(),new MockMultipartFile("file","input.png","image/png",MediaFixtures.png()));
        var request=new CreateGenerationDTO();request.setSourceFileId(uploaded.id());
        String key=UUID.randomUUID().toString();
        String body=mvc.perform(post("/api/generation/tasks").header("Authorization","Bearer "+ownerToken)
                .header("Idempotency-Key",key).contentType("application/json").content(server.json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.simulated").value(false)).andReturn().getResponse().getContentAsString();
        long id=Long.parseLong(server.json.readTree(body).path("data").path("id").asString());
        var identity=mapper.selectById(id);
        assertThat(identity.getInferenceTaskId()).isNotBlank();assertThat(identity.getPythonParameters()).contains("\"previewStep\":24");
        server.onConnect=socket->{
            if(server.handshakes.get()==1){
                for(int sequence=1;sequence<=20;sequence++){
                    var state=server.state("running",sequence,sequence,0);server.snapshot.set(state);
                    PythonRealtimeFixture.send(socket,server.envelope("sampling_step",state,Map.of()),true);
                    if(sequence==5)PythonRealtimeFixture.send(socket,server.envelope("sampling_step",state,Map.of()),true);
                }
                try{socket.close();}catch(Exception ignored){}
            } else if(server.handshakes.get()==2){
                // Drop sequence 21: this preview event cannot be trusted; the next snapshot must restore it over HTTP.
                var preview=server.state("running",22,24,1);server.snapshot.set(preview);
                var payload=new HashMap<String,Object>(server.preview());payload.put("positionsPath",server.path()+"/previews/1");
                PythonRealtimeFixture.send(socket,server.envelope("preview_ready",preview,payload),true);
            } else if(server.handshakes.get()==3){
                var failed=server.state("running",23,24,1);server.snapshot.set(failed);
                PythonRealtimeFixture.send(socket,server.envelope("preview_failed",failed,Map.of("code","PREVIEW_DECODE_FAILED","message","preview failed","fatal",false)),true);
                var state=server.state("running",24,30,1);server.snapshot.set(state);
                PythonRealtimeFixture.send(socket,server.envelope("finalizing",state,Map.of()),true);
            }
        };
        await(()->mapper.selectById(id).getStoredPreviewVersion()==1 && "final_decoding".equals(mapper.selectById(id).getInferenceStage()),worker);
        assertThat(mapper.selectById(id).getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(mapper.selectById(id).getPreviewErrorCode()).isEqualTo("PREVIEW_DECODE_FAILED");
        assertThat(server.previewGets.get()).isPositive();assertThat(server.submissions.get()).isEqualTo(1);
        mvc.perform(get("/api/generation/tasks/"+id+"/previews/1").header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.taskId").value(Long.toString(id)))
                .andExpect(jsonPath("$.data.positions.length()").value(5000)).andExpect(jsonPath("$.data.inferenceTaskId").doesNotExist());
        mvc.perform(get("/api/generation/tasks/"+id+"/previews/1").header("Authorization","Bearer "+otherToken)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fs_model_asset WHERE generation_task_id=?",Integer.class,id)).isZero();
        worker.close();server.snapshot.set(server.state("completed",25,30,1));server.resultStatus.set(409);
        var restarted=context.getAutowireCapableBeanFactory().createBean(PythonRealtimeWorker.class);
        try {
            await(()->server.resultGets.get()>0,restarted);
            assertThat(mapper.selectById(id).getStatus()).isEqualTo(GenerationStatus.PROCESSING);
            server.resultStatus.set(200);
            await(()->mapper.selectById(id).getStatus()==GenerationStatus.SUCCEEDED,restarted);
            assertThat(mapper.selectById(id).getInferenceTaskId()).isEqualTo(identity.getInferenceTaskId());
            assertThat(server.submissions.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fs_model_asset WHERE generation_task_id=?",Integer.class,id)).isEqualTo(1);
            server.broadcast(server.envelope("task_completed",server.snapshot.get(),Map.of("modelPath",server.path()+"/result","format","glb")));
            restarted.tick();Thread.sleep(100);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fs_model_asset WHERE generation_task_id=?",Integer.class,id)).isEqualTo(1);
            var completed=tasks.detail(id,owner.getId());assertThat(completed.simulated()).isFalse();assertThat(completed.assetId()).isNotNull();
            mvc.perform(get("/api/assets/"+completed.assetId()+"/model").header("Authorization","Bearer "+ownerToken))
                    .andExpect(status().isOk()).andExpect(content().bytes(MediaFixtures.glb()));
            assertThat(tasks.create(owner.getId(),key,request).created()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='5' AND success=1",Integer.class)).isEqualTo(1);
        } finally {restarted.close();}
    }
}
