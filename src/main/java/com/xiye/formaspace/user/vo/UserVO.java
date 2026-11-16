package com.xiye.formaspace.user.vo;

import java.time.Instant;
import com.xiye.formaspace.common.enums.UserStatus;

public record UserVO(String id, String email, String username, String nickname, String avatarUrl, UserStatus status, Instant createdAt) {}
