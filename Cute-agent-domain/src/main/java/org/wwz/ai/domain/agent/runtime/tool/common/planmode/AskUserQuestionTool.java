package org.wwz.ai.domain.agent.runtime.tool.common.planmode;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.askuser.UserInputRequiredException;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向用户提问。
 * <p>
 * Continuation 版：校验参数后抛出 {@link UserInputRequiredException}，由上层持久化断点并结束 Run A；
 * 不再在工具线程上 Future.get 阻塞等待。
 * </p>
 */
public class AskUserQuestionTool implements BaseTool {

    public static final String NAME = "AskUserQuestion";

    public AskUserQuestionTool() {
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "向用户提出 1–4 个选择题以澄清需求、方案或偏好。"
                + "每题 2–4 个选项；multiSelect=true 允许多选。"
                + "不要用本工具问“计划可以吗？”（用 ExitPlanMode）。"
                + "调用后当前 run 会结束并等待用户回答；用户提交后由 continuation run 继续。"
                + "本轮必须是唯一 tool call。仅主 Agent 可用。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> optionProps = new LinkedHashMap<>();
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("type", "string");
        label.put("description", "选项短标签（1–5 词）");
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("type", "string");
        description.put("description", "选项说明/权衡");
        optionProps.put("label", label);
        optionProps.put("description", description);

        Map<String, Object> optionItem = new LinkedHashMap<>();
        optionItem.put("type", "object");
        optionItem.put("properties", optionProps);
        optionItem.put("required", List.of("label", "description"));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("type", "array");
        options.put("items", optionItem);
        options.put("minItems", 2);
        options.put("maxItems", 4);
        options.put("description", "2–4 个选项；不要写 Other（前端可加自定义）");

        Map<String, Object> questionText = new LinkedHashMap<>();
        questionText.put("type", "string");
        questionText.put("description", "完整问题文本，建议以 ? 结尾");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "string");
        header.put("description", "短标签（≤12 字）");

        Map<String, Object> multiSelect = new LinkedHashMap<>();
        multiSelect.put("type", "boolean");
        multiSelect.put("description", "是否多选，默认 false");

        Map<String, Object> qProps = new LinkedHashMap<>();
        qProps.put("question", questionText);
        qProps.put("header", header);
        qProps.put("options", options);
        qProps.put("multiSelect", multiSelect);

        Map<String, Object> questionItem = new LinkedHashMap<>();
        questionItem.put("type", "object");
        questionItem.put("properties", qProps);
        questionItem.put("required", List.of("question", "header", "options"));

        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("type", "array");
        questions.put("items", questionItem);
        questions.put("minItems", 1);
        questions.put("maxItems", 4);
        questions.put("description", "1–4 道题");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("questions", questions);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("questions"));
        return parameters;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        if (context == null) {
            return fail("AskUserQuestion 未正确装配");
        }
        if (context.getRequestId() != null && context.getRequestId().contains(":sub:")) {
            return fail("AskUserQuestion 不能在子 Agent 中使用");
        }

        Map<String, Object> params = ToolArgs.mapStrict(input);
        Object rawQuestions = params.get("questions");
        if (!(rawQuestions instanceof List<?> list) || list.isEmpty()) {
            return fail("questions 不能为空（1–4 题）");
        }
        if (list.size() > 4) {
            return fail("questions 最多 4 题");
        }

        List<Map<String, Object>> questions = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> question = stringKeyedObject(list.get(i));
            if (question == null) {
                return fail("questions[" + i + "] 必须是对象");
            }
            if (StringUtils.isBlank(ToolArgs.str(question.get("question")))
                    || StringUtils.isBlank(ToolArgs.str(question.get("header")))) {
                return fail("questions[" + i + "] 缺少 question 或 header");
            }
            Object rawOptions = question.get("options");
            if (!(rawOptions instanceof List<?> options) || options.size() < 2 || options.size() > 4) {
                return fail("questions[" + i + "] 需要 2–4 个选项");
            }
            List<Map<String, Object>> normalizedOptions = new ArrayList<>(options.size());
            for (int j = 0; j < options.size(); j++) {
                Map<String, Object> option = stringKeyedObject(options.get(j));
                if (option == null) {
                    return fail("questions[" + i + "].options[" + j + "] 必须是对象");
                }
                if (StringUtils.isBlank(ToolArgs.str(option.get("label")))
                        || StringUtils.isBlank(ToolArgs.str(option.get("description")))) {
                    return fail("questions[" + i + "].options[" + j + "] 缺少 label 或 description");
                }
                normalizedOptions.add(option);
            }
            question.put("options", normalizedOptions);
            questions.add(question);
        }

        String toolCallId = null;
        ToolArtifactSource source = context.getCurrentToolArtifactSource();
        if (source != null) {
            toolCallId = source.getToolCallId();
        }
        throw new UserInputRequiredException(questions, toolCallId);
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

    private static ToolResultPayload fail(String msg) {
        return ToolResultPayload.failureFrom(msg, null);
    }
}
