package com.xiye.formaspace.config;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({CorsProperties.class, EmailCodeProperties.class})
public class WebConfig implements WebMvcConfigurer {
    private final CorsProperties cors;

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (cors.getAllowedOrigins().stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("CORS 必须配置明确的来源，不允许通配符");
        }
        registry.addMapping("/api/**")
                .allowedOrigins(cors.getAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept", "Idempotency-Key")
                .exposedHeaders("Retry-After", "Content-Disposition")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
