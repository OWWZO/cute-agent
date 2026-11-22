package org.wwz.ai.domain.agent.runtime.tool.common.planmode;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentContextFactory;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentOwnershipResolver;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 查询当前 Agent 直接派发的子 Agent 摘要。只读，不返回 prompt/output。
 */
@Slf4j
public class AgentListTool implements BaseTool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_ERROR_LENGTH = 300;

    @Override
    public String getName() {
        return TaskToolNames.AGENT_LIST;
    }

    @Override
    public String getDescription() {
        return "列出当前 Agent 直接派发的子 Agent 及状态。"
                + "只读查询，不唤醒、不停止；唤醒用 SendMessage，停止用 TaskStop。"
                + "不列孙级 Agent。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回条数上限，默认 20，最大 100");

        Map<String, Object> includeTerminal = new LinkedHashMap<>();
        includeTerminal.put("type", "boolean");
        includeTerminal.put("description", "是否包含 completed/failed/stopped/lost，默认 true");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("limit", limit);
        properties.put("includeTerminal", includeTerminal);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of());
        return parameters;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> params = ToolArgs.map(input);
            int limit = params.containsKey("limit")
                    ? parseInt(params.get("limit"), DEFAULT_LIMIT)
                    : DEFAULT_LIMIT;
            limit = Math.max(1, Math.min(limit, MAX_LIMIT));
            boolean includeTerminal = params.containsKey("includeTerminal")
                    ? coerceBoolean(params.get("includeTerminal"), true)
                    : true;

            String callerAgentId = SubAgentOwnershipResolver.currentAgentId(context);
            Map<String, RuntimeBackgroundTask> latestByAgent = new LinkedHashMap<>();
            for (RuntimeBackgroundTask task : context.requireBackgroundTasks().listOwnedBy(callerAgentId)) {
                String agentId = StringUtils.trimToEmpty(task.getAgentId());
                RuntimeBackgroundTask existing = latestByAgent.get(agentId);
                if (existing == null || isNewer(task, existing)) {
                    latestByAgent.put(agentId, task);
                }
            }
            List<RuntimeBackgroundTask> latestTasks = new ArrayList<>(latestByAgent.values());
            latestTasks.sort(Comparator
                    .comparingInt(AgentListTool::activeFirst)
                    .thenComparing(RuntimeBackgroundTask::getStartedAtMs, Comparator.reverseOrder()));

            List<RuntimeBackgroundTask> visible = new ArrayList<>();
            for (RuntimeBackgroundTask task : latestTasks) {
                if (!includeTerminal && !active(task)) {
                    continue;
                }
                visible.add(task);
            }
            boolean truncated = visible.size() > limit;
            List<Map<String, Object>> agents = new ArrayList<>();
            for (RuntimeBackgroundTask task : visible.subList(0, Math.min(limit, visible.size()))) {
                agents.add(summarize(context, task));
            }

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("callerAgentId", callerAgentId);
            fields.put("count", agents.size());
            fields.put("truncated", truncated);
            fields.put("agents", agents);
            fields.put("message", "只列出当前 Agent 直接派发的子 Agent；"
                    + "唤醒用 SendMessage，停止用 TaskStop。");
            return ToolResultPayload.okData(TaskToolNames.AGENT_LIST, fields);
        } catch (Exception e) {
            log.warn("AgentList failed", e);
            String msg = "AgentList 失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return ToolResultPayload.failureFrom(msg, null);
        }
    }

    private Map<String, Object> summarize(AgentContext context, RuntimeBackgroundTask task) {
        String childSessionId = StringUtils.defaultIfBlank(
                task.getChildSessionId(),
                SubAgentContextFactory.newChildSessionId(task.getAgentId()));
        boolean active = active(task);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("agentId", task.getAgentId());
        putIfNotBlank(fields, "description", task.getDescription());
        putIfNotBlank(fields, "agentType", task.getAgentType());
        fields.put("status", task.getStatus());
        putIfNotBlank(fields, "phase", task.getPhase());
        fields.put("childSessionId", childSessionId);
        fields.put("startedAtMs", task.getStartedAtMs());
        Optional.ofNullable(task.getEndedAtMs()).ifPresent(value -> fields.put("endedAtMs", value));
        if (StringUtils.isNotBlank(task.getErrorMsg())) {
            fields.put("errorMsg", StringUtils.abbreviate(task.getErrorMsg(), MAX_ERROR_LENGTH));
        }
        fields.put("canMessage", active);
        fields.put("canResume", !active && hasPersistedMemory(context, childSessionId));
        return fields;
    }

    private boolean hasPersistedMemory(AgentContext context, String childSessionId) {
        SubAgentRunner runner = context.subAgentRunner();
        return runner != null && runner.hasPersistedMemory(childSessionId);
    }

    private static void putIfNotBlank(Map<String, Object> fields, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            fields.put(key, value);
        }
    }

    private boolean isNewer(RuntimeBackgroundTask candidate, RuntimeBackgroundTask current) {
        if (candidate.getStartedAtMs() != current.getStartedAtMs()) {
            return candidate.getStartedAtMs() > current.getStartedAtMs();
        }
        return StringUtils.defaultString(candidate.getId())
                .compareTo(StringUtils.defaultString(current.getId())) > 0;
    }

    private static boolean active(RuntimeBackgroundTask task) {
        return RuntimeBackgroundTask.STATUS_RUNNING.equals(task.getStatus());
    }

    private static int activeFirst(RuntimeBackgroundTask task) {
        return active(task) ? 0 : 1;
    }

    private static int parseInt(Object value, int defaultValue) {
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static boolean coerceBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
            return false;
        }
        return defaultValue;
    }
}
