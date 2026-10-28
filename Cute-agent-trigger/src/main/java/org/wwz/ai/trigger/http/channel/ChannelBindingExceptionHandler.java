package org.wwz.ai.trigger.http.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.wwz.ai.api.response.Response;
import org.wwz.ai.types.enums.ResponseCode;

/**
 * 渠道端点的错误映射。
 * <p>业务校验失败（已绑定、超上限、无权操作、任务失效）都是可预期的拒绝，用 200 + 业务码返回，
 * 前端只读 {@code info} 展示即可；只有未预期的异常才记 ERROR 日志。</p>
 */
@Slf4j
@RestControllerAdvice(assignableTypes = ChannelBindingController.class)
public class ChannelBindingExceptionHandler {

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public Response<Void> handleBusinessRejection(RuntimeException e) {
        return Response.<Void>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(e.getMessage())
                .build();
    }

    @ExceptionHandler(Exception.class)
    public Response<Void> handleUnexpected(Exception e) {
        log.error("channel endpoint failed", e);
        return Response.<Void>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info("渠道服务异常，请稍后重试")
                .build();
    }
}
