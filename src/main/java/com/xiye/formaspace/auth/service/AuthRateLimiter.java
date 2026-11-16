package com.xiye.formaspace.auth.service;

import com.xiye.formaspace.auth.config.AuthRateLimitProperties;
import com.xiye.formaspace.auth.mapper.AuthRateLimitMapper;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.config.EmailCodeProperties;
import java.time.*;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@EnableConfigurationProperties(AuthRateLimitProperties.class)
public class AuthRateLimiter {
    private final AuthRateLimitMapper mapper;
    private final VerificationProtector protector;
    private final EmailCodeProperties email;
    private final AuthRateLimitProperties limits;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AuthRateLimiter(AuthRateLimitMapper mapper, VerificationProtector protector, EmailCodeProperties email,
            AuthRateLimitProperties limits, Clock clock, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.protector = protector;
        this.email = email;
        this.limits = limits;
        this.clock = clock;
        if (limits.getLoginWindow().toSeconds() < 1 || limits.getRegisterWindow().toSeconds() < 1) {
            throw new IllegalStateException("鉴权限流窗口必须至少 1 秒");
        }
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void emailSend(String identity, String ip) {
        Long retry = transactions.execute(status -> {
            var now = clock.instant();
            String cooldownKey = protector.digest("rate:send:REGISTER:cooldown:" + identity);
            mapper.ensureCooldown(cooldownKey, now);
            var cooldown = mapper.lockCooldown(cooldownKey);
            if (cooldown.getExpiresAt().isAfter(now)) {
                return remaining(now, cooldown.getExpiresAt());
            }
            mapper.reserveCooldown(cooldown.getId(), now.plus(email.getCooldown()), now);
            return hit(List.of(
                    new Rule("send:REGISTER:email:" + identity, email.getMaxSendsPerEmailPerHour(), Duration.ofHours(1)),
                    new Rule("send:ip:" + ip, email.getMaxSendsPerIpPerHour(), Duration.ofHours(1))));
        });
        rejectIfLimited(retry);
    }

    public void registration(String identity, String ip) {
        rejectIfLimited(transactions.execute(status -> hit(List.of(
                new Rule("register:email:" + identity, limits.getRegisterEmailAttempts(), limits.getRegisterWindow()),
                new Rule("register:ip:" + ip, limits.getRegisterIpAttempts(), limits.getRegisterWindow())))));
    }

    public void checkLogin(String account, String ip) {
        long retry = loginRules(account, ip).stream().mapToLong(rule -> {
            var now = clock.instant();
            long start = windowStart(now, rule.window());
            Integer attempts = mapper.attempts(protector.digest("rate:" + rule.dimension()), start);
            return attempts != null && attempts >= rule.maximum()
                    ? remaining(now, Instant.ofEpochSecond(start + rule.window().toSeconds())) : 0;
        }).max().orElse(0);
        rejectIfLimited(retry);
    }

    public void loginFailed(String account, String ip) {
        rejectIfLimited(transactions.execute(status -> hit(loginRules(account, ip))));
    }

    private List<Rule> loginRules(String account, String ip) {
        return List.of(new Rule("login:account:" + account, limits.getLoginAccountFailures(), limits.getLoginWindow()),
                new Rule("login:ip:" + ip, limits.getLoginIpFailures(), limits.getLoginWindow()));
    }

    private long hit(List<Rule> rules) {
        var now = clock.instant();
        return rules.stream().mapToLong(rule -> {
            long start = windowStart(now, rule.window());
            var expires = Instant.ofEpochSecond(start + rule.window().toSeconds());
            String key = protector.digest("rate:" + rule.dimension());
            mapper.hit(key, start, expires, now);
            Integer attempts = mapper.attempts(key, start);
            return attempts != null && attempts > rule.maximum() ? remaining(now, expires) : 0;
        }).max().orElse(0);
    }

    private long windowStart(Instant now, Duration duration) {
        long seconds = duration.toSeconds();
        return now.getEpochSecond() - Math.floorMod(now.getEpochSecond(), seconds);
    }

    private long remaining(Instant now, Instant expires) {
        return Math.max(1, (Duration.between(now, expires).toMillis() + 999) / 1000);
    }

    private void rejectIfLimited(Long retry) {
        if (retry != null && retry > 0) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, retry);
        }
    }

    private record Rule(String dimension, int maximum, Duration window) {}
}
