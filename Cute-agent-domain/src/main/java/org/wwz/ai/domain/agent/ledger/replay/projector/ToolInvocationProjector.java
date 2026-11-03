package org.wwz.ai.domain.agent.ledger.replay.projector;

import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;

import java.util.List;

/**
 * 单工具账本投影器。
 */
public interface ToolInvocationProjector {

    /**
     * 是否支持该工具。
     * <p>契约：</p>
     * <ul>
     *   <li>{@code toolName} 可能为 {@code null}（调用行缺失或 toolName 未记录），实现必须容忍
     *       并返回 {@code false}，不得抛 NPE；</li>
     *   <li>按 toolName 精确匹配，不做前缀/通配匹配，避免专用 projector 抢走默认 projector 的工具；</li>
     *   <li>注册表按注入顺序遍历并取<b>第一个</b>匹配项（后续重叠匹配只打 warn），
     *       因此多个 projector 的谓词不允许重叠。</li>
     * </ul>
     */
    boolean supports(String toolName);

    /**
     * 把工具调用事实投影成前端可消费 eventData。
     * <p>契约：</p>
     * <ul>
     *   <li>{@code invocation} 可能为 {@code null}；{@code artifacts} 可能为 {@code null} 或空；
     *       {@code state} 由注册表提供且非 {@code null}，是整轮回放共享的可变状态；</li>
     *   <li>必须返回非 {@code null} 的列表；返回空列表表示该工具不产生回放事件
     *       （调用方直接 {@code addAll}，返回 {@code null} 会 NPE）；</li>
     *   <li>每个 invocation 只会被投影一次。对 {@code state} 的写入仅限 taskId / 顺序计数等
     *       回放游标（如 {@code renewTaskId()} / {@code getAndIncrOrder(...)}），
     *       不得写入其它业务状态，否则会破坏回放顺序。</li>
     * </ul>
     */
    List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                       List<ArtifactView> artifacts,
                                       AgentStreamAccumulator state);
}
