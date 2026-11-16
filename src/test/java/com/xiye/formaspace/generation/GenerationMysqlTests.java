package com.xiye.formaspace.generation;

import com.xiye.formaspace.auth.service.TokenService;
import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.dto.CreateGenerationDTO;
import com.xiye.formaspace.generation.enums.*;
import com.xiye.formaspace.generation.inference.*;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.parameter.GenerationSnapshots;
import com.xiye.formaspace.generation.service.*;
import com.xiye.formaspace.generation.worker.MockGenerationWorker;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.testsupport.MediaFixtures;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import com.xiye.formaspace.user.vo.UserVO;
import java.net.URI;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"formaspace.generation.mode=MOCK", "formaspace.generation.mock-initial-delay-ms=3600000",
        "formaspace.generation.mock-stage-delay=1s"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(GenerationMysqlTests.TimeConfig.class)
@EnabledIfEnvironmentVariable(named="GENERATION_TEST_DB_URL", matches="jdbc:mysql:.*")
class GenerationMysqlTests {
    private static final Path FIXTURES = createFixtures();
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserMapper users;
    @Autowired private TokenService tokens;
    @Autowired private FileService files;
    @Autowired private GenerationTaskService tasks;
    @Autowired private GenerationTaskMapper taskMapper;
    @Autowired private GenerationProperties properties;
    @Autowired private GenerationSnapshots snapshots;
    @Autowired private ModelInferenceClient client;
    @Autowired private GenerationResultPublisher publisher;
    @Autowired private GlbValidator glbValidator;
    @Autowired private FileStorageProperties storage;
    @Autowired private MutableClock clock;
    private long owner;
    private String ownerToken;
    private String otherToken;

    private static Path createFixtures() {
        try {
            Path root = Files.createTempDirectory("formaspace-generation-mysql-").toRealPath();
            Files.write(root.resolve("demo.glb"), MediaFixtures.glb());
            return root;
        } catch (Exception exception) { throw new IllegalStateException("Cannot create test fixtures"); }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        String url = System.getenv("GENERATION_TEST_DB_URL");
        if (!URI.create(url.substring(5)).getPath().matches("/[A-Za-z0-9_]+_test")) {
            throw new IllegalStateException("Only a disposable MySQL database ending in _test is permitted");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("GENERATION_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("GENERATION_TEST_DB_PASSWORD"));
        registry.add("formaspace.jwt.secret", GenerationMysqlTests::key);
        registry.add("formaspace.email-code.hmac-key", GenerationMysqlTests::key);
        registry.add("formaspace.storage.root", () -> FIXTURES.resolve("storage").toString());
        registry.add("formaspace.generation.mock-demo-glb", () -> FIXTURES.resolve("demo.glb").toString());
    }

    private static String key() {
        byte[] bytes = new byte[64]; new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void resetDisposableSchema() {
        for (String table : List.of("fs_model_asset","fs_generation_task","fs_file","fs_user","fs_email_verification","fs_auth_rate_limit")) {
            jdbc.update("DELETE FROM " + table + " WHERE id > 0");
        }
        clock.set(Instant.parse("2026-11-16T00:00:00Z"));
        properties.setMode(GenerationMode.MOCK);
        properties.setProfileId("mock-v1");
        var first = user("first"); owner = first.getId(); ownerToken = tokens.issue(UserVO.from(first)).accessToken();
        otherToken = tokens.issue(UserVO.from(user("second"))).accessToken();
    }

    private UserEntity user(String name) {
        var user = new UserEntity(); user.setUsername(name); user.setEmail(name+"@example.com");
        user.setNickname(name); user.setStatus(UserStatus.ACTIVE); user.setPasswordHash("{test}not-used");
        users.insert(user); return user;
    }

    private String upload() throws Exception {
        String body = mvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "source.png", "image/png", MediaFixtures.png()))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("id").asString();
    }

    private CreateGenerationDTO request(String fileId) {
        var request = new CreateGenerationDTO(); request.setSourceFileId(fileId); return request;
    }

    private MockGenerationWorker worker(ModelInferenceClient inference) {
        return new MockGenerationWorker(taskMapper, properties, snapshots, files, inference, publisher, clock);
    }

    private void completeWithNewWorkerInstances(ModelInferenceClient inference) {
        worker(inference).tick();
        clock.advance(Duration.ofSeconds(2)); worker(inference).tick();
        clock.advance(Duration.ofSeconds(2)); worker(inference).tick();
    }

    @Test
    void fullUploadTaskMockResultReadDownloadAndStableParameterSnapshots() throws Exception {
        String fileId = upload();
        mvc.perform(get("/api/files/"+fileId+"/content").header("Authorization", "Bearer "+ownerToken))
                .andExpect(status().isOk()).andExpect(content().bytes(MediaFixtures.png()));
        var request = request(fileId);
        String key = UUID.randomUUID().toString();
        String body = mvc.perform(post("/api/generation/tasks").header("Authorization", "Bearer "+ownerToken)
                        .header("Idempotency-Key", key).contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.simulated").value(true)).andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(json.readTree(body).get("data").get("id").asString());
        var initial = tasks.detail(id, owner);
        properties.setProfileId("new-config-should-not-change-history");
        assertThat(tasks.create(owner, key, request).task().resolvedParameters()).isEqualTo(initial.resolvedParameters());
        completeWithNewWorkerInstances(client);
        var complete = tasks.detail(id, owner);
        assertThat(complete.status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(complete.progress()).isNull();
        assertThat(complete.assetId()).isNotNull();
        assertThat(complete.resolvedParameters()).isEqualTo(initial.resolvedParameters());
        mvc.perform(get("/api/assets/"+complete.assetId()).header("Authorization", "Bearer "+ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.simulated").value(true))
                .andExpect(jsonPath("$.data.modelUrl").value("/api/assets/"+complete.assetId()+"/model"));
        for (String action : List.of("model","download")) {
            mvc.perform(get("/api/assets/"+complete.assetId()+"/"+action).header("Authorization", "Bearer "+ownerToken))
                    .andExpect(status().isOk()).andExpect(content().bytes(MediaFixtures.glb()))
                    .andExpect(header().string("Cache-Control", "private, no-store"));
        }
        worker(client).tick();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fs_model_asset", Integer.class)).isEqualTo(1);
    }

    @Test
    void otherUserCannotReadSourceTaskAssetModelOrDownload() throws Exception {
        String fileId = upload();
        var created = tasks.create(owner, UUID.randomUUID().toString(), request(fileId));
        completeWithNewWorkerInstances(client);
        var complete = tasks.detail(Long.parseLong(created.task().id()), owner);
        for (String path : List.of("/api/files/"+fileId+"/content", "/api/generation/tasks/"+complete.id(),
                "/api/assets/"+complete.assetId(), "/api/assets/"+complete.assetId()+"/model", "/api/assets/"+complete.assetId()+"/download")) {
            mvc.perform(get(path).header("Authorization", "Bearer "+otherToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void sameKeyConcurrentRequestsCreateOneTaskAndReuseSeed() throws Exception {
        var request = request(upload()); String key=UUID.randomUUID().toString();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<GenerationTaskService.Creation>> actions = List.of(() -> tasks.create(owner,key,request), () -> tasks.create(owner,key,request));
            var futures = executor.invokeAll(actions);
            var first=futures.get(0).get(); var second=futures.get(1).get();
            assertThat(first.created()).isNotEqualTo(second.created());
            assertThat(first.task().id()).isEqualTo(second.task().id());
            assertThat(first.task().resolvedParameters()).isEqualTo(second.task().resolvedParameters());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fs_generation_task",Integer.class)).isEqualTo(1);
        request.setQuality(GenerationQuality.HIGH);
        assertBusiness(() -> tasks.create(owner,key,request),ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void differentKeysConcurrentRequestsCannotExceedUserCapacity() throws Exception {
        var request=request(upload());
        Callable<Boolean> action=() -> {
            try { tasks.create(owner,UUID.randomUUID().toString(),request); return true; }
            catch (BusinessException error) { assertThat(error.getErrorCode()).isEqualTo(ErrorCode.GENERATION_LIMIT_REACHED); return false; }
        };
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=executor.invokeAll(List.of(action,action));
            assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fs_generation_task",Integer.class)).isEqualTo(1);
    }

    @Test
    void disabledCreatesNothingButAllowsIdempotentReplay() throws Exception {
        var request=request(upload()); properties.setMode(GenerationMode.DISABLED);
        assertBusiness(() -> tasks.create(owner,UUID.randomUUID().toString(),request),ErrorCode.INFERENCE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fs_generation_task",Integer.class)).isZero();
        properties.setMode(GenerationMode.MOCK); String key=UUID.randomUUID().toString();
        var created=tasks.create(owner,key,request); properties.setMode(GenerationMode.DISABLED);
        assertThat(tasks.create(owner,key,request).task()).isEqualTo(created.task());
    }

    @Test
    void missingDemoFailsAndTerminalStateCannotRegress() throws Exception {
        var created=tasks.create(owner,UUID.randomUUID().toString(),request(upload()));
        var missing=new MockModelInferenceClient(new GenerationProperties(),glbValidator,storage);
        completeWithNewWorkerInstances(missing);
        long id=Long.parseLong(created.task().id());
        assertThat(tasks.detail(id,owner).status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(tasks.detail(id,owner).errorCode()).isEqualTo("MOCK_RESULT_NOT_CONFIGURED");
        assertThat(taskMapper.processing(id,clock.instant())).isZero();
        assertThat(taskMapper.succeed(id,clock.instant())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fs_model_asset",Integer.class)).isZero();
    }

    private void assertBusiness(Runnable action,ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class TimeConfig { @Bean @Primary MutableClock generationClock() { return new MutableClock(); } }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-11-16T00:00:00Z"));
        void set(Instant value) { now.set(value); }
        void advance(Duration duration) { now.updateAndGet(value->value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
