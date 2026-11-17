package com.xiye.formaspace.generation.vo;

import com.xiye.formaspace.generation.enums.*;
import java.util.List;
import java.util.Map;

public record GenerationCapabilitiesVO(GenerationMode mode, boolean generationAvailable, boolean simulated,
        String modelId, String profileId, String simulationNotice, List<String> supportedInputModes,
        Map<GenerationQuality, QualityPreset> qualityPresets, boolean textureSupported,
        boolean removeBackgroundSupported, Map<String, ParameterCapability> advancedParameters, long maximumVoxelSteps,
        UploadConstraints uploadConstraints) {
    public record QualityPreset(boolean simulated, int inferenceSteps, double guidanceScale, int octreeResolution) {}
    public record ParameterCapability(boolean supported, Object defaultValue, Number minimum,
            Number maximum, List<Integer> allowedValues, boolean rangeAdvisory) {
        public ParameterCapability(boolean supported, Object defaultValue, Number minimum,
                Number maximum, List<Integer> allowedValues) {
            this(supported, defaultValue, minimum, maximum, allowedValues, false);
        }
    }
    public record UploadConstraints(List<String> formats, long maximumBytes, long maximumPixels) {}
}
