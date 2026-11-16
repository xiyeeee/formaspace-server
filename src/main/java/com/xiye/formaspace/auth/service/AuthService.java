package com.xiye.formaspace.auth.service;

import com.xiye.formaspace.auth.dto.*;
import com.xiye.formaspace.auth.enums.*;
import com.xiye.formaspace.auth.mapper.EmailVerificationMapper;
import com.xiye.formaspace.auth.vo.LoginVO;
import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import com.xiye.formaspace.user.vo.UserVO;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.xiye.formaspace.config.EmailCodeProperties;

@Service
public class AuthService {
    private final UserMapper users;
    private final EmailVerificationMapper verification;
    private final VerificationProtector protector;
    private final AuthRateLimiter limiter;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final EmailCodeProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String dummyHash;

    public AuthService(UserMapper users, EmailVerificationMapper verification, VerificationProtector protector,
            AuthRateLimiter limiter, PasswordEncoder passwords, TokenService tokens, EmailCodeProperties properties,
            Clock clock, PlatformTransactionManager manager) {
        this.users = users;
        this.verification = verification;
        this.protector = protector;
        this.limiter = limiter;
        this.passwords = passwords;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public UserVO register(RegisterDTO request, String ip) {
        limiter.registration(request.getEmail(), ip);
        // Expensive hashing happens before acquiring the challenge row lock.
        String hash = passwords.encode(request.getPassword());
        Registration outcome;
        try {
            outcome = transactions.execute(status -> registerWithinTransaction(request, hash));
        } catch (DuplicateKeyException exception) {
            // The entire callback rolled back, including a possible challenge consumption.
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        // Invalid-code outcomes are thrown only AFTER commit so failed_attempts is durable.
        if (outcome.error() != null) {
            throw new BusinessException(outcome.error());
        }
        return outcome.user();
    }

    private Registration registerWithinTransaction(RegisterDTO request, String hash) {
        var slot = verification.lockRegistration(request.getEmail());
        var now = clock.instant();
        if (slot == null || slot.getSendStatus() != EmailSendStatus.SENT || slot.getChallengeId() == null
                || slot.getConsumedAt() != null || !slot.getExpiresAt().isAfter(now)
                || slot.getFailedAttempts() >= properties.getMaxFailedAttempts()) {
            return new Registration(null, ErrorCode.VERIFICATION_INVALID);
        }
        String supplied = protector.codeHash(request.getEmail(), VerificationPurpose.REGISTER,
                slot.getChallengeId(), request.getVerificationCode());
        if (!protector.matches(slot.getCodeHash(), supplied)) {
            verification.recordFailure(slot.getId(), now);
            return new Registration(null, ErrorCode.VERIFICATION_INVALID);
        }
        var user = new UserEntity();
        user.setEmail(request.getEmail());
        user.setUsername(request.getUsername());
        user.setNickname(request.getUsername());
        user.setPasswordHash(hash);
        user.setStatus(UserStatus.ACTIVE);
        users.insert(user);
        if (verification.consume(slot.getId(), now) != 1) {
            throw new IllegalStateException("Challenge consumption conflict");
        }
        return new Registration(UserVO.from(user), null);
    }

    public LoginVO login(LoginDTO request, String ip) {
        String account = request.getAccount();
        limiter.checkLogin(account, ip);
        var user = account.contains("@") ? users.findByEmail(account) : users.findByUsername(account);
        boolean matches = matchesPassword(request.getPassword(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches || user.getStatus() != UserStatus.ACTIVE) {
            limiter.loginFailed(account, ip);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (users.touchLastLogin(user.getId(), clock.instant()) != 1) {
            limiter.loginFailed(account, ip);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return tokens.issue(UserVO.from(user));
    }

    private boolean matchesPassword(String supplied, String stored) {
        try {
            return passwords.matches(supplied, stored);
        } catch (IllegalArgumentException exception) {
            passwords.matches(supplied, dummyHash);
            return false;
        }
    }

    private record Registration(UserVO user, ErrorCode error) {}
}
