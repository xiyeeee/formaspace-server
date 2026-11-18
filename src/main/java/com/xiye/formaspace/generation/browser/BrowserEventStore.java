package com.xiye.formaspace.generation.browser;

import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import com.xiye.formaspace.generation.realtime.GenerationPreviewService;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class BrowserEventStore {
    private final GenerationTaskMapper tasks;private final BrowserStateMapper states;
    private final GenerationPreviewService previews;private final ObjectMapper json;private final Clock clock;
    private final TransactionTemplate transactions;
    private final Object[] locks=new Object[256];
    public BrowserEventStore(GenerationTaskMapper tasks,BrowserStateMapper states,GenerationPreviewService previews,ObjectMapper json,Clock clock,PlatformTransactionManager manager){
        this.tasks=tasks;this.states=states;this.previews=previews;this.json=json;this.clock=clock;transactions=new TransactionTemplate(manager);
        Arrays.setAll(locks,index->new Object());
    }
    public Object lock(long taskId){return locks[(int)(taskId&255)];}
    public View owned(long id,long userId){
        GenerationTaskEntity task=tasks.findOwned(id,userId);if(task==null)throw new BusinessException(ErrorCode.NOT_FOUND);
        return view(task);
    }
    private View view(GenerationTaskEntity task){
        GenerationTaskEntity joined=tasks.findOwned(task.getId(),task.getUserId());
        GenerationPreviewService.PreviewMetadata preview=previews.latest(task.getId(),task.getStoredPreviewVersion());
        Map<String,String> error=task.getErrorCode()==null?null:Map.of("code",task.getErrorCode(),"message",task.getErrorMessage()==null?"生成失败":task.getErrorMessage());
        String stage=task.getStatus()==GenerationStatus.SUCCEEDED?"completed":task.getStatus()==GenerationStatus.FAILED?"failed":task.getInferenceStage();
        if("saving_result".equals(stage))stage="result_saving";
        if(stage==null)stage="queued";
        String state="CONNECTED".equals(task.getConnectionState())?"CONNECTED":Set.of("RECONNECTING","CONNECTING","RECONCILING").contains(Objects.toString(task.getConnectionState(),""))?"RECONNECTING":"DISCONNECTED";
        return new View(task.getId(),task.getUserId(),task.getStatus().name(),Boolean.TRUE.equals(task.getSimulated()),stage,
                task.getCompletedSteps()==null?0:task.getCompletedSteps(),task.getTotalSteps()==null?0:task.getTotalSteps(),
                preview,state,joined==null||joined.getAssetId()==null?null:joined.getAssetId().toString(),error);
    }
    public Frame snapshot(long id,long userId){
        return transactions.execute(status->{
            GenerationTaskEntity task=tasks.lockTask(id);
            if(task==null || task.getUserId()!=userId)throw new BusinessException(ErrorCode.NOT_FOUND);
            View current=view(task);BrowserStateMapper.State state=states.find(id);
            long sequence=state==null?0:state.sequence();
            if(state!=null){
                View previous=json.readValue(state.snapshotJson(),View.class);
                if(Set.of("SUCCEEDED","FAILED").contains(previous.status()) && !previous.status().equals(current.status()))
                    return frame(previous,sequence,"task_snapshot");
            } else states.save(new BrowserStateMapper.State(id,0,json.writeValueAsString(current)));
            return frame(current,sequence,"task_snapshot");
        });
    }
    private String business(View view){
        return json.writeValueAsString(new View(view.id(),view.userId(),view.status(),view.simulated(),view.stage(),view.completedSteps(),
                view.totalSteps(),view.latestPreview(),"DISCONNECTED",view.assetId(),view.error()==null?null:new TreeMap<>(view.error())));
    }
    public Frame record(long id,String type,long pythonSequence){
        return transactions.execute(status->{
            GenerationTaskEntity task=tasks.lockTask(id);if(task==null)return null;
            View view=view(task);BrowserStateMapper.State previous=states.find(id);
            long sequence=previous==null?0:previous.sequence();
            if(type.equals("connection_status"))return frame(view,sequence,type);
            String adapted=type.equals("task_snapshot")?typeFor(view):type;
            if(!Set.of("task_queued","task_started","sampling_step","preview_started","preview_ready","preview_failed","finalizing","result_saving","task_completed","task_failed").contains(adapted))return null;
            if(adapted.equals("task_completed") && (!view.status().equals("SUCCEEDED") || view.assetId()==null))return null;
            if(adapted.equals("task_failed") && !view.status().equals("FAILED"))return null;
            if((view.status().equals("SUCCEEDED") || view.status().equals("FAILED")) && !Set.of("task_completed","task_failed","preview_ready").contains(adapted))return null;
            if(adapted.equals("preview_ready") && view.latestPreview()==null)return null;
            String key=adapted+":"+pythonSequence+":"+(view.latestPreview()==null?0:view.latestPreview().version());
            if(states.accept(id,key)!=1)return null;
            if(previous!=null && !adapted.equals("preview_failed") && business(json.readValue(previous.snapshotJson(),View.class)).equals(business(view)))return null;
            sequence++;
            states.save(new BrowserStateMapper.State(id,sequence,json.writeValueAsString(view)));
            return frame(view,sequence,adapted);
        });
    }
    private String typeFor(View view){return switch(view.stage()){
        case "completed"->"task_completed";case "failed"->"task_failed";case "preprocessing"->"task_started";
        case "sampling"->"sampling_step";case "preview_decoding"->"preview_started";case "final_decoding"->"finalizing";
        case "result_saving"->"result_saving";default->"task_queued";};}
    public Frame frame(View view,long sequence,String type){return new Frame(view,sequence,type,clock.instant().toString());}
    public record View(long id,long userId,String status,boolean simulated,String stage,int completedSteps,int totalSteps,
            GenerationPreviewService.PreviewMetadata latestPreview,String connectionState,String assetId,Map<String,String> error){}
    public record Frame(View view,long sequence,String type,String timestamp,boolean fatal){
        public Frame(View view,long sequence,String type,String timestamp){this(view,sequence,type,timestamp,false);}
    }
}
