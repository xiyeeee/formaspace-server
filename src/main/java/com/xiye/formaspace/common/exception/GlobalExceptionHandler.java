package com.xiye.formaspace.common.exception;

import com.xiye.formaspace.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException exception) {
        var code = exception.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.failure(code.name(), code.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                ErrorCode.INVALID_ARGUMENT.name(), ErrorCode.INVALID_ARGUMENT.getMessage()));
    }

    // Framework validation, binding, malformed JSON, type conversion, 404 and 405 keep their HTTP status.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            logUnexpected(exception);
        }
        return new ResponseEntity<>(errorBody(status), headers, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse error) {
            var status = error.getStatusCode();
            if (status.is5xxServerError()) {
                logUnexpected(exception);
            }
            return new ResponseEntity<>(errorBody(status), error.getHeaders(), status);
        }
        logUnexpected(exception);
        return ResponseEntity.internalServerError().body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private ApiResponse<Void> errorBody(HttpStatusCode status) {
        if (status.value() == 400) {
            return ApiResponse.failure(ErrorCode.INVALID_ARGUMENT.name(), ErrorCode.INVALID_ARGUMENT.getMessage());
        }
        if (status.is5xxServerError()) {
            return ApiResponse.failure(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.getMessage());
        }
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        return ApiResponse.failure("HTTP_" + status.value(),
                knownStatus == null ? "请求处理失败" : knownStatus.getReasonPhrase());
    }

    private void logUnexpected(Exception exception) {
        // Do not log exception messages, causes or request bodies: these may contain SQL or credentials.
        var trace = java.util.Arrays.stream(exception.getStackTrace()).limit(12).toList();
        log.error("Unhandled server exception: type={}, frames={}", exception.getClass().getName(), trace);
    }
}
