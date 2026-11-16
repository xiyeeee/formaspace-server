package com.xiye.formaspace.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import com.xiye.formaspace.auth.enums.VerificationPurpose;

@Getter
@Setter
@TableName("fs_email_verification")
public class EmailVerificationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String email;
    private VerificationPurpose purpose;
    private String codeHash;
    private String challengeId;
    private com.xiye.formaspace.auth.enums.EmailSendStatus sendStatus;
    private Instant expiresAt;
    private Instant consumedAt;
    private Integer failedAttempts;
    private Instant sentAt;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
