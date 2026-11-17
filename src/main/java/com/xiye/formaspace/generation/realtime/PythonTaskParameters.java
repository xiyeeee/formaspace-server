package com.xiye.formaspace.generation.realtime;

import com.xiye.formaspace.generation.config.PythonRealtimeProperties;
import com.xiye.formaspace.generation.parameter.ResolvedGenerationParameters;

public record PythonTaskParameters(long seed, int steps, double guidanceScale, int finalResolution,
        boolean previewEnabled, int previewStep, int previewResolution, int pointCount, int numChunks) {
    public static PythonTaskParameters from(ResolvedGenerationParameters p, PythonRealtimeProperties config) {
        return new PythonTaskParameters(p.seed(), p.inferenceSteps(), p.guidanceScale(), p.octreeResolution(),
                config.isPreviewEnabled(), config.getPreviewStep(), config.getPreviewResolution(), config.getPointCount(), p.numChunks());
    }
}
