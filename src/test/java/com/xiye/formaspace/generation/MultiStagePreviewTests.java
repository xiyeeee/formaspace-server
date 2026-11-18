package com.xiye.formaspace.generation;

import com.xiye.formaspace.generation.config.PythonRealtimeProperties;
import com.xiye.formaspace.generation.enums.GenerationQuality;
import com.xiye.formaspace.generation.parameter.ResolvedGenerationParameters;
import com.xiye.formaspace.generation.realtime.PythonRealtimeProtocol;
import com.xiye.formaspace.generation.realtime.PythonTaskParameters;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class MultiStagePreviewTests {
    private ResolvedGenerationParameters parameters(int steps) {
        return new ResolvedGenerationParameters(1,"test",GenerationQuality.STANDARD,false,true,1234,steps,5,256,4000,"GLB");
    }
    @Test void newTasksDefaultToThreeStages() {
        PythonRealtimeProperties config=new PythonRealtimeProperties();
        assertThat(config.getPreviewStageCount()).isEqualTo(3);
        PythonTaskParameters request=PythonTaskParameters.from(parameters(30),config);
        assertThat(request.previewStages()).extracting(PythonTaskParameters.PreviewStage::step).containsExactly(12,18,24);
        assertThat(request.previewStages()).extracting(PythonTaskParameters.PreviewStage::resolution).containsExactly(64,96,128);
        assertThat(request.previewStages()).extracting(PythonTaskParameters.PreviewStage::pointCount).containsExactly(1000,2500,5000);
    }
    @Test void unsupportedStageCountFailsValidationAndPlanCreation() {
        try (jakarta.validation.ValidatorFactory factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            for (int count:List.of(0,1,4,5,7)) {
                PythonRealtimeProperties config=new PythonRealtimeProperties();config.setPreviewStageCount(count);
                assertThat(factory.getValidator().validate(config)).isNotEmpty();
                assertThatThrownBy(()->PythonTaskParameters.from(parameters(30),config)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
    @Test void persistsBoundedPlanAndRestoresItWithoutCurrentConfig() throws Exception {
        PythonRealtimeProperties config=new PythonRealtimeProperties();
        config.setPreviewStageCount(6);
        PythonTaskParameters original=PythonTaskParameters.from(parameters(30),config);
        assertThat(original.previewStages()).extracting(PythonTaskParameters.PreviewStage::step).containsExactly(12,15,18,21,24,27);
        assertThat(original.previewStages()).extracting(PythonTaskParameters.PreviewStage::pointCount).containsExactly(1000,1500,2500,3500,4500,5000);
        config.setPointCount(20);config.setSteps(50);config.setPreviewStageCount(3);
        ObjectMapper json=new ObjectMapper();
        PythonTaskParameters restored=json.readValue(json.writeValueAsString(original),PythonTaskParameters.class);
        assertThat(restored).isEqualTo(original);
        // Failed attempts do not consume a success version: version 1 may come from the second stage.
        restored.validatePreview(new PythonRealtimeProtocol.PointCloud(1,18,2500,List.of()));
        assertThatThrownBy(()->restored.validatePreview(new PythonRealtimeProtocol.PointCloud(2,18,1000,List.of())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(()->restored.validatePreview(new PythonRealtimeProtocol.PointCloud(7,27,5000,List.of())))
                .isInstanceOf(IOException.class);
    }
    @Test void previousThreeStageTasksKeepTheirOriginalContract() throws Exception {
        List<PythonTaskParameters.PreviewStage> stages=List.of(new PythonTaskParameters.PreviewStage(12,64,1000),
                new PythonTaskParameters.PreviewStage(18,96,2500),new PythonTaskParameters.PreviewStage(24,128,5000));
        PythonTaskParameters original=new PythonTaskParameters(1234,30,5,256,true,24,128,5000,4000,stages);
        ObjectMapper json=new ObjectMapper();
        PythonTaskParameters restored=json.readValue(json.writeValueAsString(original),PythonTaskParameters.class);
        restored.validatePreview(new PythonRealtimeProtocol.PointCloud(3,24,5000,List.of()));
        assertThat(restored.previewStages()).hasSize(3);
        assertThatThrownBy(()->restored.validatePreview(new PythonRealtimeProtocol.PointCloud(4,27,5000,List.of())))
                .isInstanceOf(IOException.class);
    }
    @Test void shortTasksDeduplicateStepsAndRespectExistingMemoryCaps() {
        for (int count:List.of(3,6)) {
            PythonRealtimeProperties config=new PythonRealtimeProperties();config.setPreviewStageCount(count);
            config.setPointCount(700);config.setPreviewResolution(64);
            PythonTaskParameters request=PythonTaskParameters.from(parameters(2),config);
            assertThat(request.previewStages()).containsExactly(new PythonTaskParameters.PreviewStage(1,64,700));
            PythonTaskParameters full=PythonTaskParameters.from(parameters(30),config);
            assertThat(full.previewStages()).hasSize(count);
            assertThat(full.previewStages()).extracting(PythonTaskParameters.PreviewStage::resolution).containsOnly(64);
            assertThat(full.previewStages()).extracting(PythonTaskParameters.PreviewStage::pointCount).containsOnly(700);
        }
    }
    @Test void scaledShortPlanMergesDuplicateStepsKeepingFinalBudget() {
        for (int count:List.of(3,6)) {
            PythonRealtimeProperties config=new PythonRealtimeProperties();config.setPreviewStageCount(count);
            PythonTaskParameters request=PythonTaskParameters.from(parameters(5),config);
            assertThat(request.previewStages()).extracting(PythonTaskParameters.PreviewStage::step).containsExactly(2,3,4);
            assertThat(request.previewStages()).extracting(PythonTaskParameters.PreviewStage::pointCount)
                    .containsExactlyElementsOf(count==3?List.of(1000,2500,5000):List.of(1500,3500,5000));
        }
    }
    @Test void legacyRequestsKeepSinglePreviewContractAndCanDisableNewPlan() throws Exception {
        PythonRealtimeProperties config=new PythonRealtimeProperties();config.setMultiStagePreview(false);
        PythonTaskParameters request=PythonTaskParameters.from(parameters(30),config);
        assertThat(request.previewStages()).isNull();
        request.validatePreview(new PythonRealtimeProtocol.PointCloud(1,24,5000,List.of()));
        assertThatThrownBy(()->request.validatePreview(new PythonRealtimeProtocol.PointCloud(1,12,1000,List.of())))
                .isInstanceOf(IOException.class);
        assertThat(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(request)).has("previewStages")).isFalse();
    }
}
