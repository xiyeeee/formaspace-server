package com.xiye.formaspace.generation.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum GenerationStatus {
    PENDING("PENDING"),
    SUBMITTING("SUBMITTING"),
    PROCESSING("PROCESSING"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED");

    @EnumValue
    private final String value;

    GenerationStatus(String value) {
        this.value = value;
    }
}
