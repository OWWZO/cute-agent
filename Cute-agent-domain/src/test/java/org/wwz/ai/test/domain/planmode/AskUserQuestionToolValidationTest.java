package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.askuser.UserInputRequiredException;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.AskUserQuestionTool;

import java.util.List;
import java.util.Map;

/**
 * 问询断点落库前必须满足 toParams() 声明的结构：坏数据一旦持久化，前端无法渲染、恢复时也匹配不上。
 */
public class AskUserQuestionToolValidationTest {

    private final AskUserQuestionTool tool = new AskUserQuestionTool();
    private final AgentContext context = AgentContext.builder().requestId("req-ask").build();

    @Test
    public void rejectsNonObjectQuestionEntry() {
        assertRejected(List.of("只是一个字符串"), "必须是对象");
    }

    @Test
    public void rejectsMissingQuestionOrHeader() {
        assertRejected(List.of(Map.of(
                "question", "要不要继续？",
                "options", options())), "缺少 question 或 header");
        assertRejected(List.of(Map.of(
                "header", "继续",
                "options", options())), "缺少 question 或 header");
    }

    @Test
    public void rejectsOptionCountOutsideTwoToFour() {
        assertRejected(List.of(questionWithOptions(List.of(
                Map.of("label", "A", "description", "a")))), "需要 2–4 个选项");
        assertRejected(List.of(questionWithOptions(List.of(
                Map.of("label", "A", "description", "a"),
                Map.of("label", "B", "description", "b"),
                Map.of("label", "C", "description", "c"),
                Map.of("label", "D", "description", "d"),
                Map.of("label", "E", "description", "e")))), "需要 2–4 个选项");
    }

    @Test
    public void rejectsOptionMissingLabelOrDescription() {
        assertRejected(List.of(questionWithOptions(List.of(
                Map.of("label", "A"),
                Map.of("label", "B", "description", "b")))), "缺少 label 或 description");
    }

    @Test
    public void rejectsMoreThanFourQuestions() {
        List<Map<String, Object>> questions = List.of(
                question("Q1"), question("Q2"), question("Q3"),
                question("Q4"), question("Q5"));
        assertRejected(questions, "最多 4 题");
    }

    @Test
    public void validQuestionsRaiseInputRequired() {
        Map<String, Object> question = question("要不要继续？");

        try {
            tool.execute(Map.of("questions", List.of(question)), context);
            Assert.fail("合法问询应抛 UserInputRequiredException");
        } catch (UserInputRequiredException expected) {
            Assert.assertEquals(1, expected.getQuestions().size());
            Assert.assertEquals("要不要继续？", expected.getQuestions().get(0).get("question"));
            Assert.assertEquals("继续", expected.getQuestions().get(0).get("header"));
        }
    }

    private void assertRejected(List<?> questions, String messagePart) {
        Object result = tool.execute(Map.of("questions", questions), context);

        ToolResultPayload payload = (ToolResultPayload) result;
        Assert.assertEquals(Boolean.TRUE, payload.getFailed());
        Assert.assertTrue(String.valueOf(payload.getErrorMsg()).contains(messagePart));
    }

    private static List<Map<String, Object>> options() {
        return List.of(
                Map.of("label", "是", "description", "继续执行"),
                Map.of("label", "否", "description", "停止"));
    }

    private static Map<String, Object> question(String text) {
        return questionWithOptions(text, options());
    }

    private static Map<String, Object> questionWithOptions(List<Map<String, Object>> options) {
        return questionWithOptions("要不要继续？", options);
    }

    private static Map<String, Object> questionWithOptions(String text, List<Map<String, Object>> options) {
        return Map.of("question", text, "header", "继续", "options", options);
    }
}
