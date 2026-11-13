package org.wwz.ai.domain.agent.runtime.tool.common.planmode;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwner;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwnerType;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwnerResolver;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.DesktopControlRequiredException;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.DesktopSessionRemote;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RequestDesktopControlTool implements BaseTool {

    public static final String NAME = "RequestDesktopControl";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "把当前用户的图形桌面交给用户操作，适用于登录、点按钮、过验证码等需要人手的界面。"
                + "调用后当前 run 结束，等用户点「我已完成」再继续。"
                + "必须是本轮唯一 tool call。不要用它替代 bash / code_execution。"
                + "完成后自己再 bash 检查结果（登录 cookie、下载文件等）。"
                + "同一 owner 的多个 session 共用一块桌面。仅主 Agent 可用。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", "给用户看的一句话，说明为什么需要操作桌面");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reason", reason);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("reason"));
        return parameters;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        if (context == null) {
            return fail("RequestDesktopControl 未正确装配");
        }
        if (context.isSubAgent()) {
            return fail("RequestDesktopControl 不能在子 Agent 中使用");
        }
        try {
            context.requireRuntimeDependencies();
        } catch (IllegalStateException e) {
            return fail("RequestDesktopControl 未正确装配");
        }
        Map<String, Object> params = ToolArgs.mapStrict(input);
        String reason = params.get("reason") == null ? "" : String.valueOf(params.get("reason")).trim();
        if (reason.isBlank()) {
            return fail("reason 不能为空");
        }
        String toolCallId = null;
        ToolArtifactSource source = context.getCurrentToolArtifactSource();
        if (source != null) {
            toolCallId = source.getToolCallId();
        }
        String ownerKey;
        try {
            ownerKey = resolveOwnerKey(context);
        } catch (IllegalStateException e) {
            return fail(e.getMessage());
        }
        try {
            CuteConfig config = context.getRuntimeDependencies().requireCuteConfig();
            String sessionId = StringUtils.defaultIfBlank(context.getSessionId(), context.getRequestId());
            DesktopSessionRemote.OpenResult opened = DesktopSessionRemote.open(
                    context.getRuntimeDependencies().requireRemoteHttpPort(),
                    config,
                    context.getRequestId(),
                    sessionId,
                    ownerKey,
                    DesktopSessionRemote.DEFAULT_TTL_SECONDS);
            throw new DesktopControlRequiredException(reason, opened.getUrl(), opened.getHoldUntil(), toolCallId);
        } catch (DesktopControlRequiredException yield) {
            throw yield;
        } catch (Exception e) {
            return fail("打开桌面失败: " + StringUtils.defaultString(e.getMessage()));
        }
    }

    /**
     * 桌面按 ownerKey 共享，解析不到真实用户时必须快速失败：
     * 否则所有异步/无请求上下文的调用方都会落到同一个 anonymous 桌面。
     */
    private String resolveOwnerKey(AgentContext context) {
        LtmOwner owner = context.getLtmOwner();
        if (owner == null) {
            owner = LtmOwnerResolver.resolve(context.getUserId(), null, context.getSessionId());
        }
        if (owner == null
                || LtmOwnerResolver.ANONYMOUS_OWNER_ID.equals(owner.getId())
                || (owner.getType() != LtmOwnerType.USER && owner.getType() != LtmOwnerType.ERP)) {
            throw new IllegalStateException("无法识别当前用户，已拒绝打开共享桌面会话");
        }
        return owner.asOwnerKey();
    }


    private static ToolResultPayload fail(String msg) {
        return ToolResultPayload.failureFrom(msg, null);
    }
}
