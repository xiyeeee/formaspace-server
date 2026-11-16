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
    private final FileService fileService;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public GenerationResultPublisher(GenerationTaskMapper tasks, FileMapper files, ModelAssetMapper assets,
            FileStorage storage, FileStorageProperties limits, FileService fileService, Clock clock,
            PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.files = files;
        this.assets = assets;
        this.storage = storage;
        this.limits = limits;
        this.fileService = fileService;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
    }

    public void publish(long taskId, ModelInferenceClient client, String resultReference) throws IOException {
        FileStorage.StoredFile stored;
        try (var input = client.openResult(resultReference)) {
            stored = storage.save(input, "glb", limits.getMaxModelBytes());
        }
        var commitAttempted = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            Boolean published = transactions.execute(status -> {
                var task = tasks.lockTask(taskId);
                if (task == null || task.getStatus() != GenerationStatus.PROCESSING || !Boolean.TRUE.equals(task.getSimulated())) {
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
                asset.setName("模拟作品 " + taskId);
                asset.setModelFileId(file.getId());
                asset.setFormat("GLB");
                asset.setSizeBytes(stored.sizeBytes());
                asset.setSimulated(true);
                assets.insert(asset);
                if (tasks.succeed(taskId, clock.instant()) != 1) { throw new IllegalStateException("Task completion conflict"); }
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
