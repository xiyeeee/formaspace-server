package com.xiye.formaspace.generation.dto;

import com.xiye.formaspace.generation.enums.GenerationQuality;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateGenerationDTO {
    @NotBlank
    @Pattern(regexp = "[1-9][0-9]{0,18}")
    @DecimalMax("9223372036854775807")
    private String sourceFileId;
    @NotNull
    private GenerationQuality quality = GenerationQuality.STANDARD;
    private Boolean generateTexture = false;
    @Valid
    private AdvancedGenerationOptionsDTO advancedOptions;
}
