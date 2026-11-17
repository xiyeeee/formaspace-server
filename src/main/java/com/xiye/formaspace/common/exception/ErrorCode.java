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
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "文件超过允许的大小"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅支持 JPEG、PNG 源图片"),
    INVALID_IMAGE(HttpStatus.BAD_REQUEST, "图片损坏或无法解码"),
    IMAGE_PIXEL_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "图片像素超过限制"),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "文件存储暂时不可用"),
    INVALID_GENERATION_PARAMETERS(HttpStatus.BAD_REQUEST, "生成参数不合法或超出部署限制"),
    UNSUPPORTED_CAPABILITY(HttpStatus.BAD_REQUEST, "当前部署不支持所选能力"),
    INFERENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "推理服务未就绪或连接中断，请检查模型服务"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "该幂等键已用于不同请求"),
    GENERATION_LIMIT_REACHED(HttpStatus.TOO_MANY_REQUESTS, "未结束任务已达上限，请稍后重试"),
    MOCK_RESULT_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "未配置演示模型，模拟任务无法完成"),
    INFERENCE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "模型生成失败，请检查图片或稍后重试"),
    INFERENCE_PROTOCOL_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "模型服务返回了无效结果"),
    INFERENCE_REFERENCE_LOST(HttpStatus.SERVICE_UNAVAILABLE, "模型服务已重启或任务引用失效，请核对后重新生成"),
    INFERENCE_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "模型生成超时，请核对模型服务后重试"),
    INFERENCE_STATE_UNCERTAIN(HttpStatus.SERVICE_UNAVAILABLE, "等待推理结果超时，远端状态待核对；任务未被取消"),
    SERVICE_RESTARTED(HttpStatus.SERVICE_UNAVAILABLE, "推理服务已重启，中断的生成无法恢复，已保存预览仍可查看"),
    INFERENCE_SUBMISSION_CONFLICT(HttpStatus.CONFLICT, "推理任务标识与原图片或参数冲突，请核对服务记录"),
    INFERENCE_AUTH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "推理服务认证失败，请检查服务配置"),
    SUBMISSION_UNCERTAIN(HttpStatus.SERVICE_UNAVAILABLE, "模型提交结果不确定，请先核对模型服务，避免重复生成"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用，请稍后重试");

    private final HttpStatus httpStatus;
    private final String message;
}
