package com.xiye.formaspace.generation;

import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.dto.CreateGenerationDTO;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
import com.xiye.formaspace.generation.enums.*;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.parameter.*;
import com.xiye.formaspace.generation.service.GenerationTaskService;
import com.xiye.formaspace.testsupport.TestTransactions;
import com.xiye.formaspace.user.mapper.UserMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationTaskServiceTests {
    private final GenerationTaskMapper tasks = mock(GenerationTaskMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final FileService files = mock(FileService.class);
    private final GenerationProperties properties = new GenerationProperties();
    private final GenerationParameterResolver resolver = spy(new GenerationParameterResolver(properties));
    private final GenerationSnapshots snapshots = new GenerationSnapshots(JsonMapper.builder().build());
    private final TestTransactions transactions = new TestTransactions();
    private GenerationTaskService service;
    private final CreateGenerationDTO request = new CreateGenerationDTO();

    @BeforeEach
    void setup() {
        properties.setMode(GenerationMode.MOCK);
        request.setSourceFileId("2");
        when(users.lockActiveIdentity(1L)).thenReturn(1L);
        doAnswer(invocation -> {
            GenerationTaskEntity task = invocation.getArgument(0);
            task.setId(3L);
            task.setCreatedAt(Instant.parse("2026-11-16T00:00:00Z"));
            when(tasks.findOwned(3L, 1L)).thenReturn(task);
            when(tasks.findIdempotent(1L, task.getIdempotencyKey())).thenReturn(task);
            return 1;
        }).when(tasks).insert(any(GenerationTaskEntity.class));
        service = new GenerationTaskService(tasks, users, files, resolver, snapshots, properties, transactions, mock(com.xiye.formaspace.generation.service.GenerationReadiness.class), mock(com.xiye.formaspace.generation.realtime.GenerationPreviewService.class));
    }

    @Test
    void sameKeyReplaysSnapshotsEvenAfterModeAndDefaultsChange() {
        String key = UUID.randomUUID().toString();
        var first = service.create(1, key, request);
        long seed = first.task().resolvedParameters().seed();
        properties.setMode(GenerationMode.DISABLED);
        properties.setProfileId("changed");
        var replay = service.create(1, key.toUpperCase(), request);
        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.task().resolvedParameters().seed()).isEqualTo(seed);
        assertThat(replay.task()).isEqualTo(first.task());
        verify(resolver, times(1)).resolve(any());
        verify(tasks, times(1)).insert(any(GenerationTaskEntity.class));
    }

    @Test
    void sameKeyDifferentIntentReturns409AndDoesNotGenerateSeedAgain() {
        String key = UUID.randomUUID().toString();
        service.create(1, key, request);
        request.setQuality(GenerationQuality.HIGH);
        assertBusiness(() -> service.create(1, key, request), ErrorCode.IDEMPOTENCY_CONFLICT);
        verify(resolver, times(1)).resolve(any());
    }

    @Test
    void disabledAndOverCapacityDoNotInsertAndUserLockPrecedesChecks() {
        properties.setMode(GenerationMode.DISABLED);
        assertBusiness(() -> service.create(1, UUID.randomUUID().toString(), request), ErrorCode.INFERENCE_UNAVAILABLE);
        verify(tasks, never()).insert(any(GenerationTaskEntity.class));
        properties.setMode(GenerationMode.MOCK);
        when(tasks.countActive(1L)).thenReturn(1L);
        assertBusiness(() -> service.create(1, UUID.randomUUID().toString(), request), ErrorCode.GENERATION_LIMIT_REACHED);
        var order = inOrder(users, tasks);
        order.verify(users).lockActiveIdentity(1L);
        order.verify(tasks).findIdempotent(eq(1L), anyString());
        order.verify(users).lockActiveIdentity(1L);
        order.verify(tasks).findIdempotent(eq(1L), anyString());
        order.verify(tasks).countActive(1L);
        verify(tasks, never()).insert(any(GenerationTaskEntity.class));
    }

    @Test
    void ownershipFailuresPropagateAndDatabaseFailuresRollback() {
        doThrow(new BusinessException(ErrorCode.NOT_FOUND)).when(files).requireOwned(anyLong(), anyLong(), any());
        assertBusiness(() -> service.create(1, UUID.randomUUID().toString(), request), ErrorCode.NOT_FOUND);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertBusiness(() -> service.detail(9, 1), ErrorCode.NOT_FOUND);
        doThrow(new DataAccessResourceFailureException("internal SQL")).when(users).lockActiveIdentity(1L);
        assertThatThrownBy(() -> service.create(1, UUID.randomUUID().toString(), request)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(transactions.rollbacks).isEqualTo(2);
    }

    private void assertBusiness(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
