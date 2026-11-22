package org.wwz.ai.application.agent.channel.inbound;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.application.agent.query.GptQueryCommand;
import org.wwz.ai.application.agent.query.IGptQueryApplicationService;
import org.wwz.ai.application.agent.run.AgentRunLaunchGate;
import org.wwz.ai.domain.agent.channel.port.ChannelAdapter;
import org.wwz.ai.domain.agent.channel.port.ChannelInboundPort;
import org.wwz.ai.domain.agent.channel.model.ChannelInboundEvent;
import org.wwz.ai.domain.agent.channel.model.ChannelPushResult;
import org.wwz.ai.domain.agent.channel.model.ChannelTarget;
import org.wwz.ai.domain.agent.channel.policy.ChannelSessionKey;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.model.ChannelIdentity;
import org.wwz.ai.domain.agent.channel.port.ChannelIdentityRepository;
import org.wwz.ai.types.agent.exception.AgentConcurrentRunException;
import org.wwz.ai.types.agent.user.UserRequestContext;

import java.util.UUID;

/**
 * 入站编排：一条 IM 消息 → 一轮 agent 执行。
 * <p>完全复用 {@code submitAgentQuery}，不重写编排：会话创建、授权、run 注册、launch gate、
 * LTM 边界都由现有链路负责；终答由 {@code AgentSessionPrinter} 在产生点直推，
 * 不再挂投影观察者。</p>
 */
@Slf4j
public class ChannelInboundDispatchService implements ChannelInboundPort {

    private final IGptQueryApplicationService gptQueryApplicationService;
    private final AgentRunLaunchGate agentRunLaunchGate;
    private final ChannelIdentityRepository identityRepository;
    private final ChannelSessionBinder sessionBinder;

    public ChannelInboundDispatchService(IGptQueryApplicationService gptQueryApplicationService,
                                         AgentRunLaunchGate agentRunLaunchGate,
                                         ChannelIdentityRepository identityRepository,
                                         ChannelSessionBinder sessionBinder) {
        this.gptQueryApplicationService = gptQueryApplicationService;
        this.agentRunLaunchGate = agentRunLaunchGate;
        this.identityRepository = identityRepository;
        this.sessionBinder = sessionBinder;
    }

    /**
     * @param binding 消息所属绑定（决定凭证与身份隔离边界）
     * @param adapter 该绑定的出站适配器
     */
    @Override
    public void dispatch(ChannelInboundEvent event, ChannelBinding binding, ChannelAdapter adapter) {
        if (event == null || binding == null || adapter == null) {
            return;
        }
        String text = StringUtils.trimToEmpty(event.text());
        if (text.isEmpty()) {
            return;
        }

        String previousUserId = UserRequestContext.currentUserId();
        try {
            ChannelIdentity identity = identityRepository.findOrCreate(
                    binding.getId(), event.platform(), event.platformUserId(), null);
            UserRequestContext.bind(identity.getUserId());

            String sessionId = ChannelSessionKey.of(
                    event.chatType(), event.targetId(), event.platformUserId());
            String requestId = "im-" + UUID.randomUUID();

            GptQueryCommand command = GptQueryCommand.builder()
                    .query(text)
                    .sessionId(sessionId)
                    .requestId(requestId)
                    // 强制 REACT：PLAN_SOLVE 会要求计划审批，而 IM 里没人能点「批准」。
                    .collaborationMode(org.wwz.ai.domain.agent.runtime.enums.CollaborationMode.NORMAL_VALUE)
                    .forcePlanMode(false)
                    .build();
            gptQueryApplicationService.submitAgentQuery(command);
            // 会话行由 submit 内部的 ensureSessionAccessible 创建，之后才能安全打 binding 标记。
            sessionBinder.bind(
                    sessionId,
                    binding.getId(),
                    identity.getUserId(),
                    event.chatType() == null ? null : event.chatType().name(),
                    event.targetId(),
                    event.messageId(),
                    event.receivedAt());
            // 立刻放行：IM 侧没有「浏览器连上来才开始」的概念，不能等 launch gate 的 2 秒兜底。
            agentRunLaunchGate.launchBySession(sessionId);
            // 终答由 AgentSessionPrinter 直接推送 IM，不再挂出站观察者。
            log.info("channel inbound dispatched bindingId={} sessionId={} requestId={} chatType={}",
                    binding.getId(), sessionId, requestId, event.chatType());
        } catch (AgentConcurrentRunException e) {
            pushQuietly(adapter, event, "上一条消息还在处理中，请稍候再试");
        } catch (Exception e) {
            log.warn("channel inbound dispatch failed bindingId={} target={}",
                    binding.getId(), event.targetId(), e);
            pushQuietly(adapter, event, "处理失败，请稍后重试");
        } finally {
            if (previousUserId == null) {
                UserRequestContext.clear();
            } else {
                UserRequestContext.bind(previousUserId);
            }
        }
    }

    private void pushQuietly(ChannelAdapter adapter, ChannelInboundEvent event, String message) {
        try {
            ChannelTarget target = new ChannelTarget(
                    event.platform(), event.bindingId(), event.chatType(),
                    event.targetId(), event.messageId(), event.receivedAt());
            ChannelPushResult result = adapter.pushText(target, message);
            if (result != null && !result.success()) {
                log.debug("channel rejection push failed target={} error={}", event.targetId(), result.error());
            }
        } catch (Exception ignored) {
            // 拒绝提示发不出去也不能影响主流程。
        }
    }
}
