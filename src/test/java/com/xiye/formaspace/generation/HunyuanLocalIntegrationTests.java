package com.xiye.formaspace.generation;

import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.enums.GenerationMode;
import com.xiye.formaspace.generation.enums.GenerationQuality;
import com.xiye.formaspace.generation.inference.GlbValidator;
import com.xiye.formaspace.generation.inference.HunyuanLocalInferenceClient;
import com.xiye.formaspace.generation.inference.ModelInferenceClient.*;
import com.xiye.formaspace.generation.parameter.ResolvedGenerationParameters;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real HTTP/SSE inference check; does not start Python, download weights or require MySQL. */
@EnabledIfEnvironmentVariable(named = "HUNYUAN_INTEGRATION_IMAGE", matches = ".+")
class HunyuanLocalIntegrationTests {
    @TempDir Path temporary;

    @Test
    void generatesAndDownloadsSelfContainedGlbUsingRealClient() throws Exception {
        var image = Path.of(System.getenv("HUNYUAN_INTEGRATION_IMAGE"));
        assertThat(image).isRegularFile();
        var properties = new GenerationProperties();
        properties.setMode(GenerationMode.HUNYUAN_LOCAL);
        var local = properties.getHunyuanLocal();
        local.setBaseUrl(URI.create(System.getenv().getOrDefault("HUNYUAN_BASE_URL", "http://127.0.0.1:7860")));
        local.setAuthorization(System.getenv("HUNYUAN_AUTHORIZATION"));
        var storage = new FileStorageProperties();
        var json = JsonMapper.builder().build();
        var client = new HunyuanLocalInferenceClient(properties, storage, json, Clock.systemUTC());
        assertThat(client.readiness().ready()).as("Running model metadata must match the configured model").isTrue();
        var preset = local.getStandardPreset();
        var parameters = new ResolvedGenerationParameters(1, local.getProfileId(), GenerationQuality.STANDARD,
                false, true, 1234, preset.inferenceSteps(), preset.guidanceScale(), preset.octreeResolution(),
                local.getNumChunks(), "GLB");
        String contentType = image.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
        var submission = client.submit(new Submission(1, parameters,
                new SourceImage(1, contentType, () -> Files.newInputStream(image)), Instant.now().plus(local.getTaskTimeout())));
        var result = client.query(submission.remoteReference());
        assertThat(result.state()).isEqualTo(QueryState.SUCCEEDED);
        var model = temporary.resolve("real-hunyuan.glb");
        try (var input = client.openResult(result.resultReference())) {
            Files.copy(input, model);
        }
        new GlbValidator(json).validate(model, storage.getMaxModelBytes());
        var glb = Files.readAllBytes(model);
        int jsonLength = java.nio.ByteBuffer.wrap(glb, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        var document = json.readTree(java.util.Arrays.copyOfRange(glb, 20, 20 + jsonLength));
        assertThat(document.path("meshes").size()).as("Real result contains mesh geometry").isPositive();
        System.out.println("Real Hunyuan GLB verified: bytes=" + glb.length + ", seed=1234, steps="
                + preset.inferenceSteps() + ", octree=" + preset.octreeResolution());
    }
}
