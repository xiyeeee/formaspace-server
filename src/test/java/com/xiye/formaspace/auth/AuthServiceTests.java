package com.xiye.formaspace.auth;

import com.xiye.formaspace.auth.dto.*;
import com.xiye.formaspace.auth.entity.EmailVerificationEntity;
import com.xiye.formaspace.auth.enums.*;
import com.xiye.formaspace.auth.mapper.EmailVerificationMapper;
import com.xiye.formaspace.auth.service.*;
import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.config.*;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import java.security.SecureRandom;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTests {
    private final UserMapper users = mock(UserMapper.class);
    private final EmailVerificationMapper verification = mock(EmailVerificationMapper.class);
    private final AuthRateLimiter limiter = mock(AuthRateLimiter.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final TokenService tokens = mock(TokenService.class);
    private final EmailCodeProperties properties = new EmailCodeProperties();
    private final TrackingTransactions transactions = new TrackingTransactions();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);
    private VerificationProtector protector;
    private AuthService service;
    private RegisterDTO request;
    private EmailVerificationEntity slot;

    @BeforeEach
    void setup() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        properties.setHmacKey(Base64.getEncoder().encodeToString(key));
        protector = new VerificationProtector(properties);
        when(passwords.encode(anyString())).thenReturn("{test}hash");
        service = new AuthService(users, verification, protector, limiter, passwords, tokens, properties, clock, transactions);
        request = new RegisterDTO();
        request.setEmail("user@example.com");
        request.setUsername("user_1");
        request.setPassword(" password ");
        request.setConfirmPassword(" password ");
        request.setVerificationCode("012345");
        slot = new EmailVerificationEntity();
        slot.setId(1L);
        slot.setEmail(request.getEmail());
        slot.setPurpose(VerificationPurpose.REGISTER);
        slot.setChallengeId("challenge-one");
        slot.setSendStatus(EmailSendStatus.SENT);
        slot.setExpiresAt(clock.instant().plusSeconds(300));
        slot.setFailedAttempts(0);
        slot.setCodeHash(protector.codeHash(request.getEmail(), VerificationPurpose.REGISTER, "challenge-one", "012345"));
        when(verification.lockRegistration(request.getEmail())).thenReturn(slot);
        when(verification.consume(eq(1L), any())).thenReturn(1);
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(2L);
            user.setCreatedAt(clock.instant());
            return 1;
        }).when(users).insert(any(UserEntity.class));
    }

    @Test
    void successfulRegistrationInsertsHashThenConsumesWithinOneCommit() {
        var result = service.register(request, "127.0.0.1");
        assertThat(result.id()).isEqualTo("2");
        assertThat(result.nickname()).isEqualTo("user_1");
        verify(passwords).encode(" password ");
        var order = inOrder(users, verification);
        order.verify(verification).lockRegistration(request.getEmail());
        order.verify(users).insert(argThat((UserEntity user) -> user.getPasswordHash().equals("{test}hash")));
        order.verify(verification).consume(eq(1L), any());
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
        verifyNoInteractions(tokens);
    }

    @Test
    void incorrectCodeCommitsFailureBeforeReturning400() {
        request.setVerificationCode("000000");
        assertBusiness(() -> service.register(request, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
        verify(verification).recordFailure(eq(1L), any());
        verify(users, never()).insert(any(UserEntity.class));
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
    }

    @Test
    void duplicateUserRollsBackAndDoesNotConsumeChallenge() {
        doThrow(new DuplicateKeyException("sensitive SQL")).when(users).insert(any(UserEntity.class));
        assertBusiness(() -> service.register(request, "127.0.0.1"), ErrorCode.CONFLICT);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
        verify(verification, never()).consume(anyLong(), any());
    }

    @Test
    void consumptionConflictRollsBackUserInsert() {
        when(verification.consume(eq(1L), any())).thenReturn(0);
        assertThatThrownBy(() -> service.register(request, "127.0.0.1")).isInstanceOf(IllegalStateException.class);
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test
    void rejectsPendingFailedExpiredConsumedAndExhaustedChallenges() {
        for (EmailSendStatus state : new EmailSendStatus[] {EmailSendStatus.PENDING, EmailSendStatus.FAILED}) {
            slot.setSendStatus(state);
            assertBusiness(() -> service.register(request, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
        }
        slot.setSendStatus(EmailSendStatus.SENT);
        slot.setExpiresAt(clock.instant());
        assertBusiness(() -> service.register(request, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
        slot.setExpiresAt(clock.instant().plusSeconds(300));
        slot.setConsumedAt(clock.instant());
        assertBusiness(() -> service.register(request, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
        slot.setConsumedAt(null);
        slot.setFailedAttempts(5);
        assertBusiness(() -> service.register(request, "127.0.0.1"), ErrorCode.VERIFICATION_INVALID);
        verify(users, never()).insert(any(UserEntity.class));
    }

    @Test
    void unknownAccountStillPerformsPasswordComparison() {
        var login = new LoginDTO();
        login.setAccount("missing");
        login.setPassword(" password ");
        assertBusiness(() -> service.login(login, "127.0.0.1"), ErrorCode.INVALID_CREDENTIALS);
        verify(passwords).matches(" password ", "{test}hash");
        verify(limiter).loginFailed("missing", "127.0.0.1");
    }

    @Test
    void disabledAccountStillPerformsPasswordComparison() {
        var login = new LoginDTO();
        login.setAccount("USER@example.com");
        login.setPassword(" password ");
        var user = new UserEntity();
        user.setPasswordHash("stored-hash");
        user.setStatus(UserStatus.DISABLED);
        when(users.findByEmail("user@example.com")).thenReturn(user);
        when(passwords.matches(" password ", "stored-hash")).thenReturn(true);
        assertBusiness(() -> service.login(login, "127.0.0.1"), ErrorCode.INVALID_CREDENTIALS);
        verify(passwords).matches(" password ", "stored-hash");
        verifyNoInteractions(tokens);
    }

    @Test
    void maturePasswordEncoderIncludesAlgorithmAndSalt() {
        var encoder = new SecurityConfig().passwordEncoder();
        String first = encoder.encode(" password ");
        String second = encoder.encode(" password ");
        assertThat(first).startsWith("{pbkdf2@SpringSecurity_v5_8}").isNotEqualTo(second);
        assertThat(encoder.matches(" password ", first)).isTrue();
        assertThat(encoder.matches("password", first)).isFalse();
    }

    private void assertBusiness(Runnable action, ErrorCode error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(error));
    }

    static class TrackingTransactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        @Override
        protected Object doGetTransaction() { return new Object(); }
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override
        protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
