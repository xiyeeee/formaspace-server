package com.xiye.formaspace.generation.parameter;

import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.dto.*;
import com.xiye.formaspace.generation.enums.GenerationMode;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class GenerationParameterResolver {
    private final GenerationProperties profile;
    private final SecureRandom random = new SecureRandom();

    public GenerationParameterResolver(GenerationProperties profile) {
        this.profile = profile;
        profile.activeProfile().presets().values().forEach(preset -> validateCombination(
                preset.inferenceSteps(), preset.guidanceScale(), preset.octreeResolution()));
    }

    public RequestedGenerationParameters requested(CreateGenerationDTO request) {
        var options = request.getAdvancedOptions();
        if (options == null) { options = new AdvancedGenerationOptionsDTO(); }
        return new RequestedGenerationParameters(request.getSourceFileId(), request.getQuality(),
                Boolean.TRUE.equals(request.getGenerateTexture()),
                new RequestedGenerationParameters.AdvancedOptions(!Boolean.FALSE.equals(options.getRemoveBackground()),
                        options.getSeed(), options.getInferenceSteps(), options.getGuidanceScale(), options.getOctreeResolution()));
    }

    public ResolvedGenerationParameters resolve(RequestedGenerationParameters requested) {
        if (requested.generateTexture()) { throw new BusinessException(ErrorCode.UNSUPPORTED_CAPABILITY); }
        var preset = profile.activeProfile().presets().get(requested.quality());
        if (preset == null) { throw new BusinessException(ErrorCode.UNSUPPORTED_CAPABILITY); }
        var advanced = requested.advancedOptions();
        int steps = advanced.inferenceSteps() == null ? preset.inferenceSteps() : advanced.inferenceSteps();
        double guidance = advanced.guidanceScale() == null ? preset.guidanceScale() : advanced.guidanceScale();
        int octree = advanced.octreeResolution() == null ? preset.octreeResolution() : advanced.octreeResolution();
        validateCombination(steps, guidance, octree);
        if (profile.getMode() == GenerationMode.PYTHON_REALTIME) {
            var realtime = profile.getPythonRealtime();
            if (!advanced.removeBackground()) throw new BusinessException(ErrorCode.UNSUPPORTED_CAPABILITY);
            if (realtime.isPreviewEnabled() && realtime.getPreviewStep() >= steps) {
                throw new BusinessException(ErrorCode.INVALID_GENERATION_PARAMETERS);
            }
        }
        Long specifiedSeed = advanced.seed();
        if (specifiedSeed != null && (specifiedSeed < 0 || specifiedSeed > profile.activeProfile().maximumSeed())) {
            throw new BusinessException(ErrorCode.INVALID_GENERATION_PARAMETERS);
        }
        long seed = specifiedSeed == null ? random.nextLong(profile.activeProfile().maximumSeed() + 1) : specifiedSeed;
        return new ResolvedGenerationParameters(1, profile.activeProfile().id(), requested.quality(), false,
                advanced.removeBackground(), seed, steps, guidance, octree, profile.activeProfile().numChunks(), "GLB");
    }

    private void validateCombination(int steps, double guidance, int octree) {
        if (steps <= 0 || octree <= 0 || !Double.isFinite(guidance)) {
            throw new BusinessException(ErrorCode.INVALID_GENERATION_PARAMETERS);
        }
        // Local ranges describe the UI reference, not an application-level inference limit.
        if (profile.getMode() == GenerationMode.HUNYUAN_LOCAL) return;
        // Mock retains its simulated resource policy.
        double voxelSteps = (double) steps * octree * octree * octree;
        if (steps < profile.activeProfile().minimumSteps() || steps > profile.activeProfile().maximumSteps() || !Double.isFinite(guidance)
                || guidance < profile.activeProfile().minimumGuidance() || guidance > profile.activeProfile().maximumGuidance()
                || !profile.activeProfile().octreeResolutions().contains(octree) || voxelSteps > profile.activeProfile().maximumVoxelSteps()) {
            throw new BusinessException(ErrorCode.INVALID_GENERATION_PARAMETERS);
        }
    }
}
