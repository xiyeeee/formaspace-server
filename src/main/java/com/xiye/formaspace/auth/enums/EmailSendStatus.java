package com.xiye.formaspace.auth.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EmailSendStatus {
    PENDING("PENDING"), SENT("SENT"), FAILED("FAILED");

    @EnumValue
    private final String value;
}
