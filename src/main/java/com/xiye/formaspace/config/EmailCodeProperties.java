package com.xiye.formaspace.config;

import java.time.Duration;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SMTP is optional; the separate HMAC key is always required for database rate-limit identities. */
@Getter
@Setter
@Validated
@ConfigurationProperties("formaspace.email-code")
public class EmailCodeProperties {
    private boolean enabled = false;
    @NotBlank
    private String hmacKey;
    private String from;
    @NotNull
    private Duration ttl = Duration.ofMinutes(5);
    @NotNull
    private Duration cooldown = Duration.ofSeconds(60);
    @Min(1)
    private int maxFailedAttempts = 5;
    @Min(1)
    private int maxSendsPerEmailPerHour = 5;
    @Min(1)
    private int maxSendsPerIpPerHour = 20;
}
