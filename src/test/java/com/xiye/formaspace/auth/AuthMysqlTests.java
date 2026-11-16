package com.xiye.formaspace.auth;

import com.xiye.formaspace.auth.dto.*;
import com.xiye.formaspace.auth.enums.VerificationPurpose;
import com.xiye.formaspace.auth.service.*;
import com.xiye.formaspace.common.exception.*;
import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs only against a user-provided, disposable MySQL schema ending in _test. Never uses H2. */
@SpringBootTest(properties = {"formaspace.email-code.enabled=true", "formaspace.cors.allowed-origins=http://localhost:5173"})
@AutoConfigureMockMvc
@Import(AuthMysqlTests.TestClockConfig.class)
@EnabledIfEnvironmentVariable(named = "AUTH_TEST_DB_URL", matches = "jdbc:mysql:.*")
class AuthMysqlTests {
    @Autowired private AuthService auth;
    @Autowired private EmailVerificationService verification;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private MutableClock clock;
    @MockitoBean private VerificationMailSender mail;
    private final Map<String, String> codes = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        String url = System.getenv("AUTH_TEST_DB_URL");
        String database = URI.create(url.substring(5)).getPath();
        if (database == null || !database.matches("/[A-Za-z0-9_]+_test")) {
            throw new IllegalStateException("鉴权集成测试必须使用独立、可清空、以 _test 结尾的 MySQL 数据库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("AUTH_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("AUTH_TEST_DB_PASSWORD"));
        registry.add("formaspace.jwt.secret", AuthMysqlTests::randomKey);
        registry.add("formaspace.email-code.hmac-key", AuthMysqlTests::randomKey);
    }

    private static String randomKey() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void prepareDisposableSchema() {
        for (String table : List.of("fs_model_asset", "fs_generation_task", "fs_file", "fs_user", "fs_email_verification", "fs_auth_rate_limit")) {
            jdbc.update("DELETE FROM " + table + " WHERE id > 0");
        }
        clock.set(Instant.parse("2026-11-16T00:00:00Z"));
        codes.clear();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            codes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(mail).sendRegistration(anyString(), anyString(), any());
    }

    private void send(String email) {
        verification.send(email, VerificationPurpose.REGISTER, "127.0.0.1");
    }

    private RegisterDTO registration(String email, String username) {
        var request = new RegisterDTO();
        request.setEmail(email);
        request.setUsername(username);
        request.setVerificationCode(codes.get(email));
        request.setPassword(" password123 ");
        request.setConfirmPassword(" password123 ");
        return request;
    }

    private LoginDTO login(String account) {
        var request = new LoginDTO();
        request.setAccount(account);
        request.setPassword(" password123 ");
        return request;
    }

    private void assertBusiness(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void fullHttpFlowStoresHashAndSupportsUsernameEmailAndJwtMe() throws Exception {
        mvc.perform(post("/api/auth/email-codes").contentType("application/json")
                        .content("{\"email\":\"first.last+tag@example.com\",\"purpose\":\"REGISTER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.retryAfterSeconds").value(60))
                .andExpect(jsonPath("$.data.verificationCode").doesNotExist());
        var request = registration("first.last+tag@example.com", "USER_1");
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.username").value("user_1"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
        String hash = jdbc.queryForObject("SELECT password_hash FROM fs_user WHERE username='user_1'", String.class);
        assertThat(hash).startsWith("{pbkdf2@SpringSecurity_v5_8}");
        assertThat(passwords.matches(" password123 ", hash)).isTrue();
        for (String account : List.of("USER_1", " FIRST.LAST+TAG@EXAMPLE.COM ")) {
            String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                            .content(json.writeValueAsString(login(account))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.data.expiresIn").value(7200))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andReturn().getResponse().getContentAsString();
            String token = json.readTree(body).get("data").get("accessToken").asString();
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("user_1"));
        }
        assertThat(jdbc.queryForObject("SELECT last_login_at IS NOT NULL FROM fs_user WHERE username='user_1'", Boolean.class)).isTrue();
    }

    @Test
    void incorrectCodesCommitFiveAttemptsAndThenInvalidateChallenge() {
        send("user@example.com");
        var request = registration("user@example.com", "user_1");
        String correct = request.getVerificationCode();
        request.setVerificationCode(correct.equals("000000") ? "111111" : "000000");
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertBusiness(() -> auth.register(request, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
            assertThat(jdbc.queryForObject("SELECT failed_attempts FROM fs_email_verification WHERE email='user@example.com'", Integer.class)).isEqualTo(attempt);
        }
        request.setVerificationCode(correct);
        assertBusiness(() -> auth.register(request, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fs_user", Integer.class)).isZero();
    }

    @Test
    void duplicateUsernameAndEmailRollBackWithoutConsumingValidChallenge() {
        send("first@example.com");
        auth.register(registration("first@example.com", "user_1"), "127.0.0.1");
        send("second@example.com");
        var second = registration("second@example.com", "USER_1");
        assertBusiness(() -> auth.register(second, "127.0.0.1"), ErrorCode.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM fs_email_verification WHERE email='second@example.com'", Boolean.class)).isTrue();
        second.setUsername("user_2");
        auth.register(second, "127.0.0.1");
        // Reconstruct a sent slot for an existing email to exercise the database email unique constraint.
        jdbc.update("UPDATE fs_email_verification SET consumed_at=NULL WHERE email='second@example.com'");
        second.setUsername("user_3");
        assertBusiness(() -> auth.register(second, "127.0.0.1"), ErrorCode.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM fs_email_verification WHERE email='second@example.com'", Boolean.class)).isTrue();
    }

    @Test
    void sameChallengeConcurrentRegistrationSucceedsExactlyOnce() throws Exception {
        send("user@example.com");
        var request = registration("user@example.com", "user_1");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> action = () -> {
                try { auth.register(request, "127.0.0.1"); return true; }
                catch (BusinessException error) {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VERIFICATION_INVALID);
                    return false;
                }
            };
            var results = executor.invokeAll(List.of(action, action));
            assertThat(results.stream().map(result -> {
                try { return result.get(); } catch (Exception error) { throw new AssertionError(error); }
            }).filter(Boolean::booleanValue).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fs_user", Integer.class)).isEqualTo(1);
    }

    @Test
    void expiredOldAndConsumedCodesAreRejected() {
        send("user@example.com");
        var old = registration("user@example.com", "user_1");
        clock.advance(Duration.ofSeconds(61));
        send("user@example.com");
        assertBusiness(() -> auth.register(old, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
        var current = registration("user@example.com", "user_1");
        auth.register(current, "127.0.0.1");
        assertBusiness(() -> auth.register(current, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
        send("expired@example.com");
        var expired = registration("expired@example.com", "user_2");
        clock.advance(Duration.ofSeconds(301));
        assertBusiness(() -> auth.register(expired, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
    }

    @Test
    void resendCooldownEmailAndIpLimitsArePersisted() {
        send("user@example.com");
        assertThatThrownBy(() -> send("user@example.com")).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(error.getRetryAfterSeconds()).isEqualTo(60);
        });
        for (int attempt = 1; attempt < 5; attempt++) {
            clock.advance(Duration.ofSeconds(61));
            send("user@example.com");
        }
        clock.advance(Duration.ofSeconds(61));
        assertBusiness(() -> send("user@example.com"), ErrorCode.RATE_LIMITED);
        clock.set(Instant.parse("2026-11-16T01:00:00Z"));
        for (int attempt = 0; attempt < 20; attempt++) { send("user" + attempt + "@example.com"); }
        assertBusiness(() -> send("iplimited@example.com"), ErrorCode.RATE_LIMITED);
    }

    @Test
    void loginCountsOnlyFailuresAndRegistrationHasIndependentRateLimits() {
        send("user@example.com");
        auth.register(registration("user@example.com", "user_1"), "127.0.0.1");
        for (int attempt = 0; attempt < 12; attempt++) { auth.login(login("user_1"), "127.0.0.1"); }
        var wrong = login("user_1");
        wrong.setPassword("incorrect-password");
        for (int attempt = 0; attempt < 10; attempt++) {
            assertBusiness(() -> auth.login(wrong, "127.0.0.1"), ErrorCode.INVALID_CREDENTIALS);
        }
        assertBusiness(() -> auth.login(wrong, "127.0.0.1"), ErrorCode.RATE_LIMITED);
        clock.advance(Duration.ofMinutes(15));
        auth.login(login("user_1"), "127.0.0.1");
        var invalid = registration("missing@example.com", "user_2");
        invalid.setVerificationCode("012345");
        for (int attempt = 0; attempt < 10; attempt++) {
            assertBusiness(() -> auth.register(invalid, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
        }
        assertBusiness(() -> auth.register(invalid, "127.0.0.1"), ErrorCode.RATE_LIMITED);
    }

    @Test
    void smtpFailureInvalidatesChallengeAndPendingChallengesCannotRegister() {
        doThrow(new MailSendException("synthetic SMTP failure")).when(mail).sendRegistration(anyString(), anyString(), any());
        assertBusiness(() -> send("user@example.com"), ErrorCode.MAIL_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT send_status FROM fs_email_verification WHERE email='user@example.com'", String.class)).isEqualTo("FAILED");
        var request = registration("user@example.com", "user_1");
        request.setVerificationCode("012345");
        assertBusiness(() -> auth.register(request, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
        jdbc.update("UPDATE fs_email_verification SET send_status='PENDING' WHERE email='user@example.com'");
        assertBusiness(() -> auth.register(request, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
    }

    @Test
    void overlappingSmtpCompletionsCannotOverwriteNewChallenge() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (entered.getCount() > 0) {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            codes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(mail).sendRegistration(anyString(), anyString(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = executor.submit(() -> {
                assertBusiness(() -> send("user@example.com"), ErrorCode.MAIL_UNAVAILABLE);
            });
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                clock.advance(Duration.ofSeconds(61));
                send("user@example.com");
                String challenge = jdbc.queryForObject("SELECT challenge_id FROM fs_email_verification WHERE email='user@example.com'", String.class);
                var current = registration("user@example.com", "user_1");
                release.countDown();
                old.get(10, TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("SELECT challenge_id FROM fs_email_verification WHERE email='user@example.com'", String.class)).isEqualTo(challenge);
                auth.register(current, "127.0.0.1");
            } finally { release.countDown(); }
        }
    }

    @Test
    void disablingAccountImmediatelyRejectsPreviouslyIssuedJwt() throws Exception {
        send("user@example.com");
        auth.register(registration("user@example.com", "user_1"), "127.0.0.1");
        String token = auth.login(login("user_1"), "127.0.0.1").accessToken();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        jdbc.update("UPDATE fs_user SET status='DISABLED' WHERE username='user_1'");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T00:00:00Z"));
        void set(Instant instant) { now.set(instant); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
