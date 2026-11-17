package com.xiye.formaspace.generation.config;

import java.net.URI;
import java.time.Duration;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PythonRealtimeProperties {
    @NotNull private URI baseUrl = URI.create("http://127.0.0.1:8001");
    private URI wsBaseUrl;
    private String token;
    @NotNull private Duration connectTimeout = Duration.ofSeconds(5);
    @NotNull private Duration submitTimeout = Duration.ofSeconds(20);
    @NotNull private Duration snapshotTimeout = Duration.ofSeconds(5);
    @NotNull private Duration downloadTimeout = Duration.ofMinutes(2);
    @NotNull private Duration taskDeadline = Duration.ofMinutes(30);
    @NotNull private Duration reconnectInitialDelay = Duration.ofSeconds(2);
    @NotNull private Duration reconnectMaxDelay = Duration.ofSeconds(30);
    @NotNull private Duration reconciliationInterval = Duration.ofSeconds(10);
    @Min(1) @Max(8) private int maximumConnections = 1;
    @Min(1) @Max(16) private int maximumSubmissionAttempts = 4;
    @Min(262144) @Max(8388608) private int maximumMessageBytes = 2097152;
    @Min(1) private long maximumDownloadBytes = 104857600;
    @Min(1) @Max(64) private int eventQueueCapacity = 16;
    @Min(2) @Max(50) private int steps = 30;
    @DecimalMin("0") @DecimalMax("10") private double guidanceScale = 5;
    @Min(128) @Max(256) private int finalResolution = 256;
    @Min(1000) @Max(8000) private int numChunks = 4000;
    private boolean previewEnabled = true;
    @Min(1) @Max(49) private int previewStep = 24;
    @Min(64) @Max(128) private int previewResolution = 128;
    @Min(1) @Max(5000) private int pointCount = 5000;
    @NotBlank private String modelId = "tencent/Hunyuan3D-2mini";
}
