package com.xiye.formaspace.auth.service;

import com.xiye.formaspace.auth.config.JwtProperties;
import com.xiye.formaspace.auth.vo.LoginVO;
import com.xiye.formaspace.user.vo.UserVO;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenService {
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public LoginVO issue(UserVO user) {
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        long ttl = properties.getAccessTokenTtl().toSeconds();
        var claims = JwtClaimsSet.builder().subject(user.id()).issuer(properties.getIssuer())
                .audience(List.of(properties.getAudience())).issuedAt(now).expiresAt(now.plusSeconds(ttl))
                .id(UUID.randomUUID().toString()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginVO(token, "Bearer", ttl, user);
    }
}
