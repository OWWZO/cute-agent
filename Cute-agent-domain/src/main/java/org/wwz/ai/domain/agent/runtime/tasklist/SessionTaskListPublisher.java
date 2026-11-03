package org.wwz.ai.domain.agent.runtime.tasklist;

import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskToolNames;

import java.util.Map;

/**
 * 任务列表变更后推送 SSE。
 */
public final class SessionTaskListPublisher {

    private SessionTaskListPublisher() {
    }

    public static void publish(AgentContext agentContext) {
        if (agentContext == null || agentContext.getPrinter() == null) {
            return;
        }
        // 发布是只读路径：列表尚未建立时不要为了发空快照去构造 store / hydrate DB。
        SessionTaskListStore store = agentContext.peekSessionTaskList();
        if (store == null) {
            return;
        }
        Map<String, Object> snapshot = store.toClientSnapshot();
        agentContext.getPrinter().send(TaskToolNames.SESSION_TASKS_EVENT, snapshot);
    }
}
