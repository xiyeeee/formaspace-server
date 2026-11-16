package com.xiye.formaspace.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("fs_auth_rate_limit")
public class AuthRateLimitEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bucketKey;
    private Long windowStart;
    private Integer attempts;
    private Instant expiresAt;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
