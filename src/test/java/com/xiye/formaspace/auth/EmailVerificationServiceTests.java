package com.xiye.formaspace.auth;

import com.xiye.formaspace.auth.entity.EmailVerificationEntity;
import com.xiye.formaspace.auth.enums.VerificationPurpose;
import com.xiye.formaspace.auth.mapper.EmailVerificationMapper;
import com.xiye.formaspace.auth.service.*;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.config.EmailCodeProperties;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailVerificationServiceTests {
    private final EmailVerificationMapper mapper = mock(EmailVerificationMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final VerificationProtector protector = mock(VerificationProtector.class);
    private final VerificationMailSender mail = mock(VerificationMailSender.class);
    private final AuthRateLimiter limiter = mock(AuthRateLimiter.class);
    private final EmailCodeProperties properties = new EmailCodeProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);
    private EmailVerificationService service;

    @BeforeEach
    void setup() {
        properties.setEnabled(true);
        var slot = new EmailVerificationEntity();
        slot.setId(1L);
        slot.setSentAt(clock.instant().minusSeconds(60));
        when(mapper.lockRegistration("user@example.com")).thenReturn(slot);
        when(protector.generate()).thenReturn("012345");
        when(protector.codeHash(anyString(), any(), anyString(), anyString())).thenReturn("a".repeat(64));
        when(mapper.finishSend(anyString(), anyString(), anyString(), any())).thenReturn(1);
        service = new EmailVerificationService(mapper, users, protector, mail, limiter, properties,
                clock, new AuthServiceTests.TrackingTransactions());
    }

    @Test
    void smtpRunsOutsideTransactionAndChallengeIsConditionallyConfirmed() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(mail).sendRegistration(anyString(), anyString(), any());
        assertThat(service.send("user@example.com", VerificationPurpose.REGISTER, "127.0.0.1").retryAfterSeconds()).isEqualTo(60);
        var order = inOrder(mapper, mail);
        order.verify(mapper).reserve(eq(1L), anyString(), anyString(), any(), any());
        order.verify(mail).sendRegistration("user@example.com", "012345", Duration.ofMinutes(5));
        order.verify(mapper).finishSend(eq("user@example.com"), anyString(), eq("SENT"), any());
    }

    @Test
    void smtpFailureMarksOnlyMatchingChallengeFailedAndReturns503() {
        doThrow(new MailSendException("secret SMTP password")).when(mail).sendRegistration(anyString(), anyString(), any());
        assertThatThrownBy(() -> service.send("user@example.com", VerificationPurpose.REGISTER, "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.MAIL_UNAVAILABLE));
        verify(mapper).finishSend(eq("user@example.com"), anyString(), eq("FAILED"), any());
        verify(mapper, never()).finishSend(anyString(), anyString(), eq("SENT"), any());
    }

    @Test
    void staleSmtpCompletionCannotClaimSuccessOrOverwriteNewChallenge() {
        when(mapper.finishSend(anyString(), anyString(), eq("SENT"), any())).thenReturn(0);
        assertThatThrownBy(() -> service.send("user@example.com", VerificationPurpose.REGISTER, "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.MAIL_UNAVAILABLE));
    }

    @Test
    void registeredEmailGetsSameResponseAndIsRateLimitedWithoutDelivery() {
        when(users.findByEmail("user@example.com")).thenReturn(new UserEntity());
        var result = service.send("user@example.com", VerificationPurpose.REGISTER, "127.0.0.1");
        assertThat(result.retryAfterSeconds()).isEqualTo(60);
        verify(limiter).emailSend("user@example.com", "127.0.0.1");
        verifyNoInteractions(mail, mapper);
    }

    @Test
    void disabledMailAndUnimplementedPurposeAreExplicitlyRejected() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.send("user@example.com", VerificationPurpose.REGISTER, "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.MAIL_UNAVAILABLE));
        assertThatThrownBy(() -> service.send("user@example.com", VerificationPurpose.RESET_PASSWORD, "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        verifyNoInteractions(mail, mapper, users);
    }
}
