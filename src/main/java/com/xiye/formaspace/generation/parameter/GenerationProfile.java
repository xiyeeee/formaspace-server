package com.xiye.formaspace.generation.parameter;

import com.xiye.formaspace.generation.enums.GenerationQuality;
import com.xiye.formaspace.generation.config.GenerationProperties.Preset;
import java.util.List;
import java.util.Map;

public record GenerationProfile(String id, String modelId, boolean simulated, long maximumSeed,
        int minimumSteps, int maximumSteps, double minimumGuidance, double maximumGuidance,
        List<Integer> octreeResolutions, long maximumVoxelSteps, int numChunks, Map<GenerationQuality, Preset> presets) {}
