package org.wwz.ai.domain.agent.runtime.subagent;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.adapter.port.SubAgentSessionPort;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;

/**
 * 子 Agent 归属判断。
 * <p>
 * SendMessage / TaskStop / AgentList 共用这一处规则：只认当前 Agent 的直接子 Agent。
 * 旧任务没有显式父子字段时，回退到 {@code sub_<agentId>} 子会话表的父会话链。
 */
public final class SubAgentOwnershipResolver {

    private SubAgentOwnershipResolver() {
    }

    /** 当前 Agent 的身份：子 Agent 用 agentId，主 Agent 退化为 sessionId/requestId。 */
    public static String currentAgentId(AgentContext context) {
        return StringUtils.defaultIfBlank(
                context.getSubAgentId(),
                StringUtils.defaultIfBlank(context.getSessionId(), context.getRequestId()));
    }

    /** 目标是否由调用者直接派发；只用于管理工具，不提供任意后代权限。 */
    public static boolean ownsDirectChild(AgentContext context, RuntimeBackgroundTask task, String agentId) {
        String callerAgentId = currentAgentId(context);
        if (StringUtils.isBlank(callerAgentId) || StringUtils.isBlank(agentId)) {
            return false;
        }
        if (task == null) {
            return false;
        }
        if (callerAgentId.equals(StringUtils.trimToEmpty(task.getOwnerAgentId()))
                || callerAgentId.equals(StringUtils.trimToEmpty(task.getParentAgentId()))) {
            return true;
        }
        String owningParent = findOwningParentAgentId(context, task, agentId);
        return StringUtils.isNotBlank(owningParent) && callerAgentId.equals(owningParent);
    }

    /** 旧任务缺少显式父子信息时，session 表是权威血缘链。 */
    public static boolean ownsLegacyChild(AgentContext context, RuntimeBackgroundTask task, String agentId) {
        if (task == null || StringUtils.isBlank(agentId)) {
            return false;
        }
        if (StringUtils.isNotBlank(task.getOwnerAgentId())
                || StringUtils.isNotBlank(task.getParentAgentId())) {
            return false;
        }
        return StringUtils.isBlank(findOwningParentAgentId(context, task, agentId));
    }

    /** 返回目标的直接父 Agent，仅用于错误提示，不扩大任何操作权限。 */
    public static String findOwningParentAgentId(AgentContext context,
                                                  RuntimeBackgroundTask task,
                                                  String agentId) {
        if (task == null) {
            return null;
        }
        String explicitParent = context.requireBackgroundTasks().findOwningParentAgentId(task);
        if (StringUtils.isNotBlank(explicitParent)) {
            return explicitParent;
        }
        SubAgentSessionPort sessionPort = context.getRuntimeDependencies() == null
                ? null : context.getRuntimeDependencies().getOptionalSubAgentSessionPort();
        if (sessionPort == null) {
            return null;
        }
        String childSessionId = StringUtils.defaultIfBlank(
                task.getChildSessionId(),
                SubAgentContextFactory.newChildSessionId(agentId));
        String parentSessionId = sessionPort.findParentSessionId(childSessionId);
        return SubAgentContextFactory.isChildSessionId(parentSessionId)
                ? SubAgentContextFactory.agentIdFromChildSessionId(parentSessionId)
                : null;
    }
}
