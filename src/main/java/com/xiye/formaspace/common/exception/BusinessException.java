package com.xiye.formaspace.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    private final Long retryAfterSeconds;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public BusinessException(ErrorCode errorCode, Long retryAfterSeconds) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
