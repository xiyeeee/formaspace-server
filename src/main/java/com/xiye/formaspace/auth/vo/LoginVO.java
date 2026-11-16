package com.xiye.formaspace.auth.vo;

import com.xiye.formaspace.user.vo.UserVO;

public record LoginVO(String accessToken, String tokenType, long expiresIn, UserVO user) {
    @Override
    public String toString() {
        return "LoginVO[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
