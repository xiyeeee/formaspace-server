package com.xiye.formaspace.auth.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum VerificationPurpose {
    REGISTER("REGISTER"),
    RESET_PASSWORD("RESET_PASSWORD");

    @EnumValue
    private final String value;

    VerificationPurpose(String value) {
        this.value = value;
    }
}
