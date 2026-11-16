package com.xiye.formaspace.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import com.xiye.formaspace.auth.controller.AuthController;
import com.xiye.formaspace.auth.security.*;
import com.xiye.formaspace.auth.service.*;
import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.config.*;
import com.xiye.formaspace.health.controller.HealthController;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuthController.class, HealthController.class})
@Import({SecurityConfig.class, JwtConfig.class, WebConfig.class, SecurityJsonResponse.class,
        ActiveUserAuthenticationConverter.class, CurrentUser.class, TokenService.class, AuthSecurityTests.PermissionProbe.class})
class AuthSecurityTests {
    private static final byte[] JWT_KEY = randomKey();
    private static final byte[] CODE_KEY = randomKey();
    @Autowired
    private MockMvc mvc;
    @Autowired
    private TokenService tokens;
    @MockitoBean
    private UserMapper users;
    @MockitoBean
    private AuthService auth;
    @MockitoBean
    private EmailVerificationService verification;

    private static byte[] randomKey() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("formaspace.jwt.secret", () -> Base64.getEncoder().encodeToString(JWT_KEY));
        registry.add("formaspace.email-code.hmac-key", () -> Base64.getEncoder().encodeToString(CODE_KEY));
        registry.add("formaspace.cors.allowed-origins", () -> "http://localhost:5173");
    }

    @BeforeEach
    void activeUser() {
        var user = new UserEntity();
        user.setId(1L);
        user.setEmail("user@example.com");
        user.setUsername("user_1");
        user.setNickname("user_1");
        user.setPasswordHash("never-return-this-hash");
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(Instant.parse("2026-11-16T00:00:00Z"));
        when(users.selectById(1L)).thenReturn(user);
    }

    private JWTClaimsSet.Builder claims() {
        var now = Instant.now();
        return new JWTClaimsSet.Builder().subject("1").issuer("formaspace-server").audience("formaspace-web")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(7200))).jwtID(UUID.randomUUID().toString());
    }

    private String sign(JWTClaimsSet claims, JWSAlgorithm algorithm) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(algorithm), claims);
        jwt.sign(new MACSigner(JWT_KEY));
        return jwt.serialize();
    }

    @Test
    void bearerAuthenticatesCurrentUserWithoutSessionOrTokenLeak() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + sign(claims().build(), JWSAlgorithm.HS256)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("1"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
        verify(users).selectById(1L);
    }

    @Test
    void realEncoderIssuesMinimalTokenAndLoginUsesNoStore() throws Exception {
        var issued = tokens.issue(com.xiye.formaspace.user.vo.UserVO.from(users.selectById(1L)));
        assertThat(issued.tokenType()).isEqualTo("Bearer");
        assertThat(issued.expiresIn()).isEqualTo(7200);
        var decoded = SignedJWT.parse(issued.accessToken());
        assertThat(decoded.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(decoded.getJWTClaimsSet().getClaims().keySet()).containsExactlyInAnyOrder("sub", "iss", "aud", "iat", "exp", "jti");
        assertThat(issued.toString()).doesNotContain(issued.accessToken());
        when(auth.login(any(), anyString())).thenReturn(issued);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"user_1\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + issued.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void missingTokenCookieAndQueryTokenCannotAuthenticate() throws Exception {
        String token = sign(claims().build(), JWSAlgorithm.HS256);
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/auth/me").param("access_token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie("access_token", token)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "issuer", "audience", "subject", "overflow", "missing-exp", "missing-iat", "future-iat", "missing-jti", "wrong-algorithm", "unsigned", "tampered", "malformed"})
    void rejectsInvalidTokens(String variant) throws Exception {
        var builder = claims();
        switch (variant) {
            case "expired" -> builder.issueTime(Date.from(Instant.now().minusSeconds(500))).expirationTime(Date.from(Instant.now().minusSeconds(120)));
            case "issuer" -> builder.issuer("other");
            case "audience" -> builder.audience("other");
            case "subject" -> builder.subject("abc");
            case "overflow" -> builder.subject("9223372036854775808");
            case "missing-exp" -> builder.expirationTime(null);
            case "missing-iat" -> builder.issueTime(null);
            case "future-iat" -> builder.issueTime(Date.from(Instant.now().plusSeconds(120)));
            case "missing-jti" -> builder.jwtID(null);
            default -> { }
        }
        String token = switch (variant) {
            case "wrong-algorithm" -> sign(builder.build(), JWSAlgorithm.HS512);
            case "unsigned" -> new PlainJWT(builder.build()).serialize();
            case "tampered" -> sign(builder.build(), JWSAlgorithm.HS256).replaceFirst("\\.", ".x");
            case "malformed" -> "bad-token";
            default -> sign(builder.build(), JWSAlgorithm.HS256);
        };
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(users);
    }

    @Test
    void disabledAndDeletedAccountsRejectPreviouslyIssuedToken() throws Exception {
        var user = users.selectById(1L);
        user.setStatus(UserStatus.DISABLED);
        String token = sign(claims().build(), JWSAlgorithm.HS256);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        when(users.selectById(1L)).thenReturn(null);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void databaseFailureInSecurityChainReturns503Json() throws Exception {
        when(users.selectById(1L)).thenThrow(new DataAccessResourceFailureException("private database details"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + sign(claims().build(), JWSAlgorithm.HS256)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database details"))));
    }

    @Test
    void onlyExactPublicRoutesAreAllowedAndValidationDoesNotRequireCsrf() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/email-codes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/password/reset")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void rateLimitHasRetryAfterAndDatabaseErrorsStay503() throws Exception {
        when(auth.login(any(), anyString())).thenThrow(new BusinessException(ErrorCode.RATE_LIMITED, 60L));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"user_1\",\"password\":\"password123\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        doThrow(new DataAccessResourceFailureException("secret")).when(auth).login(any(), anyString());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"user_1\",\"password\":\"password123\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void securityChainHonorsCorsAndRejectsForbiddenRequestsAsJson() throws Exception {
        mvc.perform(options("/api/auth/me").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/api/auth/me").header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/auth/me")).andExpect(status().isOk());
        mvc.perform(get("/test/forbidden").header("Authorization", "Bearer " + sign(claims().build(), JWSAlgorithm.HS256)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @RestController
    static class PermissionProbe {
        @GetMapping("/test/forbidden")
        void forbidden() {
            throw new org.springframework.security.access.AccessDeniedException("denied");
        }
    }
}
