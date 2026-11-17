package com.xiye.formaspace.generation;

import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.config.GenerationConfig;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.dto.*;
import com.xiye.formaspace.generation.enums.*;
import com.xiye.formaspace.generation.parameter.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

class GenerationParameterTests {
    private final GenerationProperties profile = new GenerationProperties();
    private final GenerationParameterResolver resolver = new GenerationParameterResolver(profile);

    private CreateGenerationDTO request() {
        var request = new CreateGenerationDTO();
        request.setSourceFileId("1");
        return request;
    }

    @Test
    void defaultsResolveCompletelyAndCanonicalNullOptionsAreEquivalent() {
        var request = request();
        var original = resolver.requested(request);
        var resolved = resolver.resolve(original);
        assertThat(resolved.inferenceSteps()).isEqualTo(5);
        assertThat(resolved.guidanceScale()).isEqualTo(5);
        assertThat(resolved.octreeResolution()).isEqualTo(128);
        assertThat(resolved.seed()).isBetween(0L, 2147483647L);
        assertThat(resolved.removeBackground()).isTrue();
        assertThat(resolved.generateTexture()).isFalse();
        assertThat(resolved.format()).isEqualTo("GLB");
        assertThat(original.advancedOptions().seed()).isNull();
        request.setAdvancedOptions(new AdvancedGenerationOptionsDTO());
        assertThat(resolver.requested(request)).isEqualTo(original);
    }

    @Test
    void explicitAdvancedValuesOverrideHighPresetAndSnapshotsSurviveConfigurationChanges() {
        var request = request();
        request.setQuality(GenerationQuality.HIGH);
        var advanced = new AdvancedGenerationOptionsDTO();
        advanced.setSeed(0L);
        advanced.setInferenceSteps(2);
        advanced.setGuidanceScale(0.0);
        advanced.setOctreeResolution(128);
        advanced.setRemoveBackground(false);
        request.setAdvancedOptions(advanced);
        var resolved = resolver.resolve(resolver.requested(request));
        assertThat(resolved.seed()).isZero();
        assertThat(resolved.inferenceSteps()).isEqualTo(2);
        assertThat(resolved.removeBackground()).isFalse();
        var snapshots = new GenerationSnapshots(JsonMapper.builder().build());
        String saved = snapshots.encode(resolved);
        profile.setQualityPresets(Map.of(GenerationQuality.HIGH, new GenerationProperties.Preset(1, 1, 128)));
        assertThat(snapshots.decode(saved, ResolvedGenerationParameters.class)).isEqualTo(resolved);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 11})
    void rejectsNonFiniteAndOutOfRangeGuidance(double guidance) {
        var request = request();
        var advanced = new AdvancedGenerationOptionsDTO();
        advanced.setGuidanceScale(guidance);
        request.setAdvancedOptions(advanced);
        assertRejected(request, ErrorCode.INVALID_GENERATION_PARAMETERS);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 21, 2147483647})
    void rejectsInvalidSteps(int steps) {
        var request = request();
        var advanced = new AdvancedGenerationOptionsDTO();
        advanced.setInferenceSteps(steps);
        request.setAdvancedOptions(advanced);
        assertRejected(request, ErrorCode.INVALID_GENERATION_PARAMETERS);
    }

    @Test
    void rejectsTextureUnsupportedOctreeSeedAndCrossFieldWorkBudget() {
        var request = request();
        request.setGenerateTexture(true);
        assertRejected(request, ErrorCode.UNSUPPORTED_CAPABILITY);
        request.setGenerateTexture(false);
        var advanced = new AdvancedGenerationOptionsDTO();
        request.setAdvancedOptions(advanced);
        advanced.setOctreeResolution(192);
        assertRejected(request, ErrorCode.INVALID_GENERATION_PARAMETERS);
        advanced.setOctreeResolution(256);
        advanced.setInferenceSteps(20);
        assertRejected(request, ErrorCode.INVALID_GENERATION_PARAMETERS);
        advanced.setInferenceSteps(5);
        advanced.setSeed(-1L);
        assertRejected(request, ErrorCode.INVALID_GENERATION_PARAMETERS);
        advanced.setSeed(2147483648L);
        assertRejected(request, ErrorCode.INVALID_GENERATION_PARAMETERS);
    }

    @Test
    void mockRequiresExplicitDevelopmentProfileAndAlwaysRejectsProduction() {
        profile.setMode(GenerationMode.MOCK);
        assertThatThrownBy(() -> new GenerationConfig(profile, new MockEnvironment())).isInstanceOf(IllegalStateException.class);
        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        new GenerationConfig(profile, local);
        local.setActiveProfiles("local", "prod");
        assertThatThrownBy(() -> new GenerationConfig(profile, local)).isInstanceOf(IllegalStateException.class);
        profile.setMode(GenerationMode.DISABLED);
        new GenerationConfig(profile, new MockEnvironment());
    }


    @Test
    void realProfileUsesAdjustableGradioRangesAndPreservesExplicitValues() {
        profile.setMode(GenerationMode.HUNYUAN_LOCAL);
        var realResolver = new GenerationParameterResolver(profile);
        var request = request();
        var defaults = realResolver.resolve(realResolver.requested(request));
        assertThat(defaults.inferenceSteps()).isEqualTo(30);
        assertThat(defaults.octreeResolution()).isEqualTo(256);
        assertThat(defaults.profileId()).isEqualTo("hunyuan-mini-mps-adjustable-v2");
        assertThat(profile.activeProfile().octreeResolutions()).contains(16, 128, 256, 512);
        assertThat(profile.activeProfile().octreeResolutions()).doesNotContain(15, 513);
        var advanced = new AdvancedGenerationOptionsDTO();
        advanced.setInferenceSteps(50);
        advanced.setGuidanceScale(7.5);
        advanced.setOctreeResolution(512);
        advanced.setSeed(0L);
        request.setAdvancedOptions(advanced);
        var resolved = realResolver.resolve(realResolver.requested(request));
        assertThat(resolved.inferenceSteps()).isEqualTo(50);
        assertThat(resolved.guidanceScale()).isEqualTo(7.5);
        assertThat(resolved.octreeResolution()).isEqualTo(512);
        assertThat(resolved.seed()).isZero();
        advanced.setInferenceSteps(100);
        assertThat(realResolver.resolve(realResolver.requested(request)).inferenceSteps()).isEqualTo(100);
        advanced.setInferenceSteps(150);
        assertThat(realResolver.resolve(realResolver.requested(request)).inferenceSteps()).isEqualTo(150);
        advanced.setInferenceSteps(30);
        advanced.setOctreeResolution(768);
        advanced.setGuidanceScale(15.0);
        var unrestricted = realResolver.resolve(realResolver.requested(request));
        assertThat(unrestricted.octreeResolution()).isEqualTo(768);
        assertThat(unrestricted.guidanceScale()).isEqualTo(15.0);
        advanced.setOctreeResolution(0);
        assertThatThrownBy(() -> realResolver.resolve(realResolver.requested(request))).isInstanceOf(BusinessException.class);
        advanced.setOctreeResolution(256);
        advanced.setGuidanceScale(Double.NaN);
        assertThatThrownBy(() -> realResolver.resolve(realResolver.requested(request))).isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsSubmittedRobotParametersWithoutReplacingExplicitValues() {
        profile.setMode(GenerationMode.HUNYUAN_LOCAL);
        var realResolver = new GenerationParameterResolver(profile);
        var request = request();
        request.setSourceFileId("6");
        var advanced = new AdvancedGenerationOptionsDTO();
        advanced.setInferenceSteps(50);
        advanced.setGuidanceScale(10.0);
        advanced.setOctreeResolution(512);
        advanced.setRemoveBackground(false);
        advanced.setSeed(0L);
        request.setAdvancedOptions(advanced);
        var resolved = realResolver.resolve(realResolver.requested(request));
        assertThat(resolved.inferenceSteps()).isEqualTo(50);
        assertThat(resolved.guidanceScale()).isEqualTo(10.0);
        assertThat(resolved.octreeResolution()).isEqualTo(512);
        assertThat(resolved.removeBackground()).isFalse();
        assertThat(resolved.seed()).isZero();
        assertThat(resolved.generateTexture()).isFalse();
    }

    @Test
    void realtimeUsesDeliveredPythonLimitsAndRejectsUnsupportedPreprocessingAndPreviewSteps() {
        profile.setMode(GenerationMode.PYTHON_REALTIME);
        var realtimeResolver=new GenerationParameterResolver(profile);
        var request=request();var advanced=new AdvancedGenerationOptionsDTO();request.setAdvancedOptions(advanced);
        assertThat(realtimeResolver.resolve(realtimeResolver.requested(request)).inferenceSteps()).isEqualTo(30);
        advanced.setRemoveBackground(false);
        assertThatThrownBy(()->realtimeResolver.resolve(realtimeResolver.requested(request))).isInstanceOfSatisfying(BusinessException.class,
                error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_CAPABILITY));
        advanced.setRemoveBackground(true);advanced.setOctreeResolution(512);
        assertThatThrownBy(()->realtimeResolver.resolve(realtimeResolver.requested(request))).isInstanceOf(BusinessException.class);
        advanced.setOctreeResolution(256);advanced.setInferenceSteps(24);
        assertThatThrownBy(()->realtimeResolver.resolve(realtimeResolver.requested(request))).isInstanceOf(BusinessException.class);
        profile.getPythonRealtime().setPreviewEnabled(false);advanced.setInferenceSteps(2);
        assertThat(realtimeResolver.resolve(realtimeResolver.requested(request)).inferenceSteps()).isEqualTo(2);
        advanced.setInferenceSteps(51);
        assertThatThrownBy(()->realtimeResolver.resolve(realtimeResolver.requested(request))).isInstanceOf(BusinessException.class);
    }

    private void assertRejected(CreateGenerationDTO request, ErrorCode code) {
        assertThatThrownBy(() -> resolver.resolve(resolver.requested(request)))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
