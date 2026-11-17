package com.xiye.formaspace.generation;

import com.xiye.formaspace.auth.service.TokenService;
import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.generation.dto.*;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import com.xiye.formaspace.generation.realtime.GenerationEventPublisher.GenerationEvent;
import com.xiye.formaspace.generation.service.GenerationTaskService;
import com.xiye.formaspace.generation.vo.GenerationTaskVO;
import com.xiye.formaspace.generation.inference.GlbValidator;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import com.xiye.formaspace.user.vo.UserVO;
import java.net.URI;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Explicit opt-in. Uses the running Python model, a real image, real MySQL and Java-owned model storage. */
@SpringBootTest(properties={"formaspace.generation.mode=PYTHON_REALTIME","formaspace.email-code.enabled=false",
        "formaspace.generation.python-realtime.poll-delay-ms=200"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext
@Import(PythonRealtimeLiveTests.Observation.class)
@EnabledIfEnvironmentVariable(named="REALTIME_LIVE_IMAGE",matches=".+")
class PythonRealtimeLiveTests {
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) throws Exception {
        String url=System.getenv("REALTIME_TEST_DB_URL");
        if(url==null || !URI.create(url.substring(5)).getPath().matches("/[A-Za-z0-9_]+_test"))
            throw new IllegalStateException("真实联调也只使用独立 _test 库");
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->System.getenv("REALTIME_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",()->System.getenv("REALTIME_TEST_DB_PASSWORD"));
        registry.add("formaspace.jwt.secret",PythonRealtimeLiveTests::key);
        registry.add("formaspace.email-code.hmac-key",PythonRealtimeLiveTests::key);
        registry.add("formaspace.generation.python-realtime.base-url",()->System.getenv().getOrDefault("REALTIME_LIVE_BASE_URL","http://127.0.0.1:8001"));
        registry.add("formaspace.generation.python-realtime.token",()->System.getenv("INFERENCE_API_TOKEN"));
        Path storage=Files.createTempDirectory("formaspace-realtime-live-").toRealPath();
        registry.add("formaspace.storage.root",()->storage.toString());
    }
    private static String key(){byte[] bytes=new byte[64];new SecureRandom().nextBytes(bytes);return Base64.getEncoder().encodeToString(bytes);}
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserMapper users;
    @Autowired private TokenService tokens;
    @Autowired private GenerationTaskService tasks;
    @Autowired private GlbValidator validator;
    @Autowired private Observer observer;
    @Test @Timeout(value=5,unit=TimeUnit.MINUTES)
    void generatesRealPreviewAndPublishesRealGlbThroughJavaBusinessEndpoints() throws Exception {
        var user=new UserEntity();String name="live_"+UUID.randomUUID().toString().substring(0,8);
        user.setUsername(name);user.setEmail(name+"@example.com");user.setNickname(name);user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("{test}unused");users.insert(user);
        String token=tokens.issue(UserVO.from(user)).accessToken();
        mvc.perform(get("/api/generation/capabilities").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("PYTHON_REALTIME"))
                .andExpect(jsonPath("$.data.generationAvailable").value(true));
        Path image=Path.of(System.getenv("REALTIME_LIVE_IMAGE"));assertThat(image).isRegularFile();
        String uploaded=mvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file","source.png","image/png",Files.readAllBytes(image)))
                .header("Authorization","Bearer "+token)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var request=new CreateGenerationDTO();request.setSourceFileId(json.readTree(uploaded).path("data").path("id").asString());
        var advanced=new AdvancedGenerationOptionsDTO();advanced.setSeed(1234L);request.setAdvancedOptions(advanced);
        String body=mvc.perform(post("/api/generation/tasks").header("Authorization","Bearer "+token)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id=Long.parseLong(json.readTree(body).path("data").path("id").asString());
        long end=System.nanoTime()+Duration.ofMinutes(4).toNanos();GenerationTaskVO task=tasks.detail(id,user.getId());
        Set<String> stages=new HashSet<>();int maximumCompleted=0;
        while(task.status()!=GenerationStatus.SUCCEEDED && task.status()!=GenerationStatus.FAILED && System.nanoTime()<end){
            if(task.stage()!=null)stages.add(task.stage());
            maximumCompleted=Math.max(maximumCompleted,task.completedSteps()==null?0:task.completedSteps());
            Thread.sleep(200);task=tasks.detail(id,user.getId());
        }
        assertThat(task.status()).withFailMessage("真实生成状态=%s code=%s stage=%s",task.status(),task.errorCode(),task.stage()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(task.latestPreview()).isNotNull();assertThat(task.latestPreview().sourceStep()).isEqualTo(24);
        assertThat(task.latestPreview().pointCount()).isEqualTo(5000);assertThat(task.simulated()).isFalse();
        assertThat(task.resolvedParameters().seed()).isEqualTo(1234L);
        assertThat(task.resolvedParameters().inferenceSteps()).isEqualTo(30);assertThat(task.resolvedParameters().octreeResolution()).isEqualTo(256);
        assertThat(observer.types).contains("task_snapshot","sampling_step","preview_ready","task_completed");
        String preview=mvc.perform(get(task.latestPreview().positionsPath()).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.positions.length()").value(5000))
                .andReturn().getResponse().getContentAsString();
        byte[] model=mvc.perform(get("/api/assets/"+task.assetId()+"/model").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        byte[] download=mvc.perform(get("/api/assets/"+task.assetId()+"/download").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(download).isEqualTo(model);
        Path saved=Files.createTempFile("formaspace-live-downloaded-",".glb");Files.write(saved,model);validator.validate(saved,104857600);
        System.out.println("REAL_PYTHON_JAVA_SUCCESS businessTask="+id+" asset="+task.assetId()+" modelBytes="+model.length
                +" previewPoints=5000 sourceStep=24 maxObservedSteps="+maximumCompleted+" stages="+stages+" downloaded="+saved);
    }
    @TestConfiguration static class Observation {@Bean Observer realtimeObserver(){return new Observer();}}
    static class Observer {
        final Queue<String> types=new ConcurrentLinkedQueue<>();
        @EventListener public void onEvent(GenerationEvent event){types.add(event.type());}
    }
}
