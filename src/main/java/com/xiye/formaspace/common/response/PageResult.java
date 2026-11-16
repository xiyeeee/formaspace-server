package com.xiye.formaspace.common.response;

import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;
import java.util.function.Function;

public record PageResult<T>(List<T> records, long total, long page, long size) {
    public static <E, T> PageResult<T> from(IPage<E> source, Function<E, T> adapter) {
        return new PageResult<>(source.getRecords().stream().map(adapter).toList(),
                source.getTotal(), source.getCurrent(), source.getSize());
    }
}
