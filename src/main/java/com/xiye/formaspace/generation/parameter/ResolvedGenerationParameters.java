package com.xiye.formaspace.generation.parameter;

import com.xiye.formaspace.generation.enums.GenerationQuality;

public record ResolvedGenerationParameters(int schemaVersion, String profileId, GenerationQuality quality,
        boolean generateTexture, boolean removeBackground, long seed, int inferenceSteps,
        double guidanceScale, int octreeResolution, int numChunks, String format) {}
