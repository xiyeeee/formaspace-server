package com.xiye.formaspace.generation.realtime;

import java.time.Instant;
import org.apache.ibatis.annotations.*;
import lombok.Getter;
import lombok.Setter;

@Mapper
public interface GenerationPreviewMapper {
    @Update("UPDATE fs_generation_preview SET decode_seconds=#{seconds} WHERE task_id=#{id} AND version=#{version}")
    int setDecodeSeconds(@Param("id")long id,@Param("version")int version,@Param("seconds")double seconds);

    @Select("SELECT * FROM fs_generation_preview WHERE task_id=#{taskId} AND version=#{version}")
    PreviewFile find(@Param("taskId") long taskId,@Param("version") int version);
    @Insert("INSERT IGNORE INTO fs_generation_preview(task_id,version,source_step,point_count,storage_key,created_at) VALUES(#{taskId},#{version},#{sourceStep},#{pointCount},#{storageKey},#{createdAt})")
    int insert(PreviewFile file);
    @Update("""
            UPDATE fs_generation_task SET stored_preview_version=GREATEST(stored_preview_version,#{version}),
            preview_error_code=CASE WHEN preview_error_code='PREVIEW_STORAGE_UNAVAILABLE' THEN NULL ELSE preview_error_code END
            WHERE id=#{taskId} AND execution_mode='PYTHON_REALTIME'
            """)
    int markStored(@Param("taskId") long taskId,@Param("version") int version);
    @Getter @Setter
    class PreviewFile {
        private Long taskId;
        private Integer version;
        private Integer sourceStep;
        private Integer pointCount;
        private String storageKey;
        private Instant createdAt;
        private Double decodeSeconds;
    }
}
