package com.xiye.formaspace.user.vo;

import java.time.Instant;
import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.user.entity.UserEntity;

public record UserVO(String id, String email, String username, String nickname, String avatarUrl,
        UserStatus status, Instant createdAt) {
    public static UserVO from(UserEntity user) {
        return new UserVO(user.getId().toString(), user.getEmail(), user.getUsername(), user.getNickname(),
                user.getAvatarUrl(), user.getStatus(), user.getCreatedAt());
    }
}
