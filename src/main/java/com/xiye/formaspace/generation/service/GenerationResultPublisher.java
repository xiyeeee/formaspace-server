package com.xiye.formaspace.generation.service;

import com.xiye.formaspace.asset.entity.ModelAssetEntity;
import com.xiye.formaspace.asset.mapper.ModelAssetMapper;
import com.xiye.formaspace.common.enums.FilePurpose;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.file.entity.FileEntity;
import com.xiye.formaspace.file.mapper.FileMapper;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.file.storage.FileStorage;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import com.xiye.formaspace.generation.inference.ModelInferenceClient;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import java.io.IOException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenerationResultPublisher {
    private final GenerationTaskMapper tasks;
    private final FileMapper files;
    private final ModelAssetMapper assets;
    private final FileStorage storage;
    private final FileStorageProperties limits;
    private final com.xiye.formaspace.generation.inference.GlbValidator glbValidator;
    private final FileService fileService;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public GenerationResultPublisher(GenerationTaskMapper tasks, FileMapper files, ModelAssetMapper assets,
            FileStorage storage, FileStorageProperties limits, FileService fileService, Clock clock,
            PlatformTransactionManager manager, com.xiye.formaspace.generation.inference.GlbValidator glbValidator) {
        this.tasks = tasks;
        this.files = files;
        this.assets = assets;
        this.storage = storage;
        this.limits = limits;
        this.glbValidator = glbValidator;
        this.fileService = fileService;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
    }

    public void publish(long taskId, ModelInferenceClient client, String resultReference) throws IOException {
        FileStorage.StoredFile stored;
        try (var input = client.openResult(resultReference)) {
            stored = storage.save(input, "glb", limits.getMaxModelBytes());
        }
        try (var copy = storage.open(stored.key())) {
            // Validate outside the DB transaction, using a bounded temporary copy, never a remote filesystem path.
            var validationFile = java.nio.file.Files.createTempFile("formaspace-glb-validation-", ".glb");
            try {
                try (var output=java.nio.file.Files.newOutputStream(validationFile)) { copy.stream().transferTo(output); }
                try { glbValidator.validate(validationFile, limits.getMaxModelBytes()); }
                catch (IOException exception) {
                    throw new com.xiye.formaspace.generation.inference.InferenceClientException(
                            com.xiye.formaspace.common.exception.ErrorCode.INFERENCE_PROTOCOL_ERROR);
                }
            } finally { java.nio.file.Files.deleteIfExists(validationFile); }
        } catch (IOException | RuntimeException exception) {
            fileService.cleanup(stored.key());
            throw exception;
        }
        var commitAttempted = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            Boolean published = transactions.execute(status -> {
                var task = tasks.lockTask(taskId);
                if (task == null || task.getStatus() != GenerationStatus.PROCESSING) {
                    return false;
                }
                var file = new FileEntity();
                file.setUserId(task.getUserId());
                file.setOriginalName("model-" + taskId + ".glb");
                file.setStorageKey(stored.key());
                file.setContentType("model/gltf-binary");
                file.setSizeBytes(stored.sizeBytes());
                file.setPurpose(FilePurpose.MODEL);
                files.insert(file);
                var asset = new ModelAssetEntity();
                asset.setUserId(task.getUserId());
                asset.setGenerationTaskId(taskId);
                boolean simulated=Boolean.TRUE.equals(task.getSimulated());
                asset.setName((simulated ? "模拟作品 " : "生成作品 ") + taskId);
                asset.setModelFileId(file.getId());
                asset.setFormat("GLB");
                asset.setSizeBytes(stored.sizeBytes());
                asset.setSimulated(simulated);
                assets.insert(asset);
                int completed = simulated ? tasks.succeed(taskId, clock.instant())
                        : "PYTHON_REALTIME".equals(task.getExecutionMode()) ? tasks.succeedRealtime(taskId, clock.instant())
                        : tasks.succeedReal(taskId, clock.instant());
                if (completed != 1) { throw new IllegalStateException("Task completion conflict"); }
                commitAttempted.set(true);
                return true;
            });
            if (!Boolean.TRUE.equals(published)) { fileService.cleanup(stored.key()); }
        } catch (RuntimeException exception) {
            fileService.cleanupAfterTransaction(stored.key(), commitAttempted.get());
            throw exception;
        }
    }
}
