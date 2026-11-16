package com.xiye.formaspace.auth;

import com.xiye.formaspace.auth.config.AuthRateLimitProperties;
import com.xiye.formaspace.auth.entity.AuthRateLimitEntity;
import com.xiye.formaspace.auth.mapper.AuthRateLimitMapper;
import com.xiye.formaspace.auth.service.AuthRateLimiter;
import com.xiye.formaspace.auth.service.VerificationProtector;
import com.xiye.formaspace.common.exception.BusinessException;
import com.xiye.formaspace.common.exception.ErrorCode;
import com.xiye.formaspace.config.EmailCodeProperties;
import java.time.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthRateLimiterTests {
    @Test
    void overLimitFailureCountsCommitBefore429AndDimensionsUseHmac() {
        var mapper = mock(AuthRateLimitMapper.class);
        var protector = mock(VerificationProtector.class);
        when(protector.digest(anyString())).thenReturn("hashed-identity");
        when(mapper.attempts(anyString(), anyLong())).thenReturn(11);
        var transactions = new AuthServiceTests.TrackingTransactions();
        var clock = Clock.fixed(Instant.parse("2026-11-16T00:00:10Z"), ZoneOffset.UTC);
        var limiter = new AuthRateLimiter(mapper, protector, new EmailCodeProperties(),
                new AuthRateLimitProperties(), clock, transactions);
        assertThatThrownBy(() -> limiter.loginFailed("user_1", "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(error.getRetryAfterSeconds()).isEqualTo(890);
                });
        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isZero();
        verify(mapper, times(2)).hit(eq("hashed-identity"), anyLong(), any(), any());
        verify(protector).digest("rate:login:account:user_1");
        verify(protector).digest("rate:login:ip:127.0.0.1");
    }

    @Test
    void preciseCooldownRejectsUntilExpiryAndDoesNotIncrementHourlyCounters() {
        var mapper = mock(AuthRateLimitMapper.class);
        var protector = mock(VerificationProtector.class);
        when(protector.digest(anyString())).thenReturn("hashed-identity");
        var now = Instant.parse("2026-11-16T00:00:00Z");
        var cooldown = new AuthRateLimitEntity();
        cooldown.setExpiresAt(now.plusMillis(1500));
        when(mapper.lockCooldown("hashed-identity")).thenReturn(cooldown);
        var transactions = new AuthServiceTests.TrackingTransactions();
        var limiter = new AuthRateLimiter(mapper, protector, new EmailCodeProperties(),
                new AuthRateLimitProperties(), Clock.fixed(now, ZoneOffset.UTC), transactions);
        assertThatThrownBy(() -> limiter.emailSend("user@example.com", "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(error.getRetryAfterSeconds()).isEqualTo(2);
                });
        verify(mapper, never()).hit(anyString(), anyLong(), any(), any());
        assertThat(transactions.commits).isEqualTo(1);
    }
}
