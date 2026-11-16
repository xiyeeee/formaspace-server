package com.xiye.formaspace.auth.security;

import com.xiye.formaspace.common.exception.BusinessException;
import com.xiye.formaspace.common.exception.ErrorCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public UserPrincipal require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    public long id() {
        return require().id();
    }
}
