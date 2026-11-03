package org.wwz.ai.domain.agent.ledger.tooloutput;

import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputView;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 结构化工具输出读取端口。
 * <p>读取的是 Execution Ledger 的 tool-output 投影，不负责生成新的执行事实。</p>
 */
public interface ToolOutputReader {

    /**
     * 按工具名 + 调用主键读取结构化输出。
     * <p>{@code toolName} 必须与持久化时写入的 toolName 完全一致（空白或未登记的工具按
     * 「无结构化输出」处理）；{@code toolInvocationId} 为 {@code null} 时返回
     * {@link Optional#empty()}。{@code empty} 同时覆盖「调用行不存在」「该工具不持久化
     * 结构化输出」「输出尚未投影」三种情况，调用方无法据此区分；返回值本身非 {@code null}。</p>
     */
    Optional<ToolStructuredOutput> readByInvocationId(String toolName, Long toolInvocationId);

    /**
     * 批量读取 rich tool 输出。传入 {@code null} artifact 时由实现自行批量补查；非空时复用调用方已加载的 artifact。
     * <p>返回 Map 的 key 为 {@link ToolInvocationView#getId()}，缺少某个 key 表示该调用没有
     * 结构化输出（未持久化或尚未投影），不是错误。必须返回非 {@code null} 的 Map
     * （{@code invocations} 为 {@code null}/空时返回空 Map）；同一 invocation 的结果必须与
     * {@link #readByInvocationId(String, Long)} 一致。</p>
     */
    Map<Long, ToolStructuredOutput> readByInvocationIds(List<ToolInvocationView> invocations,
                                                        List<ArtifactView> artifacts);

    /**
     * 按 requestId + toolCallId 跨输出表直接读取，返回持久化的 tool-output 行视图。
     * <p>这是缺少 {@code toolInvocationId} 的历史数据兼容路径：有主键时应优先使用
     * {@link #readByInvocationId(String, Long)}，两者不一致时以按主键读取的结果为准。
     * 仅在唯一命中时返回；多表命中说明关联字段不唯一，实现应返回 {@link Optional#empty()}
     * 而不是猜测。{@code requestId} / {@code toolCallId} 任一为空白时也返回 {@code empty}；
     * 返回值本身非 {@code null}。</p>
     */
    Optional<ToolOutputView> readDirect(String requestId, String toolCallId);
}
