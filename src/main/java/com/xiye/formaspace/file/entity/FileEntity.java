package com.xiye.formaspace.file.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import com.xiye.formaspace.common.enums.FilePurpose;

@Getter
@Setter
@TableName("fs_file")
public class FileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String originalName;
    private String storageKey;
    private String contentType;
    private Long sizeBytes;
    private FilePurpose purpose;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
