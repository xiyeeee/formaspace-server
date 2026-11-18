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
            if (!config.isPreviewStageCountSupported()) throw new IllegalArgumentException("previewStageCount 仅支持 3 或 6");
            boolean sixStages = config.getPreviewStageCount() == 6;
            double[] fractions = sixStages ? new double[] {0.4, 0.5, 0.6, 0.7, 0.8, 0.9} : new double[] {0.4, 0.6, 0.8};
            int[] resolutions = sixStages ? new int[] {64, 64, 96, 96, 128, 128} : new int[] {64, 96, 128};
            int[] counts = sixStages ? new int[] {1000, 1500, 2500, 3500, 4500, 5000} : new int[] {1000, 2500, 5000};
            int previousStep = 0;
            for (int index = 0; index < fractions.length; index++) {
                int step = Math.max(1, Math.min(parameters.inferenceSteps() - 1,
                        (int) Math.floor(parameters.inferenceSteps() * fractions[index])));
                PreviewStage stage=new PreviewStage(step, Math.min(resolutions[index], config.getPreviewResolution()),
                        Math.min(counts[index], config.getPointCount()));
                if (step == previousStep) {
                    // Short tasks share candidate steps; retain the highest budget for that step.
                    stages.set(stages.size()-1,stage);
                    continue;
                }
                stages.add(stage);
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
