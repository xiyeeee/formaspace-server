package com.xiye.formaspace.auth;

import com.xiye.formaspace.auth.enums.VerificationPurpose;
import com.xiye.formaspace.auth.security.SecretKeys;
import com.xiye.formaspace.auth.service.VerificationProtector;
import com.xiye.formaspace.config.EmailCodeProperties;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class VerificationProtectorTests {
    @Test
    void digestBindsEmailPurposeChallengeCodeAndSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        var properties = new EmailCodeProperties();
        properties.setHmacKey(Base64.getEncoder().encodeToString(secret));
        var protector = new VerificationProtector(properties);
        String expected = protector.codeHash("user@example.com", VerificationPurpose.REGISTER, "a", "012345");
        assertThat(expected).matches("[a-f0-9]{64}").doesNotContain("012345");
        assertThat(protector.matches(expected, protector.codeHash("user@example.com", VerificationPurpose.REGISTER, "a", "012345"))).isTrue();
        assertThat(protector.matches(expected, protector.codeHash("other@example.com", VerificationPurpose.REGISTER, "a", "012345"))).isFalse();
        assertThat(protector.matches(expected, protector.codeHash("user@example.com", VerificationPurpose.RESET_PASSWORD, "a", "012345"))).isFalse();
        assertThat(protector.matches(expected, protector.codeHash("user@example.com", VerificationPurpose.REGISTER, "b", "012345"))).isFalse();
        assertThat(protector.matches(expected, protector.codeHash("user@example.com", VerificationPurpose.REGISTER, "a", "999999"))).isFalse();
        assertThat(protector.generate()).matches("[0-9]{6}");
    }

    @Test
    void emptyMalformedAndShortSecretsFailWithoutEchoingInput() {
        for (String value : new String[] {"", "invalid-secret!", Base64.getEncoder().encodeToString(new byte[16])}) {
            assertThatThrownBy(() -> SecretKeys.decode(value, "JWT_SECRET"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET")
                    .satisfies(error -> assertThat(error.getCause()).isNull());
        }
    }
}
