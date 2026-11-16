package com.xiye.formaspace.config;

import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.enums.GenerationMode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@EnableConfigurationProperties(GenerationProperties.class)
public class GenerationConfig {
    public GenerationConfig(GenerationProperties properties, Environment environment) {
        if (properties.getMode() == GenerationMode.MOCK && (environment.acceptsProfiles(Profiles.of("prod"))
                || !environment.acceptsProfiles(Profiles.of("local", "dev", "test")))) {
            throw new IllegalStateException("MOCK 仅允许 local/dev/test，生产环境禁止启用");
        }
        if (properties.getMockStageDelay().isNegative() || properties.getMinimumSteps() > properties.getMaximumSteps()
                || !Double.isFinite(properties.getMinimumGuidance()) || !Double.isFinite(properties.getMaximumGuidance())
                || properties.getMinimumGuidance() > properties.getMaximumGuidance()) {
            throw new IllegalStateException("生成参数 profile 或模拟延时配置不合法");
        }
    }
}
