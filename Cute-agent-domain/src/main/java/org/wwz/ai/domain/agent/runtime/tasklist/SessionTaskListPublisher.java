package org.wwz.ai.domain.agent.runtime.tasklist;

import lombok.extern.slf4j.Slf4j;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskToolNames;

import java.util.Map;

/**
 * 任务列表变更后推送 SSE。
 */
@Slf4j
public final class SessionTaskListPublisher {

    private SessionTaskListPublisher() {
    }

    public static void publish(AgentContext agentContext) {
        Printer printer = agentContext == null ? null : agentContext.getPrinter();
        if (printer == null) {
            return;
        }
        // 发布是只读路径：列表尚未建立时不要为了发空快照去构造 store / hydrate DB。
        SessionTaskListStore store = agentContext.peekSessionTaskList();
        if (store == null) {
            return;
        }
        Map<String, Object> snapshot = store.toClientSnapshot();
        try {
            printer.send(TaskToolNames.SESSION_TASKS_EVENT, snapshot);
        } catch (Exception e) {
            // 推送是 best-effort：列表已经改完，发送失败不能让调用方误判业务失败并重试。
            log.warn("session task list publish failed listId={}: {}", store.getListId(), e.getMessage(), e);
        }
    }
}
