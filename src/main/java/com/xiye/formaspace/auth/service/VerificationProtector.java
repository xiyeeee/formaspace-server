package com.xiye.formaspace.auth.service;

import com.xiye.formaspace.auth.enums.VerificationPurpose;
import com.xiye.formaspace.auth.security.SecretKeys;
import com.xiye.formaspace.config.EmailCodeProperties;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class VerificationProtector {
    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();

    public VerificationProtector(EmailCodeProperties properties) {
        secret = SecretKeys.decode(properties.getHmacKey(), "AUTH_CODE_HMAC_KEY");
    }

    public String generate() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    public String codeHash(String email, VerificationPurpose purpose, String challenge, String code) {
        return digest("code\n" + email + "\n" + purpose.name() + "\n" + challenge + "\n" + code);
    }

    public String digest(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法初始化验证码保护");
        }
    }

    public boolean matches(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }
}
