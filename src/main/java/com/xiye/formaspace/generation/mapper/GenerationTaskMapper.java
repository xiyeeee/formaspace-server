package com.xiye.formaspace.generation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiye.formaspace.generation.entity.GenerationTaskEntity;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface GenerationTaskMapper extends BaseMapper<GenerationTaskEntity> {
    @Select("SELECT * FROM fs_generation_task WHERE execution_mode='PYTHON_REALTIME' AND simulated=FALSE AND status IN ('PENDING','SUBMITTING','PROCESSING') ORDER BY created_at,id LIMIT #{limit}")
    List<GenerationTaskEntity> realtimeTasks(@Param("limit") int limit);

    @Select("""
            SELECT * FROM fs_generation_task WHERE execution_mode='PYTHON_REALTIME' AND simulated=FALSE
            AND status IN ('SUCCEEDED','FAILED') AND python_preview_version>stored_preview_version
            AND inference_service=#{service} AND (next_poll_at IS NULL OR next_poll_at <= #{now})
            ORDER BY id LIMIT #{limit}
            """)
    List<GenerationTaskEntity> realtimePreviewRepairs(@Param("service") String service,@Param("now") Instant now,@Param("limit") int limit);

    @Update("""
            UPDATE fs_generation_task SET query_failures=query_failures+1,next_poll_at=#{next},
            preview_error_code='PREVIEW_STORAGE_UNAVAILABLE',updated_at=#{now}
            WHERE id=#{id} AND execution_mode='PYTHON_REALTIME' AND status IN ('SUCCEEDED','FAILED')
            """)
    int retryRealtimePreview(@Param("id") long id,@Param("next") Instant next,@Param("now") Instant now);

    @Update("""
            UPDATE fs_generation_task SET status=#{status},submission_state=#{submissionState},submission_attempts=#{submissionAttempts},
            last_python_sequence=#{lastPythonSequence},inference_stage=#{inferenceStage},completed_steps=#{completedSteps},
            total_steps=#{totalSteps},python_preview_version=#{pythonPreviewVersion},connection_state=#{connectionState},
            preview_error_code=#{previewErrorCode},remote_result_reference=#{remoteResultReference},next_poll_at=#{nextPollAt},
            query_failures=#{queryFailures},started_at=COALESCE(started_at,#{startedAt}),updated_at=#{updatedAt}
            WHERE id=#{id} AND execution_mode='PYTHON_REALTIME' AND simulated=FALSE
              AND status IN ('PENDING','SUBMITTING','PROCESSING') AND last_python_sequence <= #{lastPythonSequence}
            """)
    int saveRealtime(GenerationTaskEntity task);

    @Update("""
            UPDATE fs_generation_task SET status='SUCCEEDED',finished_at=#{now},updated_at=#{now},
            inference_stage='completed',connection_state='CLOSED'
            WHERE id=#{id} AND execution_mode='PYTHON_REALTIME' AND simulated=FALSE AND status='PROCESSING'
            """)
    int succeedRealtime(@Param("id") long id,@Param("now") Instant now);

    @Update("""
            UPDATE fs_generation_task SET status='FAILED',error_code=#{code},error_message=#{message},finished_at=#{now},
            updated_at=#{now},connection_state='CLOSED'
            WHERE id=#{id} AND execution_mode='PYTHON_REALTIME' AND simulated=FALSE AND status IN ('PENDING','SUBMITTING','PROCESSING')
            """)
    int failRealtime(@Param("id") long id,@Param("code") String code,@Param("message") String message,@Param("now") Instant now);
    @Select("SELECT * FROM fs_generation_task WHERE user_id=#{userId} AND idempotency_key=#{key}")
    GenerationTaskEntity findIdempotent(@Param("userId") long userId, @Param("key") String key);

    @Select("SELECT t.*, a.id AS asset_id FROM fs_generation_task t LEFT JOIN fs_model_asset a ON a.generation_task_id=t.id WHERE t.id=#{id} AND t.user_id=#{userId}")
    GenerationTaskEntity findOwned(@Param("id") long id, @Param("userId") long userId);

    @Select("""
            <script>SELECT t.*, a.id AS asset_id FROM fs_generation_task t
            LEFT JOIN fs_model_asset a ON a.generation_task_id=t.id WHERE t.user_id=#{userId}
            <if test="status != null">AND t.status=#{status}</if>
            ORDER BY t.created_at DESC, t.id DESC</script>
            """)
    Page<GenerationTaskEntity> pageOwned(Page<GenerationTaskEntity> page, @Param("userId") long userId,
            @Param("status") GenerationStatus status);

    @Select("SELECT COUNT(*) FROM fs_generation_task WHERE user_id=#{userId} AND status IN ('PENDING','SUBMITTING','PROCESSING')")
    long countActive(@Param("userId") long userId);

    @Select("SELECT * FROM fs_generation_task WHERE simulated=TRUE AND execution_mode='MOCK' AND status IN ('SUBMITTING','PROCESSING') ORDER BY created_at,id LIMIT #{limit}")
    List<GenerationTaskEntity> activeMock(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM fs_generation_task WHERE simulated=TRUE AND execution_mode='MOCK' AND status IN ('SUBMITTING','PROCESSING')")
    long countActiveMock();

    @Select("SELECT * FROM fs_generation_task WHERE simulated=TRUE AND execution_mode='MOCK' AND status='PENDING' ORDER BY created_at,id LIMIT #{limit}")
    List<GenerationTaskEntity> pendingMock(@Param("limit") int limit);

    @Select("SELECT * FROM fs_generation_task WHERE id=#{id} FOR UPDATE")
    GenerationTaskEntity lockTask(@Param("id") long id);

    @Update("""
            UPDATE fs_generation_task SET status='SUBMITTING', started_at=#{now}, updated_at=#{now}
            WHERE id=#{id} AND simulated=TRUE AND execution_mode='MOCK' AND status='PENDING'
            """)
    int claimMock(@Param("id") long id, @Param("now") Instant now);

    @Update("UPDATE fs_generation_task SET status='PROCESSING', updated_at=#{now} WHERE id=#{id} AND simulated=TRUE AND execution_mode='MOCK' AND status='SUBMITTING'")
    int processing(@Param("id") long id, @Param("now") Instant now);

    @Update("UPDATE fs_generation_task SET status='SUCCEEDED', finished_at=#{now}, updated_at=#{now} WHERE id=#{id} AND simulated=TRUE AND execution_mode='MOCK' AND status='PROCESSING'")
    int succeed(@Param("id") long id, @Param("now") Instant now);

    @Update("""
            UPDATE fs_generation_task SET status='FAILED', error_code=#{code}, error_message=#{message},
              finished_at=#{now}, updated_at=#{now}
            WHERE id=#{id} AND simulated=TRUE AND execution_mode='MOCK' AND status IN ('PENDING','SUBMITTING','PROCESSING')
            """)
    int fail(@Param("id") long id, @Param("code") String code, @Param("message") String message, @Param("now") Instant now);

    @Select("SELECT * FROM fs_generation_task WHERE execution_mode='HUNYUAN_LOCAL' AND simulated=FALSE AND status IN ('SUBMITTING','PROCESSING') ORDER BY created_at,id LIMIT 1")
    List<GenerationTaskEntity> activeReal();

    @Select("SELECT * FROM fs_generation_task WHERE execution_mode='HUNYUAN_LOCAL' AND simulated=FALSE AND status='PENDING' ORDER BY created_at,id LIMIT 1")
    List<GenerationTaskEntity> pendingReal();

    @Update("""
            UPDATE fs_generation_task SET status='SUBMITTING',started_at=#{now},updated_at=#{now},deadline_at=#{deadline},inference_stage='SUBMITTING'
            WHERE id=#{id} AND execution_mode='HUNYUAN_LOCAL' AND simulated=FALSE AND status='PENDING'
              AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM fs_generation_task WHERE execution_mode='HUNYUAN_LOCAL'
                AND status IN ('SUBMITTING','PROCESSING') LIMIT 1) AS active_task)
            """)
    int claimReal(@Param("id") long id,@Param("now") Instant now,@Param("deadline") Instant deadline);

    @Update("""
            UPDATE fs_generation_task SET status='PROCESSING',remote_reference=#{reference},provider_task_id=#{eventId},
              inference_stage='GENERATING',updated_at=#{now},next_poll_at=#{now}
            WHERE id=#{id} AND execution_mode='HUNYUAN_LOCAL' AND simulated=FALSE AND status='SUBMITTING'
            """)
    int submittedReal(@Param("id") long id,@Param("reference") String reference,@Param("eventId") String eventId,@Param("now") Instant now);

    @Update("""
            UPDATE fs_generation_task SET remote_result_reference=#{reference},inference_stage='RESULT_READY',
              query_failures=0,next_poll_at=#{now},updated_at=#{now}
            WHERE id=#{id} AND execution_mode='HUNYUAN_LOCAL' AND simulated=FALSE AND status='PROCESSING'
            """)
    int resultReadyReal(@Param("id") long id,@Param("reference") String reference,@Param("now") Instant now);

    @Update("""
            UPDATE fs_generation_task SET query_failures=query_failures+1,next_poll_at=#{next},updated_at=#{now}
            WHERE id=#{id} AND execution_mode='HUNYUAN_LOCAL' AND simulated=FALSE AND status='PROCESSING'
            """)
    int retryReal(@Param("id") long id,@Param("next") Instant next,@Param("now") Instant now);

    @Update("""
            UPDATE fs_generation_task SET status='SUCCEEDED',finished_at=#{now},updated_at=#{now},inference_stage='COMPLETED'
            WHERE id=#{id} AND execution_mode='HUNYUAN_LOCAL' AND simulated=FALSE AND status='PROCESSING' AND deadline_at > #{now}
            """)
    int succeedReal(@Param("id") long id,@Param("now") Instant now);

    @Update("""
            UPDATE fs_generation_task SET status='FAILED',error_code=#{code},error_message=#{message},finished_at=#{now},updated_at=#{now}
            WHERE id=#{id} AND execution_mode='HUNYUAN_LOCAL' AND simulated=FALSE AND status IN ('PENDING','SUBMITTING','PROCESSING')
            """)
    int failReal(@Param("id") long id,@Param("code") String code,@Param("message") String message,@Param("now") Instant now);
}
