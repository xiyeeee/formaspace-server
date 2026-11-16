package com.xiye.formaspace.generation.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdvancedGenerationOptionsDTO {
    private Boolean removeBackground = true;
    @Min(0)
    @Max(2147483647)
    private Long seed;
    @Positive
    private Integer inferenceSteps;
    private Double guidanceScale;
    @Positive
    private Integer octreeResolution;
}
