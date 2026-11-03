package org.wwz.ai.domain.agent.memory;

import org.wwz.ai.domain.agent.runtime.dto.Message;

import java.util.List;

/**
 * 单会话上下文记忆服务。
 * <p>
 * 默认主路径：从执行账本 hydrate 为模型可见的 Message 链（prompt-cache 友好）。
 * {@link #buildHistoryDialogue} 仅兼容/调试，不再作为 LLM 主输入。
 */
public interface SessionContextMemoryService {

    /**
     * @deprecated 仅调试/legacy；默认请用 {@link #hydrateWorkingMessages(String, String)}。
     */
    @Deprecated
    String buildHistoryDialogue(String sessionId, String currentRequestId);

    /**
     * 将会话历史 hydrate 为可 preload 的 Message 列表（不含当前 request）。
     * 顺序稳定：按 run 时间序，run 内按 llm invocation 序，工具按 dispatch 序。
     * <p>{@code sessionId} 为空白时返回空列表；{@code currentRequestId} 为 {@code null} 或空白时
     * 不做排除，返回全部历史；历史为空时返回空列表而非 {@code null}。</p>
     * <p>返回值是每次调用新建的可变列表，不与账本或共享集合关联，调用方可安全修改。</p>
     */
    List<Message> hydrateWorkingMessages(String sessionId, String currentRequestId);
}
