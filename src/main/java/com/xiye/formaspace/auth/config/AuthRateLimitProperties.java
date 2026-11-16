package com.xiye.formaspace.auth.config;

import java.time.Duration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("formaspace.auth-rate-limit")
public class AuthRateLimitProperties {
    @NotNull
    private Duration loginWindow = Duration.ofMinutes(15);
    @Min(1)
    private int loginAccountFailures = 10;
    @Min(1)
    private int loginIpFailures = 50;
    @NotNull
    private Duration registerWindow = Duration.ofMinutes(15);
    @Min(1)
    private int registerEmailAttempts = 10;
    @Min(1)
    private int registerIpAttempts = 30;
}
