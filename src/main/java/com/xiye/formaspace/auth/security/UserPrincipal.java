package com.xiye.formaspace.auth.security;

import com.xiye.formaspace.user.vo.UserVO;

public record UserPrincipal(long id, UserVO user) {
    @Override
    public String toString() {
        return "UserPrincipal[id=" + id + "]";
    }
}
