package com.xiye.formaspace.generation.browser;

import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.common.exception.BusinessException;
import com.xiye.formaspace.generation.browser.BrowserEventStore.*;
import com.xiye.formaspace.generation.browser.WsHandshake.WsPrincipal;
import com.xiye.formaspace.generation.realtime.*;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.*;

@lombok.extern.slf4j.Slf4j
@Component
public class GenerationWebSocketHandler extends TextWebSocketHandler {
    private final BrowserEventStore store;private final GenerationPreviewService previews;private final UserMapper users;
    private final ObjectMapper json;private final Clock clock;
    private final Object subscriptionsLock=new Object();
    private final ConcurrentMap<String,Peer> peers=new ConcurrentHashMap<>();
    private final ScheduledExecutorService maintenance=Executors.newScheduledThreadPool(2,runnable->{Thread thread=new Thread(runnable,"browser-ws-maintenance");thread.setDaemon(true);return thread;});
    public GenerationWebSocketHandler(BrowserEventStore store,GenerationPreviewService previews,UserMapper users,ObjectMapper json,Clock clock){
        this.store=store;this.previews=previews;this.users=users;this.json=json;this.clock=clock;
        maintenance.scheduleWithFixedDelay(this::checkPeers,10,10,TimeUnit.SECONDS);
    }
    @Override public void afterConnectionEstablished(WebSocketSession session)throws Exception{
        session.setTextMessageSizeLimit(8192);session.setBinaryMessageSizeLimit(8192);
        if(session instanceof org.springframework.web.socket.adapter.NativeWebSocketSession nativeSession){
            jakarta.websocket.Session nativeSocket=nativeSession.getNativeSession(jakarta.websocket.Session.class);
            if(nativeSocket!=null){nativeSocket.setMaxIdleTimeout(90000L);nativeSocket.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT",5000L);}
        }
        if(!(session.getPrincipal() instanceof WsPrincipal principal)){session.close(new CloseStatus(4001));return;}
        synchronized(peers){
            if(peers.size()>=64 || peers.values().stream().filter(peer->peer.principal.userId()==principal.userId()).count()>=4){session.close(new CloseStatus(1013));return;}
            Peer peer=new Peer(session,principal);peers.put(session.getId(),peer);
            peer.expiry=maintenance.schedule(()->remove(peer,4001),Math.max(0,Duration.between(clock.instant(),principal.jwtExpiry()).toMillis()),TimeUnit.MILLISECONDS);
        }
    }
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){
        Peer peer=peers.get(session.getId());if(peer==null)return;
        int authenticationClose=authenticationClose(peer);if(authenticationClose!=0){remove(peer,authenticationClose);return;}
        peer.lastActivity=clock.instant();
        long second=clock.instant().getEpochSecond();
        synchronized(peer){if(peer.rateSecond!=second){peer.rateSecond=second;peer.messages=0;}if(++peer.messages>20){error(peer,null,"RATE_LIMITED","消息过于频繁");if(++peer.invalid>=3)remove(peer,1008);return;}}
        if(message.getPayloadLength()>8192){remove(peer,1009);return;}
        try{
            JsonNode node=json.readTree(message.getPayload());
            if(node==null || !node.isObject())throw new IllegalArgumentException();
            if(!node.path("protocolVersion").isIntegralNumber() || !node.path("protocolVersion").canConvertToLong() || node.path("protocolVersion").asLong()!=1){error(peer,null,"UNSUPPORTED_PROTOCOL","不支持的协议版本");return;}
            String type=node.path("type").asString();
            if(type.equals("ping")){
                String requestId=node.path("requestId").asString();if(requestId.length()>128 || requestId.isBlank())throw new IllegalArgumentException();
                peer.offer(json.writeValueAsString(Map.of("protocolVersion",1,"type","pong","requestId",requestId)),null,peer.epoch);return;
            }
            String raw=node.path("taskId").asString();if(!node.path("taskId").isTextual() || !raw.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
            long id=Long.parseLong(raw);
            if(type.equals("unsubscribe")){synchronized(subscriptionsLock){synchronized(peer){if(Objects.equals(peer.taskId,id)){peer.taskId=null;peer.epoch++;peer.sender.getQueue().clear();}}}return;}
            if(!type.equals("subscribe"))throw new IllegalArgumentException();
            if(node.has("pointBatches") && !node.path("pointBatches").isBoolean())throw new IllegalArgumentException();
            synchronized(store.lock(id)){
                Frame snapshot=store.snapshot(id,peer.principal.userId());
                synchronized(subscriptionsLock){
                    if(peer.taskId==null && peers.values().stream().filter(value->value.taskId!=null).count()>=32){error(peer,raw,"SUBSCRIPTION_LIMIT","订阅容量已满，请稍后重试");return;}
                    synchronized(peer){peer.epoch++;peer.taskId=id;peer.pointBatches=node.path("pointBatches").asBoolean(false);peer.sequence=snapshot.sequence();peer.connectionState=snapshot.view().connectionState();peer.sender.getQueue().clear();peer.offer(encode(snapshot),id,peer.epoch);}
                }
            }
        }catch(BusinessException exception){error(peer,null,"TASK_NOT_FOUND","任务不存在或不可访问");}
        catch(org.springframework.dao.DataAccessException exception){remove(peer,1013);}
        catch(Exception exception){error(peer,null,"INVALID_MESSAGE","消息格式无效");if(++peer.invalid>=3)remove(peer,1008);}
    }
    @Override protected void handleBinaryMessage(WebSocketSession session,BinaryMessage message){Peer peer=peers.get(session.getId());if(peer!=null)remove(peer,1008);}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){Peer peer=peers.remove(session.getId());if(peer!=null)peer.stop();}
    @Override public void handleTransportError(WebSocketSession session,Throwable exception){Peer peer=peers.get(session.getId());if(peer!=null)remove(peer,1013);}
    @EventListener public void onGenerationEvent(GenerationEventPublisher.GenerationEvent event){
        try { synchronized(store.lock(event.taskId())){
            Frame frame=store.record(event.taskId(),event.type(),event.sequence());if(frame==null)return;
            if(event.fatal())frame=new Frame(frame.view(),frame.sequence(),frame.type(),frame.timestamp(),true);
            String message;
            try{message=encode(frame);}catch(Exception exception){return;}
            List<String> batches=frame.type().equals("preview_ready") && peers.values().stream().anyMatch(peer->peer.pointBatches && Objects.equals(peer.taskId,event.taskId()))
                    ? pointBatchFrames(frame,message) : null;
            for(Peer peer:peers.values())synchronized(peer){
                if(!Objects.equals(peer.taskId,event.taskId()) || peer.principal.userId()!=frame.view().userId())continue;
                if(frame.type().equals("connection_status")){
                    if(Objects.equals(peer.connectionState,frame.view().connectionState()))continue;
                    peer.connectionState=frame.view().connectionState();peer.offer(message,peer.taskId,peer.epoch);
                }else if(frame.sequence()>peer.sequence){peer.sequence=frame.sequence();
                    if(peer.pointBatches && batches!=null)peer.offer(batches,peer.taskId,peer.epoch);
                    else peer.offer(message,peer.taskId,peer.epoch);
                }
            }
        }} catch(RuntimeException exception){log.warn("Browser event deferred: taskId={}, type={}",event.taskId(),exception.getClass().getSimpleName());}
    }
    private List<String> pointBatchFrames(Frame frame,String message){
        var cloud=previews.read(frame.view().id(),frame.view().latestPreview().version(),frame.view().userId());
        var envelope=(tools.jackson.databind.node.ObjectNode)json.readTree(message);
        var payload=(tools.jackson.databind.node.ObjectNode)envelope.path("payload");
        payload.remove("positions");payload.put("streamed",true);payload.put("batchSize",PointCloudPrintPlan.BATCH_SIZE);
        payload.set("bounds",json.valueToTree(PointCloudPrintPlan.bounds(cloud.positions())));
        List<String> frames=new ArrayList<>();frames.add(json.writeValueAsString(envelope));
        for(var batch:PointCloudPrintPlan.batches(cloud.positions())){
            frames.add(json.writeValueAsString(Map.of("protocolVersion",1,"type","point_batch","taskId",cloud.taskId(),
                    "baseSequence",frame.sequence(),"previewVersion",cloud.previewVersion(),
                    "payload",Map.of("sourceStep",cloud.sourceStep(),"totalPointCount",cloud.pointCount(),
                            "offset",batch.offset(),"pointCount",batch.positions().size(),"positions",batch.positions()))));
        }
        return List.copyOf(frames);
    }
    private String encode(Frame frame){
        View view=frame.view();Map<String,Object> envelope=new LinkedHashMap<>();
        envelope.put("protocolVersion",1);envelope.put("taskId",Long.toString(view.id()));envelope.put("type",frame.type());
        if(frame.type().equals("connection_status")){envelope.put("payload",Map.of("connectionState",view.connectionState()));return json.writeValueAsString(envelope);}
        envelope.put("sequence",frame.sequence());
        String eventStage=switch(frame.type()){
            case "task_queued"->"queued";case "task_started"->"preprocessing";case "sampling_step","preview_ready","preview_failed"->"sampling";
            case "preview_started"->"preview_decoding";case "finalizing"->"final_decoding";case "result_saving"->"result_saving";
            case "task_completed"->"completed";case "task_failed"->"failed";default->view.stage();
        };
        envelope.put("stage",eventStage);envelope.put("completedSteps",view.completedSteps());
        envelope.put("totalSteps",view.totalSteps());envelope.put("previewVersion",view.latestPreview()==null?0:view.latestPreview().version());envelope.put("timestamp",frame.timestamp());
        Map<String,Object> payload=new LinkedHashMap<>();
        switch(frame.type()){
            case "task_snapshot"->{payload.put("status",view.status());payload.put("simulated",view.simulated());payload.put("connectionState",view.connectionState());payload.put("latestPreview",view.latestPreview());payload.put("assetId",view.assetId());payload.put("error",view.error());}
            case "preview_ready"->{
                GenerationPreviewService.PreviewMetadata metadata=view.latestPreview();
                GenerationPreviewService.PreviewVO cloud=previews.read(view.id(),metadata.version(),view.userId());
                payload.put("sourceStep",cloud.sourceStep());payload.put("pointCount",cloud.pointCount());payload.put("positions",cloud.positions());payload.put("positionsPath",metadata.positionsPath());payload.put("decodeSeconds",previews.decodeSeconds(view.id(),metadata.version()));
            }
            case "preview_failed"->{payload.put("code","PREVIEW_DECODE_FAILED");payload.put("message","本次中间预览生成失败");payload.put("fatal",frame.fatal());}
            case "task_completed"->{payload.put("status","SUCCEEDED");payload.put("assetId",view.assetId());}
            case "task_failed"->{payload.put("status","FAILED");payload.put("code",view.error()==null?"INFERENCE_FAILED":view.error().get("code"));payload.put("message",view.error()==null?"生成失败":view.error().get("message"));}
            default->{}
        }
        envelope.put("payload",payload);return json.writeValueAsString(envelope);
    }
    private void error(Peer peer,String taskId,String code,String message){Map<String,Object> frame=new LinkedHashMap<>();frame.put("protocolVersion",1);frame.put("type","error");frame.put("code",code);frame.put("message",message);if(taskId!=null)frame.put("taskId",taskId);peer.offer(json.writeValueAsString(frame),null,peer.epoch);}
    private int authenticationClose(Peer peer){
        if(!peer.principal.jwtExpiry().isAfter(clock.instant()))return 4001;
        try{UserEntity user=users.selectById(peer.principal.userId());return user!=null && user.getStatus()==UserStatus.ACTIVE?0:4001;}
        catch(RuntimeException exception){return 1013;}
    }
    private void checkPeers(){for(Peer peer:peers.values()){
        int closeCode=authenticationClose(peer);
        if(closeCode!=0)remove(peer,closeCode);else if(peer.lastActivity.plusSeconds(90).isBefore(clock.instant()))remove(peer,1000);
    }}
    private void remove(Peer peer,int code){if(!peers.remove(peer.session.getId(),peer))return;peer.stop();try{peer.session.close(new CloseStatus(code));}catch(IOException ignored){}}
    @PreDestroy public void shutdown(){for(Peer peer:peers.values())remove(peer,1000);maintenance.shutdownNow();}
    private final class Peer {
        private final WebSocketSession session;private final WsPrincipal principal;
        private final ThreadPoolExecutor sender;private final AtomicBoolean closing=new AtomicBoolean();
        private volatile Long taskId;private volatile long epoch,sequence;private volatile Instant lastActivity;
        private volatile String connectionState;private volatile boolean pointBatches;private ScheduledFuture<?> expiry;private long rateSecond;private int messages,invalid;
        private Peer(WebSocketSession session,WsPrincipal principal){this.session=session;this.principal=principal;lastActivity=clock.instant();
            sender=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),runnable->{Thread thread=new Thread(runnable,"browser-ws-send");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());}
        private void offer(String text,Long task,long subscriptionEpoch){
            offer(List.of(text),task,subscriptionEpoch);
        }
        private void offer(List<String> frames,Long task,long subscriptionEpoch){
            if(closing.get())return;
            // One bounded sender job per preview; never enqueue 100 independent jobs or sleep the inference worker.
            try{sender.execute(()->{for(String text:frames){
                if(closing.get())return;if(!principal.jwtExpiry().isAfter(clock.instant())){remove(this,4001);return;}
                if(task!=null && (!Objects.equals(taskId,task)||epoch!=subscriptionEpoch))return;
                try{session.sendMessage(new TextMessage(text));}catch(Exception exception){remove(this,1013);return;}
            }
            });}catch(RejectedExecutionException exception){if(closing.compareAndSet(false,true))maintenance.execute(()->remove(this,1013));}
        }
        private void stop(){closing.set(true);if(expiry!=null)expiry.cancel(false);sender.shutdownNow();taskId=null;}
    }
}
