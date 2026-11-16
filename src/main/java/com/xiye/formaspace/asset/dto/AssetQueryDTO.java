package com.xiye.formaspace.asset.dto;

import com.xiye.formaspace.common.response.PageQuery;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetQueryDTO extends PageQuery {
    @Size(max = 100)
    private String keyword;
}
