package com.xiye.formaspace.generation;

import com.xiye.formaspace.asset.mapper.ModelAssetMapper;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.file.entity.FileEntity;
import com.xiye.formaspace.file.mapper.FileMapper;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.file.storage.LocalFileStorage;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
import com.xiye.formaspace.generation.enums.*;
import com.xiye.formaspace.generation.inference.*;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.parameter.*;
import com.xiye.formaspace.generation.service.GenerationResultPublisher;
import com.xiye.formaspace.generation.worker.MockGenerationWorker;
import com.xiye.formaspace.testsupport.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MockLifecycleTests {
    @TempDir Path temporary;
    private final GenerationTaskMapper tasks = mock(GenerationTaskMapper.class);
    private final GenerationProperties properties = new GenerationProperties();
    private final GenerationSnapshots snapshots = new GenerationSnapshots(JsonMapper.builder().build());
    private final FileService files = mock(FileService.class);
    private final ModelInferenceClient client = mock(ModelInferenceClient.class);
    private final GenerationResultPublisher publisher = mock(GenerationResultPublisher.class);
    private final Instant start = Instant.parse("2026-11-16T00:00:00Z");

    private MockGenerationWorker worker(Instant now) {
        return new MockGenerationWorker(tasks, properties, snapshots, files, client, publisher, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void recreatedWorkerResumesPersistedSubmittingAndProcessingThenMissingDemoFails() throws Exception {
        var task = new GenerationTaskEntity();
        task.setId(1L); task.setUserId(2L); task.setSourceFileId(3L); task.setStatus(GenerationStatus.SUBMITTING);
        task.setUpdatedAt(start);
        when(tasks.activeMock(100)).thenAnswer(invocation ->
                task.getStatus() == GenerationStatus.FAILED ? List.of() : List.of(task));
        var source = new FileEntity(); source.setId(3L); source.setContentType("image/png");
        when(files.requireOwned(eq(3L), eq(2L), any())).thenReturn(source);
        when(tasks.processing(eq(1L), any())).thenAnswer(invocation -> {
            task.setStatus(GenerationStatus.PROCESSING); task.setUpdatedAt(invocation.getArgument(1)); return 1;
        });
        when(client.query(1L)).thenReturn(new ModelInferenceClient.QueryResult(true, null, ErrorCode.MOCK_RESULT_NOT_CONFIGURED));
        when(tasks.fail(eq(1L), anyString(), anyString(), any())).thenAnswer(invocation -> {
            task.setStatus(GenerationStatus.FAILED); return 1;
        });
        worker(start.plusSeconds(3)).tick();
        assertThat(task.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        worker(start.plusSeconds(6)).tick();
        assertThat(task.getStatus()).isEqualTo(GenerationStatus.FAILED);
        worker(start.plusSeconds(9)).tick();
        verify(tasks, times(1)).fail(eq(1L), eq("MOCK_RESULT_NOT_CONFIGURED"), anyString(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void databaseOutageDuringCompletionLeavesTaskRecoverable() throws Exception {
        var task = new GenerationTaskEntity(); task.setId(1L); task.setStatus(GenerationStatus.PROCESSING); task.setUpdatedAt(start);
        when(tasks.activeMock(100)).thenReturn(List.of(task));
        when(client.query(1L)).thenReturn(new ModelInferenceClient.QueryResult(true, "configured-demo", null));
        doThrow(new DataAccessResourceFailureException("private SQL")).when(publisher).publish(1L, client, "configured-demo");
        worker(start.plusSeconds(3)).tick();
        verify(tasks, never()).fail(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void resultDatabaseFailureCleansCopiedModelAndCannotSucceed() throws Exception {
        var properties = new FileStorageProperties(); properties.setRoot(temporary.toRealPath());
        var storage = new LocalFileStorage(properties);
        var modelFiles = mock(FileMapper.class);
        var assetMapper = mock(ModelAssetMapper.class);
        var task = new GenerationTaskEntity(); task.setId(1L); task.setUserId(2L);
        task.setStatus(GenerationStatus.PROCESSING); task.setSimulated(true);
        when(tasks.lockTask(1L)).thenReturn(task);
        when(client.openResult("configured-demo")).thenAnswer(invocation -> new ByteArrayInputStream(MediaFixtures.glb()));
        doThrow(new DataAccessResourceFailureException("test SQL")).when(modelFiles).insert(any(FileEntity.class));
        doAnswer(invocation -> { storage.delete(invocation.getArgument(0)); return null; }).when(files).cleanupAfterTransaction(anyString(), eq(false));
        var resultPublisher = new GenerationResultPublisher(tasks, modelFiles, assetMapper, storage, properties,
                files, Clock.fixed(start, ZoneOffset.UTC), new TestTransactions());
        assertThatThrownBy(() -> resultPublisher.publish(1, client, "configured-demo"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(tasks, never()).succeed(anyLong(), any());
        verifyNoInteractions(assetMapper);
        try (var children = Files.list(temporary)) { assertThat(children.count()).isZero(); }
    }

    @Test
    void lateCompletionOfTerminalTaskDiscardsCopyAndDoesNotCreateAsset() throws Exception {
        var properties = new FileStorageProperties(); properties.setRoot(temporary.toRealPath());
        var storage = new LocalFileStorage(properties);
        var task = new GenerationTaskEntity(); task.setStatus(GenerationStatus.SUCCEEDED); task.setSimulated(true);
        when(tasks.lockTask(1L)).thenReturn(task);
        when(client.openResult("configured-demo")).thenAnswer(invocation -> new ByteArrayInputStream(MediaFixtures.glb()));
        doAnswer(invocation -> { storage.delete(invocation.getArgument(0)); return null; }).when(files).cleanup(anyString());
        var modelFiles = mock(FileMapper.class); var assets = mock(ModelAssetMapper.class);
        var resultPublisher = new GenerationResultPublisher(tasks, modelFiles, assets, storage, properties,
                files, Clock.fixed(start, ZoneOffset.UTC), new TestTransactions());
        resultPublisher.publish(1, client, "configured-demo");
        verifyNoInteractions(modelFiles, assets);
        verify(tasks, never()).succeed(anyLong(), any());
        try (var children = Files.list(temporary)) { assertThat(children.count()).isZero(); }
    }

    @Test
    void validatesConfiguredGlbContainerAndRejectsCorruptionWithoutFallbackModel() throws Exception {
        var validator = new GlbValidator(JsonMapper.builder().build());
        Path demo = Files.write(temporary.resolve("demo.glb"), MediaFixtures.glb());
        validator.validate(demo, 100000);
        properties.setMockDemoGlb(demo);
        var mock = new MockModelInferenceClient(properties, validator, new FileStorageProperties());
        assertThat(mock.query(1).resultReference()).isEqualTo("configured-demo");
        try (var result = mock.openResult("configured-demo")) { assertThat(result.readAllBytes()).isEqualTo(MediaFixtures.glb()); }
        Files.writeString(demo, "not GLB");
        assertThatThrownBy(() -> mock.openResult("configured-demo")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new MockModelInferenceClient(properties, validator, new FileStorageProperties())).isInstanceOf(IOException.class);
        properties.setMockDemoGlb(null);
        var noDemo = new MockModelInferenceClient(properties, validator, new FileStorageProperties());
        assertThat(noDemo.query(1).failure()).isEqualTo(ErrorCode.MOCK_RESULT_NOT_CONFIGURED);
    }
}
