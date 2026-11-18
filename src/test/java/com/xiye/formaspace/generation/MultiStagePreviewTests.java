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
    @Test void persistsBoundedPlanAndRestoresItWithoutCurrentConfig() throws Exception {
        PythonRealtimeProperties config=new PythonRealtimeProperties();
        PythonTaskParameters original=PythonTaskParameters.from(parameters(30),config);
        assertThat(original.previewStages()).extracting(PythonTaskParameters.PreviewStage::step).containsExactly(12,18,24);
        assertThat(original.previewStages()).extracting(PythonTaskParameters.PreviewStage::pointCount).containsExactly(1000,2500,5000);
        config.setPointCount(20);config.setSteps(50);
        ObjectMapper json=new ObjectMapper();
        PythonTaskParameters restored=json.readValue(json.writeValueAsString(original),PythonTaskParameters.class);
        assertThat(restored).isEqualTo(original);
        // Failed attempts do not consume a success version: version 1 may come from the second stage.
        restored.validatePreview(new PythonRealtimeProtocol.PointCloud(1,18,2500,List.of()));
        assertThatThrownBy(()->restored.validatePreview(new PythonRealtimeProtocol.PointCloud(2,18,1000,List.of())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(()->restored.validatePreview(new PythonRealtimeProtocol.PointCloud(4,24,5000,List.of())))
                .isInstanceOf(IOException.class);
    }
    @Test void shortTasksDeduplicateStepsAndRespectExistingMemoryCaps() {
        PythonRealtimeProperties config=new PythonRealtimeProperties();config.setPointCount(700);config.setPreviewResolution(64);
        PythonTaskParameters request=PythonTaskParameters.from(parameters(2),config);
        assertThat(request.previewStages()).containsExactly(new PythonTaskParameters.PreviewStage(1,64,700));
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
