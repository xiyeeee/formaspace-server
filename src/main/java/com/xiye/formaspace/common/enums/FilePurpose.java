package com.xiye.formaspace.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum FilePurpose {
    SOURCE_IMAGE("SOURCE_IMAGE"),
    MODEL("MODEL"),
    THUMBNAIL("THUMBNAIL");

    @EnumValue
    private final String value;

    FilePurpose(String value) {
        this.value = value;
    }
}
