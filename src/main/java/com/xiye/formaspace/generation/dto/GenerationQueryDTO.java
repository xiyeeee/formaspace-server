package com.xiye.formaspace.generation.dto;

import com.xiye.formaspace.common.response.PageQuery;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GenerationQueryDTO extends PageQuery {
    private GenerationStatus status;
}
