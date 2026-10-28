package org.wwz.ai.trigger.http.channel;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;
import org.wwz.ai.api.response.Response;
import org.wwz.ai.application.agent.channel.binding.ChannelBindingApplicationService;
import org.wwz.ai.application.agent.channel.binding.ChannelBindingQrService;
import org.wwz.ai.application.agent.channel.binding.ChannelOwnerQueryService;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.types.agent.user.UserRequestContext;
import org.wwz.ai.types.enums.ResponseCode;

import java.time.format.DateTimeFormatter;

/**
 * 机器人绑定入口。
 * <p>身份一律取 {@code UserRequestContext}（由 {@code AuthenticationFilter} 绑定），
 * 请求体里不接受 userId。响应体不含 appSecret。</p>
 * <p>整个渠道模块由 {@code cute.channel.enabled} 控制。本类的依赖
 * （{@code ChannelBindingApplicationService} 等）只在开关打开时由
 * {@code ChannelAutoConfiguration} 注册，因此本类也必须带同一开关——
 * 否则开关关闭时 Controller 仍被组件扫描注册，启动即因缺依赖失败。</p>
 */
@Slf4j
@RequestMapping("/api/channel/binding")
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cute.channel", name = "enabled", havingValue = "true")
public class ChannelBindingController {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChannelBindingApplicationService bindingService;
    private final ChannelBindingQrService qrService;
    private final ChannelOwnerQueryService ownerQueryService;

    @GetMapping("/mine")
    public Response<BindingMineView> mine() {
        String userId = UserRequestContext.requireUserId();
        ChannelBinding binding = bindingService.mine(userId).orElse(null);
        return ok(BindingMineView.builder()
                .binding(binding == null ? null : toView(binding))
                .globalBindingCount(bindingService.globalBindingCount())
                .maxBindings(bindingService.maxBindings())
                .build());
    }

    @PostMapping("/qr")
    public Response<ChannelBindingQrService.QrView> createQr() {
        String userId = UserRequestContext.requireUserId();
        bindingService.assertCanCreate(userId);
        return ok(qrService.createQr(userId));
    }

    @GetMapping("/qr/{taskId}")
    public Response<ChannelBindingQrService.QrStatus> pollQr(@PathVariable("taskId") String taskId) {
        UserRequestContext.requireUserId();
        return ok(qrService.poll(taskId));
    }

    @PostMapping("/{bindingId}/retry")
    public Response<Boolean> retry(@PathVariable("bindingId") Long bindingId) {
        String userId = UserRequestContext.requireUserId();
        bindingService.retry(bindingId, userId);
        return ok(true);
    }

    @DeleteMapping("/{bindingId}")
    public Response<UnbindResult> unbind(@PathVariable("bindingId") Long bindingId) {
        String userId = UserRequestContext.requireUserId();
        String notice = bindingService.unbind(bindingId, userId);
        return ok(new UnbindResult(true, notice));
    }

    /** owner 查自己 bot 下的会话列表。不暴露 QQ 用户身份。 */
    @GetMapping("/{bindingId}/sessions")
    public Response<ChannelOwnerQueryService.SessionListView> sessions(
            @PathVariable("bindingId") Long bindingId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        String userId = UserRequestContext.requireUserId();
        return ok(ownerQueryService.listSessions(bindingId, userId, limit));
    }

    private static <T> Response<T> ok(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private static BindingView toView(ChannelBinding binding) {
        return BindingView.builder()
                .bindingId(binding.getId())
                .appId(binding.getAppId())
                .botOpenid(binding.getBotOpenid())
                .status(binding.getStatus())
                .lastError(binding.getLastError())
                .connectedAt(binding.getConnectedAt() == null ? null : binding.getConnectedAt().format(TIME_FORMAT))
                .build();
    }

    @Data
    @Builder
    public static class BindingView {
        private Long bindingId;
        private String appId;
        private String botOpenid;
        private String status;
        private String lastError;
        private String connectedAt;
    }

    @Data
    @Builder
    public static class BindingMineView {
        private BindingView binding;
        private long globalBindingCount;
        private int maxBindings;
    }

    public record UnbindResult(boolean unbound, String notice) {
    }
}
