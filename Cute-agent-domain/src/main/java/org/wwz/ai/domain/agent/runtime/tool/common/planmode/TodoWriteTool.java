package org.wwz.ai.domain.agent.runtime.tool.common.planmode;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskItem;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListPublisher;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 整表写入 Todo 列表。
 * 空数组表示清空列表。
 */
@Slf4j
public class TodoWriteTool implements BaseTool {

    @Override
    public String getName() {
        return TaskToolNames.TODO_WRITE;
    }

    @Override
    public String getDescription() {
        return "用完整 todo 列表覆盖当前会话任务清单。每项含 content（或 subject）、status（pending|in_progress|completed），"
                + "可选 activeForm。"
                + "复杂多步/计划获批后跟踪进度时使用；简单对话不要用。"
                + "传空数组表示清空列表。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("content", Map.of("type", "string", "description", "任务内容/标题"));
        itemProps.put("subject", Map.of("type", "string", "description", "可选，同 content"));
        itemProps.put("description", Map.of("type", "string", "description", "可选，详情"));
        itemProps.put("status", Map.of(
                "type", "string",
                "description", "pending | in_progress | completed"));
        itemProps.put("activeForm", Map.of("type", "string", "description", "可选，进行时文案"));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);
        item.put("required", List.of("status"));
        // content 与 subject 至少一个存在；否则 replaceAll 会跳过该项并静默丢任务。
        item.put("anyOf", List.of(
                Map.of(
                        "type", "object",
                        "properties", itemProps,
                        "required", List.of("content")),
                Map.of(
                        "type", "object",
                        "properties", itemProps,
                        "required", List.of("subject"))));

        Map<String, Object> todos = new LinkedHashMap<>();
        todos.put("type", "array");
        todos.put("items", item);
        todos.put("description", "更新后的完整 todo 列表");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("todos", todos);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("todos"));
        return parameters;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> params = ToolArgs.map(input);
            Object rawTodos = params.get("todos");
            if (!(rawTodos instanceof List<?> list)) {
                return failure("todos 必须为数组");
            }
            List<Map<String, Object>> todoMaps = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Map<String, Object> item = stringKeyedObject(list.get(i));
                if (item == null) {
                    return failure("todos[" + i + "] 必须是对象");
                }
                if (StringUtils.isBlank(firstNonBlank(item.get("content"), item.get("subject")))) {
                    return failure("todos[" + i + "] 缺少 content 或 subject");
                }
                String status = ToolArgs.str(item.get("status"));
                if (StringUtils.isBlank(status)) {
                    return failure("todos[" + i + "] 缺少 status");
                }
                if (!SessionTaskListStore.isSupportedStatus(status)) {
                    return failure("todos[" + i + "] status 非法: " + status
                            + "，允许: pending | in_progress | completed");
                }
                // 先校验再调用 replaceAll：非法条目不能触发破坏性的整表替换。
                item.put("status", status);
                todoMaps.add(item);
            }
            SessionTaskListStore store = context.requireSessionTaskList();
            List<SessionTaskItem> oldTodos = store.replaceAll(todoMaps);
            List<SessionTaskItem> newTodos = store.list();
            SessionTaskListPublisher.publish(context);

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("message", "Todos have been modified successfully. Continue using the todo list to track progress.");
            fields.put("oldCount", oldTodos.size());
            fields.put("newCount", newTodos.size());
            fields.put("tasks", store.toClientTaskList());
            return ToolResultPayload.okData(TaskToolNames.TODO_WRITE, fields);
        } catch (Exception e) {
            log.warn("TodoWrite failed", e);
            String msg = "TodoWrite 失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return ToolResultPayload.failureFrom(msg, null);
        }
    }

    private static ToolResultPayload failure(String message) {
        String text = "TodoWrite 失败：" + message;
        return ToolResultPayload.failureFrom(text, null);
    }

    private static Map<String, Object> stringKeyedObject(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private static String firstNonBlank(Object a, Object b) {
        String first = a == null ? "" : String.valueOf(a).trim();
        if (StringUtils.isNotBlank(first)) {
            return first;
        }
        return b == null ? "" : String.valueOf(b).trim();
    }
}
