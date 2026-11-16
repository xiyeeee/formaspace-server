package com.xiye.formaspace.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "请求参数不正确"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "请先登录"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有访问权限"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(HttpStatus.CONFLICT, "资源已存在或状态冲突"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "账号或密码错误"),
    VERIFICATION_INVALID(HttpStatus.BAD_REQUEST, "验证码无效或已过期，请重新获取"),
    MAIL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "邮件暂时无法发送，请稍后重试"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "服务暂时不可用，请稍后重试"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用，请稍后重试");

    private final HttpStatus httpStatus;
    private final String message;
}
