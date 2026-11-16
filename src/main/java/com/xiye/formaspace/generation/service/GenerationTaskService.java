package com.xiye.formaspace.generation.service;

import com.xiye.formaspace.common.enums.FilePurpose;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.common.response.PageResult;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.dto.*;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
import com.xiye.formaspace.generation.enums.*;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.parameter.*;
import com.xiye.formaspace.generation.vo.GenerationTaskVO;
import com.xiye.formaspace.user.mapper.UserMapper;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenerationTaskService {
    private final GenerationTaskMapper tasks;
    private final UserMapper users;
    private final FileService files;
    private final GenerationParameterResolver resolver;
    private final GenerationSnapshots snapshots;
    private final GenerationProperties properties;
    private final TransactionTemplate transactions;

    public GenerationTaskService(GenerationTaskMapper tasks, UserMapper users, FileService files,
            GenerationParameterResolver resolver, GenerationSnapshots snapshots, GenerationProperties properties,
            PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.users = users;
        this.files = files;
        this.resolver = resolver;
        this.snapshots = snapshots;
        this.properties = properties;
        transactions = new TransactionTemplate(manager);
    }

    public Creation create(long userId, String rawKey, CreateGenerationDTO request) {
        String key = canonicalKey(rawKey);
        var requested = resolver.requested(request);
        String requestedJson = snapshots.encode(requested);
        String fingerprint = snapshots.fingerprint(requestedJson);
        return transactions.execute(status -> {
            // One user row serializes both idempotency lookup and active-capacity decisions across keys.
            if (users.lockActiveIdentity(userId) == null) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
            var previous = tasks.findIdempotent(userId, key);
            if (previous != null) {
                if (!fingerprint.equals(previous.getRequestHash())) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                }
                return new Creation(toVO(tasks.findOwned(previous.getId(), userId)), false);
            }
            if (properties.getMode() == GenerationMode.DISABLED) {
                throw new BusinessException(ErrorCode.INFERENCE_UNAVAILABLE);
            }
            files.requireOwned(Long.parseLong(request.getSourceFileId()), userId, FilePurpose.SOURCE_IMAGE);
            var resolved = resolver.resolve(requested);
            if (tasks.countActive(userId) >= properties.getMaxActivePerUser()) {
                throw new BusinessException(ErrorCode.GENERATION_LIMIT_REACHED, 3L);
            }
            var task = new GenerationTaskEntity();
            task.setUserId(userId);
            task.setSourceFileId(Long.parseLong(request.getSourceFileId()));
            task.setProvider("HUNYUAN_LOCAL");
            task.setStatus(GenerationStatus.PENDING);
            task.setSimulated(true);
            task.setIdempotencyKey(key);
            task.setRequestHash(fingerprint);
            task.setRequestedParameters(requestedJson);
            task.setResolvedParameters(snapshots.encode(resolved));
            task.setParameterSchemaVersion(resolved.schemaVersion());
            task.setProfileId(resolved.profileId());
            tasks.insert(task);
            return new Creation(toVO(task), true);
        });
    }

    public GenerationTaskVO detail(long id, long userId) {
        var task = tasks.findOwned(id, userId);
        if (task == null) { throw new BusinessException(ErrorCode.NOT_FOUND); }
        return toVO(task);
    }

    public PageResult<GenerationTaskVO> page(long userId, GenerationQueryDTO query) {
        return PageResult.from(tasks.pageOwned(query.toPage(), userId, query.getStatus()), this::toVO);
    }

    private GenerationTaskVO toVO(GenerationTaskEntity task) {
        return new GenerationTaskVO(task.getId().toString(), task.getSourceFileId().toString(), task.getStatus(),
                task.getProgress(), Boolean.TRUE.equals(task.getSimulated()),
                snapshots.decode(task.getRequestedParameters(), RequestedGenerationParameters.class),
                snapshots.decode(task.getResolvedParameters(), ResolvedGenerationParameters.class),
                task.getAssetId() == null ? null : task.getAssetId().toString(), task.getErrorCode(), task.getErrorMessage(),
                task.getCreatedAt(), task.getStartedAt(), task.getFinishedAt());
    }

    private String canonicalKey(String value) {
        try {
            if (value != null && UUID.fromString(value).toString().equalsIgnoreCase(value)) {
                return value.toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) { }
        throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
    }

    public record Creation(GenerationTaskVO task, boolean created) {}
}
