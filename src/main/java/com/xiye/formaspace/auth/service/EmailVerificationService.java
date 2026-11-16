package com.xiye.formaspace.auth.service;

import com.xiye.formaspace.auth.enums.VerificationPurpose;
import com.xiye.formaspace.auth.mapper.EmailVerificationMapper;
import com.xiye.formaspace.auth.vo.EmailCodeSendVO;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.config.EmailCodeProperties;
import com.xiye.formaspace.user.mapper.UserMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailVerificationService {
    private final EmailVerificationMapper mapper;
    private final UserMapper users;
    private final VerificationProtector protector;
    private final VerificationMailSender mail;
    private final AuthRateLimiter limiter;
    private final EmailCodeProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public EmailVerificationService(EmailVerificationMapper mapper, UserMapper users, VerificationProtector protector,
            VerificationMailSender mail, AuthRateLimiter limiter, EmailCodeProperties properties,
            Clock clock, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.users = users;
        this.protector = protector;
        this.mail = mail;
        this.limiter = limiter;
        this.properties = properties;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        if (properties.getTtl().toSeconds() < 1 || properties.getCooldown().toSeconds() < 1) {
            throw new IllegalStateException("验证码有效期和冷却时间必须至少 1 秒");
        }
    }

    public EmailCodeSendVO send(String email, VerificationPurpose purpose, String ip) {
        if (purpose != VerificationPurpose.REGISTER) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.MAIL_UNAVAILABLE);
        }
        limiter.emailSend(email, ip);
        if (users.findByEmail(email) != null) {
            return new EmailCodeSendVO(properties.getCooldown().toSeconds());
        }
        var delivery = transactions.execute(status -> reserve(email));
        if (delivery.retryAfter() > 0) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, delivery.retryAfter());
        }
        // No transaction or row lock is held across SMTP. Only this challenge may be marked sent/failed.
        try {
            mail.sendRegistration(email, delivery.code(), properties.getTtl());
        } catch (MailException exception) {
            finish(email, delivery.challenge(), "FAILED");
            throw new BusinessException(ErrorCode.MAIL_UNAVAILABLE);
        }
        if (finish(email, delivery.challenge(), "SENT") != 1) {
            throw new BusinessException(ErrorCode.MAIL_UNAVAILABLE);
        }
        return new EmailCodeSendVO(properties.getCooldown().toSeconds());
    }

    private Delivery reserve(String email) {
        var now = clock.instant();
        mapper.ensureSlot(email, now, now.minus(properties.getCooldown()));
        var slot = mapper.lockRegistration(email);
        var availableAt = slot.getSentAt().plus(properties.getCooldown());
        if (availableAt.isAfter(now)) {
            long wait = Math.max(1, (Duration.between(now, availableAt).toMillis() + 999) / 1000);
            return new Delivery(null, null, wait);
        }
        String code = protector.generate();
        // Prevent an accidental six-digit repeat from making the previous mailed code usable after resend.
        while (slot.getChallengeId() != null && protector.matches(slot.getCodeHash(),
                protector.codeHash(email, VerificationPurpose.REGISTER, slot.getChallengeId(), code))) {
            code = protector.generate();
        }
        String challenge = UUID.randomUUID().toString();
        mapper.reserve(slot.getId(), challenge, protector.codeHash(email, VerificationPurpose.REGISTER, challenge, code),
                now, now.plus(properties.getTtl()));
        return new Delivery(challenge, code, 0);
    }

    private int finish(String email, String challenge, String state) {
        return transactions.execute(status -> mapper.finishSend(email, challenge, state, clock.instant()));
    }

    private record Delivery(String challenge, String code, long retryAfter) {
        @Override
        public String toString() {
            return "Delivery[redacted]";
        }
    }
}
