package com.xiye.formaspace.generation.parameter;

import com.xiye.formaspace.generation.enums.GenerationQuality;

/** Canonical user intent: nullable advanced overrides stay null and do not acquire configuration defaults. */
public record RequestedGenerationParameters(String sourceFileId, GenerationQuality quality, boolean generateTexture,
        AdvancedOptions advancedOptions) {
    public record AdvancedOptions(boolean removeBackground, Long seed, Integer inferenceSteps,
            Double guidanceScale, Integer octreeResolution) {}
}
