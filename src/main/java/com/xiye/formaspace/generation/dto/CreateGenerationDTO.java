package com.xiye.formaspace.generation.dto;

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
}
