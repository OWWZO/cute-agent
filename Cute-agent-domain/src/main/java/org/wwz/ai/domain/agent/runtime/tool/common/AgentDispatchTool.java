package org.wwz.ai.domain.agent.runtime.tool.common;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.BackgroundSubAgentExecutor;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinition;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRegistry;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentObservationBuilder;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentResult;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentRunner;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主 Agent 派发子 Agent 的工具入口。只创建新子 Agent。
 * 默认同步阻塞；run_in_background=true 时注册后台任务并立即返回 task_id。
 */
@Slf4j
public class AgentDispatchTool implements BaseTool {

    public static final String NAME = "Agent";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return buildDescription(null);
    }

    /** 可用 subagent_type 清单来自运行时注册表，只能从 context 取。 */
    @Override
    public String getDescription(AgentContext context) {
        return buildDescription(context);
    }

    private String buildDescription(AgentContext context) {
        SubAgentRegistry subAgentRegistry = resolveSubAgentRegistry(context);
        StringBuilder sb = new StringBuilder();
        sb.append("派发一个新的子 Agent 执行独立任务。")
                .append("默认阻塞等待完成后返回精简报告；run_in_background=true 时立即返回 task_id 与 agentId。")
                .append("后台运行中：用 TaskOutput 取结果、TaskStop 取消、SendMessage 中途指导。")
                .append("已结束/失败/停止的子 Agent 用 SendMessage(to=agentId, message=…) 后台唤醒，不要用本工具续跑。")
                .append("新任务：子 Agent 从零上下文开始，请在 prompt 中写全背景与交付要求。")
                .append("可用 subagent_type：");
        List<String> lines = new ArrayList<>();
        if (subAgentRegistry != null) {
            for (SubAgentDefinition def : subAgentRegistry.list()) {
                lines.add(def.getAgentType() + " — " + def.getWhenToUse());
            }
        }
        if (lines.isEmpty()) {
            sb.append(SubAgentRegistry.TYPE_GENERAL_PURPOSE);
        } else {
            sb.append(String.join("; ", lines));
        }
        sb.append("。省略 subagent_type 时默认 general-purpose。不要用本工具做简单单次查询。");
        return sb.toString();
    }

    @Override
    public Map<String, Object> toParams() {
        return buildParams(null);
    }

    /** subagent_type 候选同样来自运行时注册表。 */
    @Override
    public Map<String, Object> toParams(AgentContext context) {
        return buildParams(context);
    }

    private Map<String, Object> buildParams(AgentContext context) {
        SubAgentRegistry subAgentRegistry = resolveSubAgentRegistry(context);
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", "string");
        description.put("description", "任务短描述，3-5 个词，用于展示与日志");

        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("type", "string");
        prompt.put("description",
                "交给子 Agent 的任务说明。须写全背景与交付要求；本工具只创建新实例");

        Map<String, Object> subagentType = new LinkedHashMap<>();
        subagentType.put("type", "string");
        String typeHint = "子 Agent 类型；省略则 general-purpose。可用: "
                + (subAgentRegistry == null || subAgentRegistry.listTypeNames().isEmpty()
                ? SubAgentRegistry.TYPE_GENERAL_PURPOSE
                : String.join(", ", subAgentRegistry.listTypeNames()));
        subagentType.put("description", typeHint);

        Map<String, Object> runInBackground = new LinkedHashMap<>();
        runInBackground.put("type", "boolean");
        runInBackground.put("description",
                "设为 true 时后台运行：立即返回 task_id。用 TaskOutput 等待/读取结果，SendMessage 中途指导，TaskStop 取消");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", description);
        properties.put("prompt", prompt);
        properties.put("subagent_type", subagentType);
        properties.put("run_in_background", runInBackground);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("description", "prompt"));
        return parameters;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Object input, AgentContext agentContext) {
        try {
            Map<String, Object> params = input instanceof Map
                    ? (Map<String, Object>) input
                    : JSON.parseObject(JSON.toJSONString(input), Map.class);
            if (params == null) {
                return ToolResultPayload.failureFrom("Agent 执行失败：参数为空", null);
            }

            String description = trimToString(params.get("description"));
            String prompt = trimToString(params.get("prompt"));
            String subagentType = trimToString(params.get("subagent_type"));
            String resumeAgentId = trimToString(params.get("resume_agent_id"));
            boolean background = coerceBoolean(params.get("run_in_background"));

            if (StringUtils.isNotBlank(resumeAgentId)) {
                return ToolResultPayload.failureFrom(
                        "Agent 执行失败：resume_agent_id 已移除。请用 SendMessage(to=\""
                                + resumeAgentId + "\", message=…) 指导运行中的子 Agent 或唤醒已结束的子 Agent。",
                        Map.of("hint", "SendMessage(to=\"" + resumeAgentId + "\", message=\"…\")",
                                "agentId", resumeAgentId));
            }
            if (StringUtils.isBlank(prompt)) {
                return ToolResultPayload.failureFrom("Agent 执行失败：prompt 不能为空", null);
            }
            if (StringUtils.isBlank(description)) {
                description = StringUtils.defaultIfBlank(subagentType, "subagent-task");
            }
            SubAgentRunner subAgentRunner = agentContext.subAgentRunner();
            if (subAgentRunner == null) {
                return ToolResultPayload.failureFrom("Agent 执行失败：SubAgentRunner 未注入", null);
            }
            ActiveAgentRunRegistry activeAgentRunRegistry = agentContext.activeAgentRunRegistry();

            // 必须在当前线程捕获：currentToolArtifactSource 是 ThreadLocal，
            // 后台线程不会继承，否则子事件丢失 parentToolUseId 并泄漏到主时间线。
            String parentToolUseId = captureParentToolUseId(agentContext);

            if (background) {
                return BackgroundSubAgentExecutor.submit(
                        agentContext, subAgentRunner, activeAgentRunRegistry,
                        description, prompt, subagentType, null, parentToolUseId, NAME);
            }

            SubAgentResult result = subAgentRunner.run(
                    agentContext, description, prompt, subagentType,
                    null, null, null, parentToolUseId);
            Map<String, Object> data = SubAgentObservationBuilder.build(result, NAME, false);
            if (!result.isCompleted()) {
                return ToolResultPayload.failureFrom(
                        StringUtils.defaultIfBlank(result.getErrorMsg(), "Agent 执行失败"),
                        data);
            }
            return ToolResultPayload.fromData(data);
        } catch (Exception e) {
            log.error("Agent dispatch tool failed", e);
            String msg = "Agent 执行失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return ToolResultPayload.failureFrom(msg, null);
        }
    }

    /**
     * 初始 Agent 回执落账后，若后台任务已经快速结束，补写一次最终 observation。
     */
    public static void settleLedgerIfTerminal(AgentContext parent,
                                               String parentToolUseId,
                                               String runningObservation) {
        BackgroundSubAgentExecutor.settleLedgerIfTerminal(parent, parentToolUseId, runningObservation);
    }

    /**
     * 后台任务清空后，只有父 run 已经 finishRun 才允许关观察流。
     */
    public static boolean shouldSettleParentStream(AgentContext parent) {
        return BackgroundSubAgentExecutor.shouldSettleParentStream(parent);
    }

    private static String captureParentToolUseId(AgentContext parent) {
        if (parent == null || parent.getCurrentToolArtifactSource() == null) {
            return null;
        }
        return StringUtils.trimToNull(parent.getCurrentToolArtifactSource().getToolCallId());
    }

    private static String trimToString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 子 Agent 执行器与 context 同源：装配期已挂到运行时依赖上。 */
    /** 子 Agent 定义表与 context 同源：描述里的 subagent_type 清单由此生成。 */
    /** 子 Agent 注册表只在本工具使用，不值得单开 AgentContext getter。 */
    private static SubAgentRegistry resolveSubAgentRegistry(AgentContext context) {
        if (context == null || context.getRuntimeDependencies() == null) {
            return null;
        }
        return context.getRuntimeDependencies().getOptionalSubAgentRegistry();
    }

    private static boolean coerceBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }
}
