package com.xiye.formaspace.generation.realtime;

import com.xiye.formaspace.common.enums.FilePurpose;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.generation.config.*;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import com.xiye.formaspace.generation.inference.*;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.service.GenerationResultPublisher;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.http.WebSocket;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@ConditionalOnProperty(prefix="formaspace.generation",name="mode",havingValue="PYTHON_REALTIME")
public class PythonRealtimeWorker {
    private final GenerationTaskMapper tasks;
    private final PythonRealtimeProperties config;
    private final PythonRealtimeClient client;
    private final PythonRealtimeProtocol protocol;
    private final GenerationPreviewService previews;
    private final GenerationResultPublisher results;
    private final FileService files;
    private final GenerationEventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean tickQueued=new AtomicBoolean();
    private final Map<Long,Connection> connections=new HashMap<>();
    private volatile boolean stopped;

    public PythonRealtimeWorker(GenerationTaskMapper tasks,GenerationProperties properties,PythonRealtimeClient client,
            PythonRealtimeProtocol protocol,GenerationPreviewService previews,GenerationResultPublisher results,
            FileService files,GenerationEventPublisher events,ObjectMapper json,Clock clock) {
        this.tasks=tasks;config=properties.getPythonRealtime();this.client=client;this.protocol=protocol;
        this.previews=previews;this.results=results;this.files=files;this.events=events;this.json=json;this.clock=clock;
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(config.getEventQueueCapacity()),
                runnable->{var thread=new Thread(runnable,"python-task-events");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Scheduled(fixedDelayString="${formaspace.generation.python-realtime.poll-delay-ms:1000}",
            initialDelayString="${formaspace.generation.python-realtime.initial-delay-ms:0}")
    public void tick() {
        if (stopped || !tickQueued.compareAndSet(false,true)) return;
        try { executor.execute(()->{try { reconcile(); } finally { tickQueued.set(false); }}); }
        catch (RejectedExecutionException exception) { tickQueued.set(false); }
    }
    // HTTP, storage and database operations run here, never in the scheduler, Servlet, or WebSocket callback threads.
    private void reconcile() {
        try {
            var active=tasks.realtimeTasks(config.getMaximumConnections());
            Set<Long> ids=new HashSet<>();active.forEach(task->ids.add(task.getId()));
            for (long id:List.copyOf(connections.keySet())) if (!ids.contains(id)) disconnect(id);
            for (var task:active) {
                try { reconcile(task); }
                catch (Exception exception) {
                    log.warn("Python task reconciliation deferred: taskId={}, type={}",task.getId(),exception.getClass().getSimpleName());
                }
            }
            for (var task:tasks.realtimePreviewRepairs(client.serviceKey(),clock.instant(),config.getMaximumConnections())) repairPreview(task);
        } catch (RuntimeException exception) { log.error("Python task scheduler unavailable: type={}",exception.getClass().getSimpleName()); }
    }
    private void reconcile(GenerationTaskEntity task) throws IOException {
        if (!client.serviceKey().equals(task.getInferenceService())) {
            disconnect(task.getId());task.setConnectionState("SERVICE_CHANGED");save(task);return;
        }
        if (task.getNextPollAt()!=null && task.getNextPollAt().isAfter(clock.instant())) return;
        if (task.getRemoteResultReference()!=null) { publish(task);return; }
        if (task.getRemoteResultReference()==null && (task.getDeadlineAt()==null || !task.getDeadlineAt().isAfter(clock.instant()))) {
            fail(task,ErrorCode.INFERENCE_STATE_UNCERTAIN);return;
        }
        if (task.getNextPollAt()!=null && task.getNextPollAt().isAfter(clock.instant())) return;
        Connection connection=connections.get(task.getId());
        if (connection!=null && (connection.disconnected.get() || (connection.socket!=null
                && (connection.socket.isInputClosed() || connection.socket.isOutputClosed())))) {
            disconnect(task.getId());retry(task,0);return;
        }
        if (connection!=null && !connection.firstSnapshot && connection.opened.plus(config.getSnapshotTimeout()).isBefore(clock.instant())) {
            disconnect(task.getId());retry(task,0);return;
        }
        if (connection!=null && connection.checked.plus(config.getReconciliationInterval()).isAfter(clock.instant())) return;
        try {
            var node=client.snapshot(task.getInferenceTaskId());
            var state=protocol.snapshot(node,task.getInferenceTaskId(),task.getTotalSteps());
            apply(task,state,null);
            task=tasks.selectById(task.getId());
            if (terminal(task)) { disconnect(task.getId());return; }
            if (connection==null) connect(task);
            else {
                connection.checked=clock.instant();
                var current=connection;
                connection.socket.sendPing(java.nio.ByteBuffer.wrap(new byte[]{1}))
                        .orTimeout(config.getSnapshotTimeout().toMillis(),TimeUnit.MILLISECONDS)
                        .whenComplete((socket,error)->{if(error!=null)current.disconnected.set(true);});
            }
        } catch (PythonRealtimeClient.RemoteHttpException exception) {
            if (exception.status()==404) {
                if ("ACCEPTED".equals(task.getSubmissionState())) fail(task,ErrorCode.INFERENCE_REFERENCE_LOST);
                else submit(task);
            } else httpFailure(task,exception,false);
        } catch (IOException exception) { disconnect(task.getId());retry(task,0); }
    }
    private void submit(GenerationTaskEntity task) throws IOException {
        if (task.getSubmissionAttempts()>=config.getMaximumSubmissionAttempts()) {
            fail(task,"DEFERRED".equals(task.getSubmissionState()) ? ErrorCode.INFERENCE_UNAVAILABLE : ErrorCode.SUBMISSION_UNCERTAIN);return;
        }
        // Write UNKNOWN and the attempt before any network I/O. Every replay keeps the original UUID and JSON bytes.
        task.setSubmissionState("UNKNOWN");task.setStatus(GenerationStatus.SUBMITTING);
        task.setSubmissionAttempts(task.getSubmissionAttempts()+1);task.setStartedAt(clock.instant());
        if (!save(task)) return;
        try {
            var file=files.requireOwned(task.getSourceFileId(),task.getUserId(),FilePurpose.SOURCE_IMAGE);
            client.submitTask(task.getInferenceTaskId(),task.getPythonParameters(),new ModelInferenceClient.SourceImage(
                    file.getId(),file.getContentType(),()->files.open(file).stream()));
            task.setSubmissionState("ACCEPTED");task.setStatus(GenerationStatus.PROCESSING);task.setQueryFailures(0);
            task.setConnectionState("RECONCILING");task.setNextPollAt(clock.instant());save(task);
            log.info("Python task accepted: taskId={}",task.getId());
        } catch (PythonRealtimeClient.RemoteHttpException exception) { httpFailure(task,exception,true); }
        catch (IOException exception) { retry(task,0); }
        catch (BusinessException exception) { fail(task,exception.getErrorCode()); }
    }
    private void httpFailure(GenerationTaskEntity task,PythonRealtimeClient.RemoteHttpException exception,boolean submitting) {
        if (exception.status()==401 || exception.status()==403) { fail(task,ErrorCode.INFERENCE_AUTH_FAILED);return; }
        if (submitting && exception.status()==409) { fail(task,ErrorCode.INFERENCE_SUBMISSION_CONFLICT);return; }
        if (submitting && exception.status()==429) task.setSubmissionState("DEFERRED");
        if ((exception.status()>=400 && exception.status()<500 && exception.status()!=409 && exception.status()!=429)
                || (exception.status()>=300 && exception.status()<400)) {
            fail(task,ErrorCode.INFERENCE_PROTOCOL_ERROR);return;
        }
        retry(task,exception.retryAfter());
    }
    private void connect(GenerationTaskEntity task) throws IOException {
        var connection=new Connection(clock.instant());
        connections.put(task.getId(),connection);
        var listener=new PythonSocketListener(config.getMaximumMessageBytes(),(socket,text)-> {
            if (stopped || connection.disconnected.get()) return false;
            try {
                executor.execute(()-> {
                    try { handle(task.getId(),connection,text); }
                    catch (Exception exception) {
                        connection.disconnected.set(true);socket.abort();
                        log.warn("Python event stream needs snapshot: taskId={}, type={}",task.getId(),exception.getClass().getSimpleName());
                    } finally { if (!connection.disconnected.get()) socket.request(1); }
                });return true;
            } catch (RejectedExecutionException exception) { return false; }
        },connection.disconnected);
        try {
            connection.socket=client.connect(task.getInferenceTaskId(),listener);
            task.setConnectionState("CONNECTING");save(task);
        } catch (IOException exception) { connection.disconnected.set(true);disconnect(task.getId());throw exception; }
    }
    private void handle(long id,Connection connection,String text) throws IOException {
        if (connections.get(id)!=connection || connection.disconnected.get()) return;
        var task=tasks.selectById(id);
        if (terminal(task)) { disconnect(id);return; }
        var event=protocol.event(json.readTree(text),task.getInferenceTaskId(),task.getTotalSteps());
        boolean snapshot=event.type().equals("task_snapshot");
        PythonRealtimeProtocol.require(connection.firstSnapshot || snapshot);
        if (snapshot) {
            PythonRealtimeProtocol.require(event.state().sequence()>=task.getLastPythonSequence());
            connection.firstSnapshot=true;
        } else {
            if (event.state().sequence()<=task.getLastPythonSequence()) return;
            PythonRealtimeProtocol.require(event.state().sequence()==task.getLastPythonSequence()+1);
        }
        if (event.type().equals("preview_failed"))
            PythonRealtimeProtocol.require(event.state().previewVersion()==task.getPythonPreviewVersion());
        apply(task,event.state(),event);
        connection.checked=clock.instant();
    }
    private void apply(GenerationTaskEntity task,PythonRealtimeProtocol.State state,PythonRealtimeProtocol.Event event) throws IOException {
        if (terminal(task) || state.sequence()<task.getLastPythonSequence()) return;
        PythonRealtimeProtocol.require(state.completedSteps()>=task.getCompletedSteps() && state.previewVersion()>=task.getPythonPreviewVersion());
        task.setLastPythonSequence(state.sequence());task.setCompletedSteps(state.completedSteps());task.setTotalSteps(state.totalSteps());
        task.setPythonPreviewVersion(state.previewVersion());task.setInferenceStage(state.stage());
        task.setStatus(GenerationStatus.PROCESSING);task.setSubmissionState("ACCEPTED");task.setConnectionState("CONNECTED");
        if (event!=null) task.setQueryFailures(0);
        task.setNextPollAt(null);
        if (event!=null && event.type().equals("preview_failed")) task.setPreviewErrorCode("PREVIEW_DECODE_FAILED");
        if (state.status().equals("completed")) { task.setRemoteResultReference(task.getInferenceTaskId());task.setInferenceStage("saving_result"); }
        if (!save(task)) return;
        // Recover every retained version, including intermediate versions missed while disconnected.
        if (state.previewVersion()>task.getStoredPreviewVersion()) {
            try {
                storePreviews(task,state.previewVersion(),state.completedSteps(),state.sequence(),event);
            } catch (IOException | RuntimeException exception) {
                log.warn("Python preview save deferred: taskId={}, version={}, type={}",task.getId(),state.previewVersion(),exception.getClass().getSimpleName());
            }
        }
        if (state.status().equals("failed")) {
            String code=state.error().path("code").asString();
            fail(task,code.equals("SERVICE_RESTARTED") ? ErrorCode.SERVICE_RESTARTED : ErrorCode.INFERENCE_FAILED);return;
        }
        if (state.status().equals("completed")) {
            events.publish(task.getId(),"result_saving",state.sequence());
            publish(task);return;
        }
        if (event!=null) {
            if(event.type().equals("preview_failed"))events.publish(task.getId(),event.type(),state.sequence(),event.payload().path("fatal").asBoolean());
            else events.publish(task.getId(),event.type(),state.sequence());
        } else events.publish(task.getId(),"task_snapshot",state.sequence());
    }
    private void publish(GenerationTaskEntity task) {
        events.publish(task.getId(),"result_saving",task.getLastPythonSequence());
        try {
            results.publish(task.getId(),client,task.getInferenceTaskId());
            var saved=tasks.selectById(task.getId());
            if (saved!=null && saved.getStatus()==GenerationStatus.SUCCEEDED) {
                disconnect(task.getId());events.publish(task.getId(),"task_completed",task.getLastPythonSequence());
            }
        } catch (InferenceClientException exception) { fail(task,exception.getErrorCode()); }
        catch (PythonRealtimeClient.RemoteHttpException exception) {
            if (exception.status()==404) fail(task,ErrorCode.INFERENCE_REFERENCE_LOST);
            else httpFailure(task,exception,false); // 409 result-not-ready is reconciliation, never a second submission.
        } catch (IOException | RuntimeException exception) {
            retry(task,0);
            log.warn("Python result publication deferred: taskId={}, type={}",task.getId(),exception.getClass().getSimpleName());
        }
    }
    private void storePreviews(GenerationTaskEntity task,int latestVersion,int completedSteps,long sourceSequence,
            PythonRealtimeProtocol.Event event) throws IOException {
        PythonTaskParameters parameters=json.readValue(task.getPythonParameters(),PythonTaskParameters.class);
        int maximumVersions=parameters.previewStages()==null?1:parameters.previewStages().size();
        PythonRealtimeProtocol.require(latestVersion<=maximumVersions);
        for(int version=1;version<=latestVersion;version++) {
            if(previews.latest(task.getId(),version)!=null)continue;
            PythonRealtimeProtocol.PointCloud cloud=event!=null && event.type().equals("preview_ready") && version==latestVersion
                    ? protocol.points(event.payload(),task.getInferenceTaskId(),version,task.getTotalSteps(),true)
                    : protocol.points(client.preview(task.getInferenceTaskId(),version),task.getInferenceTaskId(),version,task.getTotalSteps(),false);
            parameters.validatePreview(cloud);
            PythonRealtimeProtocol.require(cloud.sourceStep()<=completedSteps);
            GenerationPreviewService.PreviewMetadata previous=previews.latest(task.getId(),version-1);
            PythonRealtimeProtocol.require(previous==null || previous.sourceStep()<cloud.sourceStep());
            previews.save(task.getId(),cloud);task.setStoredPreviewVersion(cloud.previewVersion());
            if(event!=null && event.type().equals("preview_ready") && version==latestVersion
                    && event.payload().path("decodeSeconds").isNumber())
                previews.recordDecodeSeconds(task.getId(),version,event.payload().path("decodeSeconds").asDouble());
            events.publish(task.getId(),"preview_ready",sourceSequence);
        }
    }
    private void repairPreview(GenerationTaskEntity task) {
        try {
            storePreviews(task,task.getPythonPreviewVersion(),task.getCompletedSteps(),task.getLastPythonSequence(),null);
        } catch (IOException | RuntimeException exception) {
            int failures=Math.min(20,task.getQueryFailures()==null?0:task.getQueryFailures());
            long delay=Math.min(config.getReconnectMaxDelay().toMillis(),config.getReconnectInitialDelay().toMillis()*(1L<<failures));
            tasks.retryRealtimePreview(task.getId(),clock.instant().plusMillis(delay),clock.instant());
            log.warn("Python terminal preview recovery deferred: taskId={}, version={}, type={}",
                    task.getId(),task.getPythonPreviewVersion(),exception.getClass().getSimpleName());
        }
    }
    private void retry(GenerationTaskEntity task,long minimumSeconds) {
        disconnect(task.getId());
        int failures=Math.min(20,task.getQueryFailures()==null?0:task.getQueryFailures());
        long cap=config.getReconnectMaxDelay().toMillis();
        long base=Math.min(cap,config.getReconnectInitialDelay().toMillis()*(1L<<failures));
        long delay=Math.max(minimumSeconds*1000,Math.min(cap,base+ThreadLocalRandom.current().nextLong(Math.max(1,base/4))));
        task.setQueryFailures(failures+1);task.setNextPollAt(clock.instant().plusMillis(delay));task.setConnectionState("RECONNECTING");
        save(task);
        log.warn("Python connection reconciliation scheduled: taskId={}, attempt={}, delayMs={}",task.getId(),failures+1,delay);
    }
    private boolean save(GenerationTaskEntity task) {
        task.setUpdatedAt(clock.instant());
        boolean saved=tasks.saveRealtime(task)==1;
        if(saved)events.publish(task.getId(),"connection_status",task.getLastPythonSequence());
        return saved;
    }
    private boolean terminal(GenerationTaskEntity task) { return task==null || task.getStatus()==GenerationStatus.SUCCEEDED || task.getStatus()==GenerationStatus.FAILED; }
    private void fail(GenerationTaskEntity task,ErrorCode code) {
        if (tasks.failRealtime(task.getId(),code.name(),code.getMessage(),clock.instant())==1) events.publish(task.getId(),"task_failed",task.getLastPythonSequence());
        disconnect(task.getId());log.warn("Python task failed: taskId={}, code={}",task.getId(),code.name());
    }
    private void disconnect(long id) {
        var connection=connections.remove(id);
        if (connection!=null) { connection.disconnected.set(true);if (connection.socket!=null) connection.socket.abort(); }
    }
    @PreDestroy public void close() {
        stopped=true;executor.shutdown();
        try { if (!executor.awaitTermination(3,TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt();executor.shutdownNow(); }
        connections.values().forEach(connection->{connection.disconnected.set(true);if (connection.socket!=null) connection.socket.abort();});
        connections.clear();
    }
    private static class Connection {
        private WebSocket socket;
        private boolean firstSnapshot;
        private final Instant opened;
        private Instant checked;
        private final AtomicBoolean disconnected=new AtomicBoolean();
        private Connection(Instant now) { opened=now;checked=now; }
    }
}
