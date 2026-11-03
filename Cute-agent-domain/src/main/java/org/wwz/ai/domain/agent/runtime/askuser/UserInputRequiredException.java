package org.wwz.ai.domain.agent.runtime.askuser;

import java.util.List;
import java.util.Map;

/**
 * AskUserQuestion 让步信号：不阻塞线程，由上层持久化断点并结束当前 run。
 * <p>{@code questions} 只做浅拷贝，问题内的 Map 仍与调用方共享，构造后不得再修改；
 * {@code toolCallId} 可为 {@code null}（无产物来源时）。</p>
 */
public class UserInputRequiredException extends RuntimeException {

    private final List<Map<String, Object>> questions;
    private final String toolCallId;

    public UserInputRequiredException(List<Map<String, Object>> questions, String toolCallId) {
        super("USER_INPUT_REQUIRED");
        this.questions = questions == null ? List.of() : List.copyOf(questions);
        this.toolCallId = toolCallId;
    }

    public List<Map<String, Object>> getQuestions() {
        return questions;
    }

    public String getToolCallId() {
        return toolCallId;
    }
}
