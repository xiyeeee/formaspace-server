package com.xiye.formaspace.generation.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import com.xiye.formaspace.generation.enums.GenerationStatus;

@Getter
@Setter
@TableName("fs_generation_task")
public class GenerationTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long sourceFileId;
    private String provider;
    private String providerTaskId;
    private String executionMode;
    private String remoteReference;
    private String remoteResultReference;
    private String inferenceStage;
    private Integer queryFailures;
    private Instant nextPollAt;
    private Instant deadlineAt;
    private String idempotencyKey;
    private String requestHash;
    private String requestedParameters;
    private String resolvedParameters;
    private Integer parameterSchemaVersion;
    private String modelId;
    private String profileId;
    private Boolean simulated;
    @TableField(exist = false)
    private Long assetId;
    private GenerationStatus status;
    private Integer progress;
    private String errorCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
