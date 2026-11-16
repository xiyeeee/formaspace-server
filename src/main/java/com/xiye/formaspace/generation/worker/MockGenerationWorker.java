package com.xiye.formaspace.generation.worker;

import com.xiye.formaspace.common.enums.FilePurpose;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import com.xiye.formaspace.generation.inference.ModelInferenceClient;
import com.xiye.formaspace.generation.inference.ModelInferenceClient.*;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.parameter.*;
import com.xiye.formaspace.generation.service.GenerationResultPublisher;
import java.io.IOException;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "formaspace.generation", name = "mode", havingValue = "MOCK")
public class MockGenerationWorker {
    private final GenerationTaskMapper tasks;
    private final GenerationProperties properties;
    private final GenerationSnapshots snapshots;
    private final FileService files;
    private final ModelInferenceClient client;
    private final GenerationResultPublisher publisher;
    private final Clock clock;

    // Fixed-delay execution is serial in this single-instance mock. All recovery state is in MySQL.
    @Scheduled(fixedDelayString = "${formaspace.generation.mock-poll-delay-ms:1000}",
            initialDelayString = "${formaspace.generation.mock-initial-delay-ms:1000}")
    public void tick() {
        try {
            tasks.activeMock(100).forEach(this::advance);
            int available = (int) Math.max(0, properties.getMockConcurrency() - tasks.countActiveMock());
            if (available > 0) {
                tasks.pendingMock(Math.min(available, 100)).forEach(task -> tasks.claimMock(task.getId(), clock.instant()));
            }
        } catch (RuntimeException exception) {
            // Storage/transaction outage leaves nonterminal work recoverable on the next tick/restart.
            log.error("Mock worker storage step failed: type={}", exception.getClass().getSimpleName());
        }
    }

    private void advance(GenerationTaskEntity task) {
        if (task.getUpdatedAt().plus(properties.getMockStageDelay()).isAfter(clock.instant())) { return; }
        try {
            if (task.getStatus() == GenerationStatus.SUBMITTING) {
                var file = files.requireOwned(task.getSourceFileId(), task.getUserId(), FilePurpose.SOURCE_IMAGE);
                var parameters = snapshots.decode(task.getResolvedParameters(), ResolvedGenerationParameters.class);
                client.submit(new Submission(task.getId(), parameters,
                        new SourceImage(file.getId(), file.getContentType(), () -> files.open(file).stream())));
                tasks.processing(task.getId(), clock.instant());
                return;
            }
            var result = client.query(task.getId());
            if (!result.completed()) { return; }
            if (result.failure() != null) { fail(task.getId(), result.failure()); return; }
            publisher.publish(task.getId(), client, result.resultReference());
        } catch (IOException exception) {
            fail(task.getId(), ErrorCode.STORAGE_UNAVAILABLE);
        } catch (BusinessException exception) {
            fail(task.getId(), exception.getErrorCode());
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Mock task failed: id={}, type={}", task.getId(), exception.getClass().getSimpleName());
            fail(task.getId(), ErrorCode.INTERNAL_ERROR);
        }
    }

    private void fail(long id, ErrorCode code) {
        tasks.fail(id, code.name(), code.getMessage(), clock.instant());
    }
}
