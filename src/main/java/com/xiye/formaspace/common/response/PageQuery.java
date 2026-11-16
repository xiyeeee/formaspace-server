package com.xiye.formaspace.common.response;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PageQuery {
    @Min(1)
    private long page = 1;
    @Min(1)
    @Max(100)
    private long size = 20;

    public <T> Page<T> toPage() {
        return Page.of(page, size);
    }
}
