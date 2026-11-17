package com.xiye.formaspace.generation;

import com.xiye.formaspace.config.GenerationConfig;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.enums.GenerationMode;
import com.xiye.formaspace.generation.inference.GlbValidator;
import com.xiye.formaspace.generation.inference.MockModelInferenceClient;
import com.xiye.formaspace.generation.parameter.GenerationParameterResolver;
import com.xiye.formaspace.generation.service.GenerationCapabilitiesService;
import com.xiye.formaspace.common.response.ApiResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

class GenerationConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(GenerationConfig.class, GenerationParameterResolver.class,
                    GlbValidator.class, MockModelInferenceClient.class, JsonConfiguration.class);

    @Test
    void bindsExplicitMockWithEmptyDemoWithoutCreatingARealModelClient() {
        context.withPropertyValues("spring.profiles.active=local", "formaspace.generation.mode=MOCK",
                "formaspace.generation.mock-demo-glb=").run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(MockModelInferenceClient.class);
            assertThat(application.getBean(MockModelInferenceClient.class).query("mock:1").failure().name())
                    .isEqualTo("MOCK_RESULT_NOT_CONFIGURED");
        });
    }

    @Test
    void productionMockFailsDuringContextInitialization() {
        context.withPropertyValues("spring.profiles.active=prod", "formaspace.generation.mode=MOCK")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void documentedCapabilitiesMatchSerializedDefaultMockProfile() throws Exception {
        String document = Files.readString(Path.of("docs/generation-api.md"));
        int section = document.indexOf("## 能力接口完整示例");
        int start = document.indexOf("```json\n", section) + "```json\n".length();
        int end = document.indexOf("\n```", start);
        var json = JsonMapper.builder().build();
        var properties = new GenerationProperties(); properties.setMode(GenerationMode.MOCK);
        var capabilities = new GenerationCapabilitiesService(properties, new FileStorageProperties(), org.mockito.Mockito.mock(com.xiye.formaspace.generation.service.GenerationReadiness.class)).capabilities();
        assertThat(json.readTree(json.writeValueAsString(ApiResponse.success(capabilities))))
                .isEqualTo(json.readTree(document.substring(start,end)));
    }

    @Test
    void realParameterRangesAreMarkedAsAdvisory() {
        var properties = new GenerationProperties();
        properties.setMode(GenerationMode.HUNYUAN_LOCAL);
        var service = new GenerationCapabilitiesService(properties, new FileStorageProperties(),
                org.mockito.Mockito.mock(com.xiye.formaspace.generation.service.GenerationReadiness.class));
        var parameters = service.capabilities().advancedParameters();
        assertThat(parameters.get("inferenceSteps").rangeAdvisory()).isTrue();
        assertThat(parameters.get("guidanceScale").rangeAdvisory()).isTrue();
        assertThat(parameters.get("octreeResolution").rangeAdvisory()).isTrue();
        assertThat(parameters.get("seed").rangeAdvisory()).isFalse();
    }

    @Configuration(proxyBeanMethods=false)
    static class JsonConfiguration {
        @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
        @Bean FileStorageProperties storage() { return new FileStorageProperties(); }
    }
}
