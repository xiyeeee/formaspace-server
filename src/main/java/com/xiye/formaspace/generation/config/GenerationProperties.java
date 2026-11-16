package com.xiye.formaspace.generation.config;

import com.xiye.formaspace.generation.enums.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("formaspace.generation")
public class GenerationProperties {
    @NotNull
    private GenerationMode mode = GenerationMode.DISABLED;
    @Min(1)
    private int maxActivePerUser = 1;
    @Min(1)
    private int mockConcurrency = 1;
    @NotNull
    private Duration mockStageDelay = Duration.ofSeconds(2);
    private Path mockDemoGlb;
    @NotBlank
    private String profileId = "mock-v1";
    @Min(1)
    private int minimumSteps = 1;
    @Min(1)
    private int maximumSteps = 20;
    @DecimalMin("0.0")
    private double minimumGuidance = 0;
    @DecimalMin("0.0")
    private double maximumGuidance = 10;
    @NotEmpty
    private List<@Min(1) Integer> octreeResolutions = List.of(128, 256);
    @Min(1)
    private long maximumVoxelSteps = 268_435_456;
    @Min(1)
    private int numChunks = 8000;
    @Valid
    @NotEmpty
    private Map<GenerationQuality, Preset> qualityPresets = new LinkedHashMap<>(Map.of(
            GenerationQuality.STANDARD, new Preset(5, 5.0, 128),
            GenerationQuality.HIGH, new Preset(10, 5.0, 256)));

    public record Preset(@Min(1) int inferenceSteps, @DecimalMin("0.0") double guidanceScale,
            @Min(1) int octreeResolution) {}
}
