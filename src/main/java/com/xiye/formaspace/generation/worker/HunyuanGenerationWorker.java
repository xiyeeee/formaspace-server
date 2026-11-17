package com.xiye.formaspace.generation.worker;

import com.xiye.formaspace.common.enums.FilePurpose;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import com.xiye.formaspace.generation.inference.*;
import com.xiye.formaspace.generation.inference.ModelInferenceClient.*;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.parameter.*;
import com.xiye.formaspace.generation.service.GenerationResultPublisher;
import java.io.IOException;
import java.time.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="formaspace.generation", name="mode", havingValue="HUNYUAN_LOCAL")
public class HunyuanGenerationWorker {
    private final GenerationTaskMapper tasks;
    private final GenerationProperties properties;
    private final FileService files;
    private final GenerationSnapshots snapshots;
    private final HunyuanLocalInferenceClient client;
    private final GenerationResultPublisher publisher;
    private final ObjectMapper json;
    private final Clock clock;

    // Single application instance, serial scheduler, one remote shape pipeline at a time.
    @Scheduled(fixedDelayString="${formaspace.generation.hunyuan-local.poll-delay-ms:2000}")
    public synchronized void tick() {
        try {
            var active=tasks.activeReal();
            if (!active.isEmpty()) { advance(active.getFirst()); return; }
            for (var task:tasks.pendingReal()) {
                var now=clock.instant();
                if (tasks.claimReal(task.getId(),now,now.plus(properties.getHunyuanLocal().getTaskTimeout()))==1) {
                    task.setDeadlineAt(now.plus(properties.getHunyuanLocal().getTaskTimeout()));
                    submit(task);
                }
            }
        } catch (RuntimeException exception) {
            log.error("Hunyuan worker storage step failed: type={}",exception.getClass().getSimpleName());
        }
    }

    private void submit(GenerationTaskEntity task) {
        log.info("Hunyuan task submitting: taskId={}", task.getId());
        try {
            var file=files.requireOwned(task.getSourceFileId(),task.getUserId(),FilePurpose.SOURCE_IMAGE);
            var parameters=snapshots.decode(task.getResolvedParameters(),ResolvedGenerationParameters.class);
            var submitted=client.submit(new Submission(task.getId(),parameters,
                    new SourceImage(file.getId(),file.getContentType(),()->files.open(file).stream()),task.getDeadlineAt()));
            String eventId=json.readTree(submitted.remoteReference()).path("eventId").asString();
            try {
                tasks.submittedReal(task.getId(),submitted.remoteReference(),eventId,clock.instant());
                log.info("Hunyuan task accepted: taskId={}", task.getId());
            } catch (RuntimeException exception) {
                // Event ID is operational metadata, never sent to the browser. Do not re-submit on storage failure.
                log.error("Hunyuan accepted event but persistence failed: taskId={}, eventId={}, type={}",
                        task.getId(),eventId,exception.getClass().getSimpleName());
                throw exception;
            }
        } catch (InferenceClientException exception) { fail(task.getId(),exception.getErrorCode()); }
        catch (IOException exception) { fail(task.getId(),ErrorCode.SUBMISSION_UNCERTAIN); }
        catch (BusinessException exception) { fail(task.getId(),exception.getErrorCode()); }
    }

    private void advance(GenerationTaskEntity task) {
        var now=clock.instant();
        if (task.getStatus()==GenerationStatus.SUBMITTING) {
            // A previous process may have sent the POST. Absence of a durable event ID is never permission to resend.
            fail(task.getId(),ErrorCode.SUBMISSION_UNCERTAIN); return;
        }
        if (task.getDeadlineAt()==null || !task.getDeadlineAt().isAfter(now)) { fail(task.getId(),ErrorCode.INFERENCE_TIMEOUT); return; }
        if (task.getNextPollAt()!=null && task.getNextPollAt().isAfter(now)) { return; }
        try {
            String result=task.getRemoteResultReference();
            if (result==null) {
                var response=client.query(task.getRemoteReference());
                if (!response.completed()) { return; }
                if (response.failure()!=null) { fail(task.getId(),response.failure()); return; }
                result=response.resultReference();
                if (tasks.resultReadyReal(task.getId(),result,clock.instant())!=1) { return; }
                task.setQueryFailures(0);
            }
            publisher.publish(task.getId(),client,result);
        } catch (InferenceClientException exception) { fail(task.getId(),exception.getErrorCode()); }
        catch (IOException exception) {
            int failures=task.getQueryFailures()==null?0:task.getQueryFailures();
            if (failures>=properties.getHunyuanLocal().getMaximumRetries()) {
                log.warn("Hunyuan connection retries exhausted: taskId={}, phase=QUERY_OR_DOWNLOAD, retries={}, type={}",
                        task.getId(), failures, exception.getClass().getSimpleName());
                fail(task.getId(),ErrorCode.INFERENCE_UNAVAILABLE); return;
            }
            long seconds=properties.getHunyuanLocal().getRetryInterval().toSeconds()* (1L<<failures);
            tasks.retryReal(task.getId(),clock.instant().plusSeconds(Math.max(1,seconds)),clock.instant());
            log.warn("Hunyuan connection retry scheduled: taskId={}, phase=QUERY_OR_DOWNLOAD, attempt={}, delaySeconds={}, type={}",
                    task.getId(),failures+1,Math.max(1,seconds),exception.getClass().getSimpleName());
        } catch (BusinessException exception) { fail(task.getId(),exception.getErrorCode()); }
    }

    private void fail(long id,ErrorCode code) {
        tasks.failReal(id,code.name(),code.getMessage(),clock.instant());
        log.warn("Hunyuan task failed: taskId={}, code={}",id,code.name());
    }
}
