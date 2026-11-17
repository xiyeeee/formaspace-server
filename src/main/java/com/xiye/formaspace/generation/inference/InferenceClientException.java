package com.xiye.formaspace.generation.inference;

import com.xiye.formaspace.common.exception.ErrorCode;
import java.io.IOException;
import lombok.Getter;

@Getter
public class InferenceClientException extends IOException {
    private final ErrorCode errorCode;
    public InferenceClientException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
