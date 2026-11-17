package com.xiye.formaspace.generation;

import com.xiye.formaspace.common.exception.ErrorCode;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import com.xiye.formaspace.generation.inference.HunyuanLocalInferenceClient;
import com.xiye.formaspace.generation.inference.ModelInferenceClient.QueryResult;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.parameter.GenerationSnapshots;
import com.xiye.formaspace.generation.service.GenerationResultPublisher;
import com.xiye.formaspace.generation.worker.HunyuanGenerationWorker;
import java.io.IOException;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HunyuanGenerationWorkerTests {
    private final GenerationTaskMapper tasks = mock(GenerationTaskMapper.class);
    private final HunyuanLocalInferenceClient client = mock(HunyuanLocalInferenceClient.class);
    private final GenerationResultPublisher publisher = mock(GenerationResultPublisher.class);
    private final GenerationProperties properties = new GenerationProperties();
    private final Instant now = Instant.parse("2026-11-17T05:00:00Z");
    private final GenerationTaskEntity task = new GenerationTaskEntity();
    private HunyuanGenerationWorker worker;

    @BeforeEach
    void setup() {
        var json = JsonMapper.builder().build();
        worker = new HunyuanGenerationWorker(tasks, properties, mock(FileService.class),
                new GenerationSnapshots(json), client, publisher, json, Clock.fixed(now, ZoneOffset.UTC));
        task.setId(1L);
        task.setStatus(GenerationStatus.PROCESSING);
        task.setRemoteReference("durable-event");
        task.setDeadlineAt(now.plusSeconds(300));
        when(tasks.activeReal()).thenReturn(List.of(task));
    }

    @Test
    void resumesDurableEventAndPersistsResultBeforePublicationWithoutClaimingAnotherTask() throws Exception {
        when(client.query("durable-event")).thenReturn(new QueryResult(true, "durable-result", null));
        when(tasks.resultReadyReal(1L, "durable-result", now)).thenReturn(1);
        worker.tick();
        var order = inOrder(tasks, client, publisher);
        order.verify(client).query("durable-event");
        order.verify(tasks).resultReadyReal(1L, "durable-result", now);
        order.verify(publisher).publish(1L, client, "durable-result");
        verify(tasks, never()).pendingReal();
        verify(client, never()).submit(any());
    }

    @Test
    void resumesSavedResultWithoutConsumingEventAgain() throws Exception {
        task.setRemoteResultReference("durable-result");
        worker.tick();
        verify(publisher).publish(1L, client, "durable-result");
        verifyNoInteractions(client);
    }

    @Test
    void unresolvedSubmissionAfterRestartFailsWithoutResubmission() {
        task.setStatus(GenerationStatus.SUBMITTING);
        worker.tick();
        verify(tasks).failReal(1L, ErrorCode.SUBMISSION_UNCERTAIN.name(), ErrorCode.SUBMISSION_UNCERTAIN.getMessage(), now);
        verifyNoInteractions(client, publisher);
    }

    @Test
    void temporaryDisconnectUsesBoundedBackoffAndEventuallyFails() throws Exception {
        when(client.query("durable-event")).thenThrow(new IOException("connection lost"));
        task.setQueryFailures(1);
        worker.tick();
        verify(tasks).retryReal(1L, now.plusSeconds(10), now);
        verify(tasks, never()).failReal(anyLong(), anyString(), anyString(), any());
        task.setQueryFailures(properties.getHunyuanLocal().getMaximumRetries());
        worker.tick();
        verify(tasks).failReal(1L, ErrorCode.INFERENCE_UNAVAILABLE.name(), ErrorCode.INFERENCE_UNAVAILABLE.getMessage(), now);
        verifyNoInteractions(publisher);
    }

    @Test
    void doesNotQueryBeforeBackoffOrAfterDeadline() {
        task.setNextPollAt(now.plusSeconds(10));
        worker.tick();
        verifyNoInteractions(client);
        task.setDeadlineAt(now);
        worker.tick();
        verify(tasks).failReal(1L, ErrorCode.INFERENCE_TIMEOUT.name(), ErrorCode.INFERENCE_TIMEOUT.getMessage(), now);
        verifyNoInteractions(client, publisher);
    }

    @Test
    void lateCompletedEventCannotPublishAfterConditionalUpdateWasRejected() throws Exception {
        when(client.query("durable-event")).thenReturn(new QueryResult(true, "durable-result", null));
        when(tasks.resultReadyReal(1L, "durable-result", now)).thenReturn(0);
        worker.tick();
        verifyNoInteractions(publisher);
    }
}
