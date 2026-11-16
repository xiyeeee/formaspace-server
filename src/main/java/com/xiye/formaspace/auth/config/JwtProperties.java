package com.xiye.formaspace.auth.config;

import java.time.Duration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("formaspace.jwt")
public class JwtProperties {
    @NotBlank
    private String secret;
    @NotBlank
    private String issuer = "formaspace-server";
    @NotBlank
    private String audience = "formaspace-web";
    @NotNull
    private Duration accessTokenTtl = Duration.ofHours(2);
    @NotNull
    private Duration clockSkew = Duration.ofSeconds(30);
}
