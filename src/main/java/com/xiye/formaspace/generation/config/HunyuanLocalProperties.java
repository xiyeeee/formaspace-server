package com.xiye.formaspace.generation.config;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HunyuanLocalProperties {
    @NotNull
    private URI baseUrl = URI.create("http://127.0.0.1:7860");
    @Pattern(regexp = "[a-zA-Z0-9_]+")
    private String apiName = "shape_generation";
    @Pattern(regexp = "/[a-zA-Z0-9_/]+")
    private String apiPrefix = "/gradio_api";
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(5);
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(60);
    @NotNull
    private Duration taskTimeout = Duration.ofMinutes(30);
    @NotNull
    private Duration retryInterval = Duration.ofSeconds(5);
    @Min(0) @Max(10)
    private int maximumRetries = 3;
    @NotBlank
    private String modelId = "tencent/Hunyuan3D-2mini";
    @NotBlank
    private String subfolder = "hunyuan3d-dit-v2-mini";
    @NotBlank
    private String profileId = "hunyuan-mini-mps-candidate-v1";
    private String authorization;
    @Valid @NotNull
    private GenerationProperties.Preset standardPreset = new GenerationProperties.Preset(5,5,128);
    @Min(1000) @Max(5000000)
    private int numChunks = 1000;
}
