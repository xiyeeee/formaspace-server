package com.xiye.formaspace.generation.realtime;

import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.file.storage.FileStorage;
import com.xiye.formaspace.generation.mapper.GenerationTaskMapper;
import java.io.*;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class GenerationPreviewService {
    private final GenerationPreviewMapper previews;
    private final GenerationTaskMapper tasks;
    private final FileStorage storage;
    private final FileService files;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public GenerationPreviewService(GenerationPreviewMapper previews,GenerationTaskMapper tasks,FileStorage storage,
            FileService files,ObjectMapper json,Clock clock,PlatformTransactionManager manager) {
        this.previews=previews;this.tasks=tasks;this.storage=storage;this.files=files;this.json=json;this.clock=clock;
        transactions=new TransactionTemplate(manager);
    }
    public void save(long taskId,PythonRealtimeProtocol.PointCloud cloud) throws IOException {
        if (previews.find(taskId,cloud.previewVersion())!=null) { previews.markStored(taskId,cloud.previewVersion());return; }
        var view=new PreviewVO(Long.toString(taskId),cloud.previewVersion(),cloud.sourceStep(),cloud.pointCount(),PointCloudPrintPlan.order(cloud.positions()));
        var stored=storage.save(new ByteArrayInputStream(json.writeValueAsBytes(view)),"json",2097152);
        var attempted=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            Boolean inserted=transactions.execute(status -> {
                if (tasks.lockTask(taskId)==null) return false;
                var file=new GenerationPreviewMapper.PreviewFile();
                file.setTaskId(taskId);file.setVersion(cloud.previewVersion());file.setSourceStep(cloud.sourceStep());
                file.setPointCount(cloud.pointCount());file.setStorageKey(stored.key());file.setCreatedAt(clock.instant());
                if (previews.insert(file)!=1) return false;
                previews.markStored(taskId,cloud.previewVersion());attempted.set(true);return true;
            });
            if (!Boolean.TRUE.equals(inserted)) files.cleanup(stored.key());
        } catch (RuntimeException exception) {
            // A commit result may be uncertain. Keep a possibly referenced preview instead of deleting it.
            if (!attempted.get()) files.cleanup(stored.key());
            throw exception;
        }
    }
    public Double decodeSeconds(long id,int version) {
        GenerationPreviewMapper.PreviewFile file=previews.find(id,version);
        return file==null?null:file.getDecodeSeconds();
    }
    public void recordDecodeSeconds(long id,int version,double seconds) {
        if(Double.isFinite(seconds) && seconds>=0)previews.setDecodeSeconds(id,version,seconds);
    }
    public PreviewMetadata latest(long taskId,Integer version) {
        if (version==null || version<1) return null;
        var file=previews.find(taskId,version);
        return file==null ? null : new PreviewMetadata(version,file.getSourceStep(),file.getPointCount(),
                "/api/generation/tasks/"+taskId+"/previews/"+version);
    }
    public PreviewVO read(long taskId,int version,long userId) {
        if (tasks.findOwned(taskId,userId)==null) throw new BusinessException(ErrorCode.NOT_FOUND);
        var file=previews.find(taskId,version);
        if (file==null) throw new BusinessException(ErrorCode.NOT_FOUND);
        try (var content=storage.open(file.getStorageKey())) {
            if (content.sizeBytes()>2097152) throw new IOException("Preview size exceeds limit");
            return json.readValue(content.stream().readNBytes(2097153),PreviewVO.class);
        } catch (IOException exception) { throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE); }
    }
    public record PreviewMetadata(int version,int sourceStep,int pointCount,String positionsPath) {}
    public record PreviewVO(String taskId,int previewVersion,int sourceStep,int pointCount,List<List<Double>> positions) {}
}
