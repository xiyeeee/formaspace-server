package com.xiye.formaspace.file.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xiye.formaspace.common.enums.FilePurpose;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.file.entity.FileEntity;
import com.xiye.formaspace.file.mapper.FileMapper;
import com.xiye.formaspace.file.storage.FileStorage;
import com.xiye.formaspace.file.vo.FileUploadVO;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class FileService {
    private final FileMapper files;
    private final FileStorage storage;
    private final FileStorageProperties limits;
    private final SourceImageValidator validator;
    private final TransactionTemplate transactions;

    public FileService(FileMapper files, FileStorage storage, FileStorageProperties limits,
            SourceImageValidator validator, PlatformTransactionManager manager) {
        this.files = files;
        this.storage = storage;
        this.limits = limits;
        this.validator = validator;
        transactions = new TransactionTemplate(manager);
    }

    public FileUploadVO upload(long userId, MultipartFile upload) {
        var image = validator.validate(upload);
        FileStorage.StoredFile stored;
        try (var input = upload.getInputStream()) {
            stored = storage.save(input, image.extension(), limits.getMaxUploadBytes());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        }
        var commitAttempted = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            return transactions.execute(status -> {
                var file = new FileEntity();
                file.setUserId(userId);
                file.setOriginalName(image.originalName());
                file.setContentType(image.contentType());
                file.setStorageKey(stored.key());
                file.setSizeBytes(stored.sizeBytes());
                file.setPurpose(FilePurpose.SOURCE_IMAGE);
                files.insert(file);
                commitAttempted.set(true);
                return new FileUploadVO(file.getId().toString(), file.getOriginalName(),
                        file.getContentType(), file.getSizeBytes(), null);
            });
        } catch (RuntimeException exception) {
            cleanupAfterTransaction(stored.key(), commitAttempted.get());
            throw exception;
        }
    }

    public FileEntity requireOwned(long id, long userId, FilePurpose purpose) {
        var file = files.selectOne(Wrappers.<FileEntity>lambdaQuery().eq(FileEntity::getId, id)
                .eq(FileEntity::getUserId, userId).eq(FileEntity::getPurpose, purpose));
        if (file == null) { throw new BusinessException(ErrorCode.NOT_FOUND); }
        return file;
    }

    public FileStorage.StoredContent open(FileEntity file) {
        try {
            var content = storage.open(file.getStorageKey());
            if (content.sizeBytes() != file.getSizeBytes()) {
                content.close();
                throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
            }
            return content;
        } catch (java.nio.file.NoSuchFileException exception) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    public void cleanupAfterTransaction(String key, boolean commitMayHaveRun) {
        if (commitMayHaveRun) {
            try {
                if (files.selectCount(Wrappers.<FileEntity>lambdaQuery().eq(FileEntity::getStorageKey, key)) > 0) {
                    return;
                }
            } catch (RuntimeException exception) {
                // Commit outcome is unknown: retaining an orphan is safer than deleting a possibly committed model.
                log.error("Commit outcome unknown; retaining file for reconciliation: key={}, type={}",
                        key, exception.getClass().getSimpleName());
                return;
            }
        }
        cleanup(key);
    }

    public void cleanup(String key) {
        try { storage.delete(key); }
        catch (IOException exception) {
            // Opaque key identifies the orphan for operators; never include absolute paths or exception messages.
            log.error("Stored file cleanup failed: key={}, type={}", key, exception.getClass().getSimpleName());
        }
    }
}
