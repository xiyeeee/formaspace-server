package com.xiye.formaspace.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import com.xiye.formaspace.common.enums.UserStatus;

@Getter
@Setter
@TableName("fs_user")
public class UserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String email;
    private String username;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private UserStatus status;
    private Instant lastLoginAt;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
