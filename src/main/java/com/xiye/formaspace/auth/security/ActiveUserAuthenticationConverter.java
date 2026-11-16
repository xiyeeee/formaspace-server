package com.xiye.formaspace.auth.security;

import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.user.mapper.UserMapper;
import com.xiye.formaspace.user.vo.UserVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ActiveUserAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserMapper users;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            var user = users.selectById(Long.parseLong(jwt.getSubject()));
            if (user == null || user.getStatus() != UserStatus.ACTIVE) {
                throw new BadCredentialsException("Invalid credentials");
            }
            return UsernamePasswordAuthenticationToken.authenticated(
                    new UserPrincipal(user.getId(), UserVO.from(user)), null, List.of());
        } catch (DataAccessException exception) {
            // Do not retain JDBC exception messages/causes in the security chain.
            throw new AuthenticationStorageException();
        }
    }
}
