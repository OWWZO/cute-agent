package org.wwz.ai.domain.agent.ledger;

import org.wwz.ai.domain.agent.ledger.model.ArtifactRecordCommand;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunStartRecord;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationFinishRecord;

import java.util.List;
import java.util.Map;

/**
 * 执行账本统一写入契约。
 */
public interface AgentExecutionRecorder {

    /**
     * 创建 run。返回新 run 的主键；以下情况返回 {@code null}：入参为 {@code null}、
     * {@code requestId} 或 {@code sessionId} 为空白、会话头解析失败、落库失败。
     * <p>{@code null} 表示 run 未成功建立（落库失败时实现会尽力把可能已写入的行补偿为失败终态），
     * 调用方不得直接拆箱，也不要把 {@code null} 当作有效 runId 继续写入子事实。</p>
     */
    Long createRun(DialogueRunStartRecord record);

    /**
     * 结束 run。fail-open：实现记录并吞掉所有异常，调用方无法感知失败；
     * 失败时 run 可能永久停留在 RUNNING，由启动对账（{@code BackgroundRunReconciler}）兜底收敛。
     * <p>入参不完整（record / runId / requestId / status 为空）或 run 不存在时直接 no-op。</p>
     */
    void finishRun(DialogueRunFinishRecord record);

    /**
     * 创建 LLM 调用账本行。返回新行主键；入参为 {@code null}、{@code runId} 为空、
     * {@code agentName} 为空白或落库失败时返回 {@code null}。
     * 调用方不得直接拆箱；id 为 {@code null} 时后续 finish 会直接 no-op。
     */
    Long createLlmInvocation(LlmInvocationStartRecord record);

    /**
     * 结束 LLM 调用。fail-open：异常被记录后吞掉，调用方无法感知失败，
     * 对应行可能停留在 RUNNING。
     * <p>{@code llmInvocationId} 为空时直接 no-op。</p>
     */
    void finishLlmInvocation(LlmInvocationFinishRecord record);

    /**
     * 批量创建工具调用账本行。
     * <p>返回值 key 为 {@code toolCallId}，value 为新建行的 {@code toolInvocationId}；
     * 入参为 {@code null}/空或 {@code runId} 为空时返回空 Map（绝不返回 {@code null}）。
     * 允许部分成功：{@code toolCallId} 为空的 item 会被跳过，单条写入失败也不会中断其余 item，
     * 因此返回 Map 可能小于 {@code record.getItems()}。调用方按键取值前必须先判空，
     * 不要假定每个 toolCallId 都存在。</p>
     */
    Map<String, Long> createToolInvocations(ToolInvocationBatchStartRecord record);

    /**
     * 结束工具调用。fail-open：异常被记录后吞掉，调用方无法感知失败，
     * 对应行可能停留在 RUNNING。
     * <p>{@code toolInvocationId} 为空时直接 no-op；{@code finishedAt} 为 {@code null}
     * 表示 provisional 后台 ACK 写，此时行仍保持 RUNNING，不能当作已结算。</p>
     */
    void finishToolInvocation(ToolInvocationFinishRecord record);

    /**
     * 写入产物，fail-open：实现记录并吞掉写入异常，调用方无法感知丢失。
     * 适合请求收尾等不希望产物失败反噬主流程的场景；{@code fileName} 为空的记录会被跳过。
     */
    void recordArtifacts(List<ArtifactRecordCommand> records);

    /**
     * 写入产物，严格模式：批量插入行数少于待写入行数时抛出 {@link IllegalStateException}，
     * 其余异常同样向调用方传播。需要确保产物可回放时使用。
     */
    void recordArtifactsOrThrow(List<ArtifactRecordCommand> records);
}
