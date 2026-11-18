package com.xiye.formaspace.generation.realtime;

import com.xiye.formaspace.generation.config.PythonRealtimeProperties;
import com.xiye.formaspace.generation.parameter.ResolvedGenerationParameters;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record PythonTaskParameters(long seed, int steps, double guidanceScale, int finalResolution,
        boolean previewEnabled, int previewStep, int previewResolution, int pointCount, int numChunks,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        List<PreviewStage> previewStages) {
    public PythonTaskParameters {
        if (previewStages != null) previewStages = List.copyOf(previewStages);
    }
    // Previously persisted requests retain their single-preview contract.
    public PythonTaskParameters(long seed, int steps, double guidanceScale, int finalResolution,
            boolean previewEnabled, int previewStep, int previewResolution, int pointCount, int numChunks) {
        this(seed, steps, guidanceScale, finalResolution, previewEnabled, previewStep, previewResolution,
                pointCount, numChunks, null);
    }
    public static PythonTaskParameters from(ResolvedGenerationParameters parameters, PythonRealtimeProperties config) {
        List<PreviewStage> stages = null;
        if (config.isPreviewEnabled() && config.isMultiStagePreview()) {
            stages = new ArrayList<>();
            double[] fractions = {0.4, 0.6, 0.8};
            int[] resolutions = {64, 96, 128};
            int[] counts = {1000, 2500, 5000};
            int previousStep = 0;
            for (int index = 0; index < fractions.length; index++) {
                int step = Math.max(1, Math.min(parameters.inferenceSteps() - 1,
                        (int) Math.floor(parameters.inferenceSteps() * fractions[index])));
                if (step <= previousStep) continue;
                stages.add(new PreviewStage(step, Math.min(resolutions[index], config.getPreviewResolution()),
                        Math.min(counts[index], config.getPointCount())));
                previousStep = step;
            }
        }
        return new PythonTaskParameters(parameters.seed(), parameters.inferenceSteps(), parameters.guidanceScale(),
                parameters.octreeResolution(), config.isPreviewEnabled(), config.getPreviewStep(),
                config.getPreviewResolution(), config.getPointCount(), parameters.numChunks(), stages);
    }
    public void validatePreview(PythonRealtimeProtocol.PointCloud cloud) throws IOException {
        List<PreviewStage> plan = previewStages == null
                ? List.of(new PreviewStage(previewStep, previewResolution, pointCount)) : previewStages;
        PythonRealtimeProtocol.require(previewEnabled && cloud.previewVersion() <= plan.size()
                && plan.stream().anyMatch(stage -> stage.step() == cloud.sourceStep() && stage.pointCount() == cloud.pointCount()));
    }
    public record PreviewStage(int step, int resolution, int pointCount) {}
}
