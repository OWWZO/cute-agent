package org.wwz.ai.domain.agent.memory;

import org.wwz.ai.domain.agent.memory.ltm.LtmOwner;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.llm.ContextTokenTracker;
import org.wwz.ai.domain.agent.runtime.llm.PromptShape;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.util.List;

/**
 * 会话工作记忆压缩服务。
 * 入口时机：
 * 1) applyIfNeeded 保留兼容，不再由 case 层 pre-run 调用
 * 2) BaseAgent 每 step 主模型调用前（mid-run）——第一次真正压缩检查
 * 压缩按 memoryScope 隔离，默认 main。
 */
public interface SessionContextCompactionService {

    /**
     * mid-run flush 所需的父回合对齐信息（Hermes prompt-cache parity）。
     */
    record LtmFlushParity(
            String parentSystemPrompt,
            ToolCollection parentTools,
            LtmOwner owner
    ) {
        public static LtmFlushParity of(String system, ToolCollection tools, LtmOwner owner) {
            return new LtmFlushParity(system, tools, owner);
        }
    }

    /**
     * 若超阈值则压缩；失败时降级为 drop-oldest。默认 main scope。
     */
    default List<Message> applyIfNeeded(String sessionId, String requestId, List<Message> messages) {
        return applyIfNeeded(sessionId, WorkingMemoryScopes.MAIN, requestId, messages);
    }

    /**
     * 若超阈值则按给定 scope 压缩；未压缩时原样返回入参。
     * <p><b>列表所有权：</b>实现不得原地修改 {@code messages}；未发生压缩时返回入参本身
     * （同一实例或等值列表），发生压缩时返回新的 List。调用方以「返回值与入参相同或相等」
     * 判断本轮是否压缩，原地修改会被误判为未变化。</p>
     */
    List<Message> applyIfNeeded(String sessionId, String memoryScope, String requestId, List<Message> messages);

    /**
     * mid-run 压缩：可单独开关；默认 main scope。
     */
    default List<Message> applyIfNeededMidRun(String sessionId, String requestId, List<Message> messages) {
        return applyIfNeededMidRun(sessionId, WorkingMemoryScopes.MAIN, requestId, messages, null);
    }

    default List<Message> applyIfNeededMidRun(String sessionId,
                                              String memoryScope,
                                              String requestId,
                                              List<Message> messages) {
        return applyIfNeededMidRun(sessionId, memoryScope, requestId, messages, null);
    }

    default List<Message> applyIfNeededMidRun(String sessionId,
                                              String memoryScope,
                                              String requestId,
                                              List<Message> messages,
                                              LtmFlushParity flushParity) {
        return applyIfNeededMidRun(sessionId, memoryScope, requestId, messages, flushParity, null, null);
    }

    /**
     * mid-run 压缩的唯一实现点：短重载都委托到这里。
     * <p>实现必须覆写本方法；{@code flushParity}/{@code promptShape}/{@code tokenSnapshot}
     * 是 Hermes prompt-cache 对齐所需的 mid-run 上下文，不得静默丢弃。</p>
     * <p>上述三个上下文参数均可为 {@code null}：3/4/5 参重载会以 {@code null} 传入缺失项。
     * 实现必须逐项判空；缺失时按「无该上下文」处理并回退到本地估算，不得直接解引用。</p>
     * <p><b>列表所有权：</b>与 {@code applyIfNeeded} 相同——不得原地修改入参；
     * 未压缩时原样返回入参，压缩时返回新的 List。</p>
     */
    List<Message> applyIfNeededMidRun(String sessionId,
                                      String memoryScope,
                                      String requestId,
                                      List<Message> messages,
                                      LtmFlushParity flushParity,
                                      PromptShape promptShape,
                                      ContextTokenTracker.Snapshot tokenSnapshot);
}
