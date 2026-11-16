package com.xiye.formaspace.asset.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("fs_model_asset")
public class ModelAssetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long generationTaskId;
    private String name;
    private Long modelFileId;
    private Long thumbnailFileId;
    private String format;
    private Long sizeBytes;
    private Boolean simulated;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
