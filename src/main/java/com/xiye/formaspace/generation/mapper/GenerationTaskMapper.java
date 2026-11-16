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

    @Select("SELECT * FROM fs_generation_task WHERE simulated=TRUE AND status IN ('SUBMITTING','PROCESSING') ORDER BY created_at,id LIMIT #{limit}")
    List<GenerationTaskEntity> activeMock(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM fs_generation_task WHERE simulated=TRUE AND status IN ('SUBMITTING','PROCESSING')")
    long countActiveMock();

    @Select("SELECT * FROM fs_generation_task WHERE simulated=TRUE AND status='PENDING' ORDER BY created_at,id LIMIT #{limit}")
    List<GenerationTaskEntity> pendingMock(@Param("limit") int limit);

    @Select("SELECT * FROM fs_generation_task WHERE id=#{id} FOR UPDATE")
    GenerationTaskEntity lockTask(@Param("id") long id);

    @Update("""
            UPDATE fs_generation_task SET status='SUBMITTING', started_at=#{now}, updated_at=#{now}
            WHERE id=#{id} AND simulated=TRUE AND status='PENDING'
            """)
    int claimMock(@Param("id") long id, @Param("now") Instant now);

    @Update("UPDATE fs_generation_task SET status='PROCESSING', updated_at=#{now} WHERE id=#{id} AND simulated=TRUE AND status='SUBMITTING'")
    int processing(@Param("id") long id, @Param("now") Instant now);

    @Update("UPDATE fs_generation_task SET status='SUCCEEDED', finished_at=#{now}, updated_at=#{now} WHERE id=#{id} AND simulated=TRUE AND status='PROCESSING'")
    int succeed(@Param("id") long id, @Param("now") Instant now);

    @Update("""
            UPDATE fs_generation_task SET status='FAILED', error_code=#{code}, error_message=#{message},
              finished_at=#{now}, updated_at=#{now}
            WHERE id=#{id} AND simulated=TRUE AND status IN ('PENDING','SUBMITTING','PROCESSING')
            """)
    int fail(@Param("id") long id, @Param("code") String code, @Param("message") String message, @Param("now") Instant now);
}
