package com.xiye.formaspace.asset.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RenameAssetDTO {
    @NotBlank
    @Size(max = 100)
    private String name;
}
