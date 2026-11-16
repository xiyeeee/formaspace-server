package com.xiye.formaspace.generation.parameter;

import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.dto.*;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class GenerationParameterResolver {
    private final GenerationProperties profile;
    private final SecureRandom random = new SecureRandom();

    public GenerationParameterResolver(GenerationProperties profile) {
        this.profile = profile;
        profile.getQualityPresets().values().forEach(preset -> validateCombination(
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
        var preset = profile.getQualityPresets().get(requested.quality());
        if (preset == null) { throw new BusinessException(ErrorCode.UNSUPPORTED_CAPABILITY); }
        var advanced = requested.advancedOptions();
        int steps = advanced.inferenceSteps() == null ? preset.inferenceSteps() : advanced.inferenceSteps();
        double guidance = advanced.guidanceScale() == null ? preset.guidanceScale() : advanced.guidanceScale();
        int octree = advanced.octreeResolution() == null ? preset.octreeResolution() : advanced.octreeResolution();
        validateCombination(steps, guidance, octree);
        Long specifiedSeed = advanced.seed();
        if (specifiedSeed != null && (specifiedSeed < 0 || specifiedSeed > Integer.MAX_VALUE)) {
            throw new BusinessException(ErrorCode.INVALID_GENERATION_PARAMETERS);
        }
        long seed = specifiedSeed == null ? random.nextLong(2147483648L) : specifiedSeed;
        return new ResolvedGenerationParameters(1, profile.getProfileId(), requested.quality(), false,
                advanced.removeBackground(), seed, steps, guidance, octree, profile.getNumChunks(), "GLB");
    }

    private void validateCombination(int steps, double guidance, int octree) {
        // This joint product resource budget is independent of official UI slider ranges.
        double voxelSteps = (double) steps * octree * octree * octree;
        if (steps < profile.getMinimumSteps() || steps > profile.getMaximumSteps() || !Double.isFinite(guidance)
                || guidance < profile.getMinimumGuidance() || guidance > profile.getMaximumGuidance()
                || !profile.getOctreeResolutions().contains(octree) || voxelSteps > profile.getMaximumVoxelSteps()) {
            throw new BusinessException(ErrorCode.INVALID_GENERATION_PARAMETERS);
        }
    }
}
