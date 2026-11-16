package com.xiye.formaspace.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.xiye.formaspace.auth.config.JwtProperties;
import com.xiye.formaspace.auth.security.SecretKeys;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {
    @Bean
    public SecretKey jwtSecretKey(JwtProperties jwt, EmailCodeProperties email) {
        byte[] secret = SecretKeys.decode(jwt.getSecret(), "JWT_SECRET");
        byte[] codeKey = SecretKeys.decode(email.getHmacKey(), "AUTH_CODE_HMAC_KEY");
        if (MessageDigest.isEqual(secret, codeKey)) {
            throw new IllegalStateException("JWT_SECRET 和 AUTH_CODE_HMAC_KEY 必须使用不同的随机密钥");
        }
        if (jwt.getAccessTokenTtl().toSeconds() < 1 || jwt.getClockSkew().isNegative()
                || jwt.getClockSkew().compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException("JWT 有效期必须至少 1 秒，时钟偏差必须在 0～30 秒之间");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties properties, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(properties.getClockSkew());
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            try {
                String subject = jwt.getSubject();
                boolean valid = subject != null && subject.matches("[1-9][0-9]{0,18}")
                        && Long.parseLong(subject) > 0 && jwt.getAudience().contains(properties.getAudience())
                        && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                        && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                        && !jwt.getIssuedAt().isAfter(clock.instant().plus(properties.getClockSkew()))
                        && jwt.getId() != null && !jwt.getId().isBlank();
                if (valid) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (RuntimeException ignored) {
                // Malformed claims are authentication failures, never an internal error or a log payload.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token claims", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(properties.getIssuer()), requiredClaims));
        return decoder;
    }
}
