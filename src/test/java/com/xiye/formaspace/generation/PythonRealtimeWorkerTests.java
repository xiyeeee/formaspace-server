package com.xiye.formaspace.generation;

import com.xiye.formaspace.common.enums.FilePurpose;
import com.xiye.formaspace.common.exception.ErrorCode;
import com.xiye.formaspace.file.entity.FileEntity;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
import com.xiye.formaspace.generation.enums.*;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.realtime.*;
import com.xiye.formaspace.generation.service.GenerationResultPublisher;
import com.xiye.formaspace.testsupport.PythonRealtimeFixture;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonRealtimeWorkerTests {
    private final GenerationTaskMapper tasks=mock(GenerationTaskMapper.class);
    private final PythonRealtimeClient client=mock(PythonRealtimeClient.class);
    private final GenerationPreviewService previews=mock(GenerationPreviewService.class);
    private final GenerationResultPublisher results=mock(GenerationResultPublisher.class);
    private final FileService files=mock(FileService.class);
    private final GenerationEventPublisher events=mock(GenerationEventPublisher.class);
    private final GenerationProperties config=new GenerationProperties();
    private final GenerationTaskEntity task=new GenerationTaskEntity();
    private final JsonMapper json=JsonMapper.builder().build();
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"),ZoneOffset.UTC);
    private PythonRealtimeWorker worker;
    private PythonRealtimeFixture fixture;
    @BeforeEach void setup() throws Exception {
        config.setMode(GenerationMode.PYTHON_REALTIME);fixture=new PythonRealtimeFixture();
        task.setId(7L);task.setUserId(2L);task.setSourceFileId(3L);task.setStatus(GenerationStatus.SUBMITTING);
        task.setInferenceTaskId(UUID.randomUUID().toString());fixture.taskId=task.getInferenceTaskId();task.setInferenceService("original-service");
        task.setSubmissionState("UNKNOWN");task.setSubmissionAttempts(1);task.setLastPythonSequence(-1L);
        task.setTotalSteps(30);task.setCompletedSteps(0);task.setPythonPreviewVersion(0);task.setStoredPreviewVersion(0);task.setQueryFailures(0);
        task.setDeadlineAt(clock.instant().plusSeconds(60));
        task.setPythonParameters(json.writeValueAsString(new PythonTaskParameters(1234,30,5,256,true,24,128,5000,4000)));
        when(tasks.realtimeTasks(anyInt())).thenAnswer(invocation->task.getStatus()==GenerationStatus.FAILED?List.of():List.of(task));
        when(tasks.selectById(7L)).thenReturn(task);when(tasks.saveRealtime(any())).thenReturn(1);
        when(tasks.failRealtime(eq(7L),anyString(),anyString(),any())).thenAnswer(invocation->{task.setStatus(GenerationStatus.FAILED);return 1;});
        when(client.serviceKey()).thenReturn("original-service");
        var image=new FileEntity();image.setId(3L);image.setContentType("image/png");
        when(files.requireOwned(3,2,FilePurpose.SOURCE_IMAGE)).thenReturn(image);
        worker=create();
    }
    private PythonRealtimeWorker create() {return new PythonRealtimeWorker(tasks,config,client,new PythonRealtimeProtocol(),previews,results,files,events,json,clock);}
    @AfterEach void cleanup(){worker.close();fixture.close();}
    @Test void unknownPostAndJavaRestartReuseUuidAndExactParameters() throws Exception {
        when(client.snapshot(anyString())).thenThrow(new PythonRealtimeClient.RemoteHttpException(404,0));
        doThrow(new IOException("lost response")).when(client).submitTask(anyString(),anyString(),any());
        worker.tick();verify(client,timeout(3000)).submitTask(eq(task.getInferenceTaskId()),eq(task.getPythonParameters()),any());
        worker.close();task.setNextPollAt(null);worker=create();worker.tick();
        verify(client,timeout(3000).times(2)).submitTask(eq(task.getInferenceTaskId()),eq(task.getPythonParameters()),any());
        assertThat(task.getSubmissionAttempts()).isEqualTo(3);
    }
    @Test void conflictFailsWithoutChangingIdentityOrReplayingSubmission() throws Exception {
        when(client.snapshot(anyString())).thenThrow(new PythonRealtimeClient.RemoteHttpException(404,0));
        doThrow(new PythonRealtimeClient.RemoteHttpException(409,0)).when(client).submitTask(anyString(),anyString(),any());
        String identity=task.getInferenceTaskId();worker.tick();
        verify(tasks,timeout(3000)).failRealtime(eq(7L),eq("INFERENCE_SUBMISSION_CONFLICT"),anyString(),any());
        assertThat(task.getInferenceTaskId()).isEqualTo(identity);
    }
    @Test void capacityFailurePersistsDelayAndDoesNotBusySubmit() throws Exception {
        when(client.snapshot(anyString())).thenThrow(new PythonRealtimeClient.RemoteHttpException(404,0));
        doThrow(new PythonRealtimeClient.RemoteHttpException(429,12)).when(client).submitTask(anyString(),anyString(),any());
        worker.tick();verify(tasks,timeout(3000).atLeast(2)).saveRealtime(any());
        worker.close();assertThat(task.getSubmissionState()).isEqualTo("DEFERRED");
        assertThat(task.getNextPollAt()).isAfterOrEqualTo(clock.instant().plusSeconds(12));
        worker=create();worker.tick();worker.close();verify(client,times(1)).submitTask(anyString(),anyString(),any());
    }
    @Test void acceptedTaskMissingOnProviderIsNeverResubmitted() throws Exception {
        task.setSubmissionState("ACCEPTED");when(client.snapshot(anyString())).thenThrow(new PythonRealtimeClient.RemoteHttpException(404,0));
        worker.tick();verify(tasks,timeout(3000)).failRealtime(eq(7L),eq("INFERENCE_REFERENCE_LOST"),anyString(),any());
        verify(client,never()).submitTask(anyString(),anyString(),any());
    }
    @Test void sameSequenceSnapshotRetriesPreviewWithoutCompletingAtThirtyOfThirty() throws Exception {
        task.setLastPythonSequence(30L);task.setPythonPreviewVersion(1);task.setCompletedSteps(30);
        when(client.snapshot(anyString())).thenReturn(fixture.state("running",30,30,1));
        when(client.preview(task.getInferenceTaskId(),1)).thenReturn(json.valueToTree(fixture.preview()));
        worker.tick();verify(previews,timeout(3000)).save(eq(7L),any());worker.close();
        assertThat(task.getStatus()).isEqualTo(GenerationStatus.PROCESSING);assertThat(task.getInferenceStage()).isEqualTo("final_decoding");
        verifyNoInteractions(results);
    }
    @Test void pythonRestartFailsTaskWhileRetainingStoredPreview() throws Exception {
        task.setStoredPreviewVersion(1);task.setPythonPreviewVersion(1);
        when(client.snapshot(anyString())).thenReturn(fixture.state("failed",40,24,1));
        worker.tick();verify(tasks,timeout(3000)).failRealtime(eq(7L),eq("SERVICE_RESTARTED"),anyString(),any());
        assertThat(task.getStoredPreviewVersion()).isEqualTo(1);verifyNoInteractions(previews,results);
    }
    @Test void deadlineIsUncertainRatherThanCancellationAndServiceBindingDoesNotFollowConfigChanges() throws Exception {
        task.setDeadlineAt(clock.instant());worker.tick();
        verify(tasks,timeout(3000)).failRealtime(eq(7L),eq("INFERENCE_STATE_UNCERTAIN"),anyString(),any());
        verifyNoInteractions(results);verify(client,never()).submitTask(anyString(),anyString(),any());
    }
    @Test void changedServiceDoesNotQueryOrSubmitAgainstAnotherProvider() throws Exception {
        when(client.serviceKey()).thenReturn("different-service");worker.tick();
        verify(tasks,timeout(3000)).saveRealtime(any());worker.close();
        assertThat(task.getConnectionState()).isEqualTo("SERVICE_CHANGED");
        verify(client,never()).snapshot(anyString());verify(client,never()).submitTask(anyString(),anyString(),any());
    }
    @Test void missingPreviewDoesNotBlockFinalModelAndCanRecoverAfterBusinessCompletion() throws Exception {
        when(client.snapshot(anyString())).thenReturn(fixture.state("completed",40,30,1));
        when(client.preview(task.getInferenceTaskId(),1)).thenThrow(new IOException("temporarily missing"))
                .thenReturn(json.valueToTree(fixture.preview()));
        doAnswer(invocation->{task.setStatus(GenerationStatus.SUCCEEDED);return null;})
                .when(results).publish(7L,client,task.getInferenceTaskId());
        when(tasks.realtimePreviewRepairs(anyString(),any(),anyInt())).thenAnswer(invocation->
                task.getStatus()==GenerationStatus.SUCCEEDED?List.of(task):List.of());
        worker.tick();verify(previews,timeout(3000)).save(eq(7L),any());worker.close();
        verify(results).publish(7L,client,task.getInferenceTaskId());
        assertThat(task.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        verify(client,never()).submitTask(anyString(),anyString(),any());
    }
}
