package org.wwz.ai.domain.agent.ledger.tooloutput;

import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputPersistCommand;

/**
 * 结构化工具输出写入端口。
 * <p>将工具专属输出持久化到对应的 tool-output 表，避免把大对象重新塞回通用 invocation 记录。</p>
 */
public interface ToolOutputWriter {

    /**
     * 尽力写入：持久化失败只记录日志，不向调用方抛出；重复键/插入 0 行按幂等忽略。
     * {@code command} 或其 {@code structuredOutput} 为 {@code null} 时直接 no-op。
     * 适合请求收尾等不希望投影失败反噬主流程的场景。
     */
    void write(ToolOutputPersistCommand command);

    /**
     * 严格写入：持久化异常（如重复键）向调用方传播，插入 0 行时抛出
     * {@link IllegalStateException}。{@code command} 或其 {@code structuredOutput}
     * 为 {@code null} 时直接 no-op。
     *
     * @throws IllegalStateException 输出行未建立（重复或未生效）时
     */
    void writeOrThrow(ToolOutputPersistCommand command);
}
