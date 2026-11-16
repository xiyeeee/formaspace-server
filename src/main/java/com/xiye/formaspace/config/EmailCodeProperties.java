package com.xiye.formaspace.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration only. Validate secrets/SMTP when the email feature is implemented and enabled. */
@Getter
@Setter
@ConfigurationProperties("formaspace.email-code")
public class EmailCodeProperties {
    private boolean enabled = false;
    private String hmacKey;
    private String from;
    private Duration ttl = Duration.ofMinutes(10);
    private Duration cooldown = Duration.ofSeconds(60);
    private int maxFailedAttempts = 5;
    private int maxSendsPerEmailPerHour = 5;
    private int maxSendsPerIpPerHour = 20;
}
