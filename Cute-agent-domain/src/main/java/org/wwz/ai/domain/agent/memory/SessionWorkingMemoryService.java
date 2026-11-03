package org.wwz.ai.domain.agent.memory;

import org.wwz.ai.domain.agent.runtime.dto.Message;

import java.util.List;

/**
 * 跨轮工作记忆投影服务（working_memory_*）。
 * load 供下一轮 preload；persist 在 run 成功结束后写入本轮 delta。
 * 读写按 {@code sessionId + memoryScope} 隔离；子会话已统一使用 {@link WorkingMemoryScopes#MAIN}，
 * 历史 {@code sub:{agentId}} 作用域只存在于迁移前的存量数据，仅可只读兼容，不应再作为写入 scope。
 */
public interface SessionWorkingMemoryService {

    /**
     * 加载 session 主 scope（main）内 READY 消息链（排除当前 request）。
     * <p>{@code currentRequestId} 为 {@code null} 或不在 READY 链中时，返回完整 READY 链，不做排除。</p>
     */
    default List<Message> loadReadyMessages(String sessionId, String currentRequestId) {
        return loadReadyMessages(sessionId, WorkingMemoryScopes.MAIN, currentRequestId);
    }

    /**
     * 加载指定 scope 的 READY 消息链。
     * <p>scope 取值由 {@link WorkingMemoryScopes} 约定：主链路用 {@code main}，
     * 实现写入前应调用 {@link WorkingMemoryScopes#normalize(String)} 归一空白值；
     * 未归一的自定义值会形成独立记忆分区，写入成功但主视图读不到。</p>
     * <p>返回值是调用方私有的新列表，可安全修改；空结果返回不可变空列表，不返回 {@code null}。</p>
     */
    List<Message> loadReadyMessages(String sessionId, String memoryScope, String currentRequestId);

    /**
     * 持久化本轮 delta 到主 scope。
     */
    default boolean persistTurn(String sessionId,
                                String requestId,
                                Long runId,
                                String entryAgent,
                                List<Message> turnMessages) {
        return persistTurn(sessionId, WorkingMemoryScopes.MAIN, requestId, runId, entryAgent, turnMessages);
    }

    /**
     * 持久化本轮 delta 到指定 scope。
     * <p><b>幂等契约：</b>以 {@code (sessionId, memoryScope, requestId)} 为唯一轮次键。
     * 同一 requestId 重复调用不得追加第二轮 delta：已存在 READY turn 时应直接返回，
     * 保证 run 重试或部分失败后的补偿调用不会污染下一轮 preload。</p>
     * <p>实现需保证「查重 + 插入」对同一 requestId 的并发调用不产生两条 turn
     * （依赖存储层唯一约束或调用方串行化）。</p>
     * <p><b>入参所有权：</b>实现不得在调用返回后继续持有或修改 {@code turnMessages}；
     * 如需异步/延迟使用，必须自行复制。</p>
     *
     * @return true 表示本轮消息已经（或无需）落库；false 表示持久化失败，调用方不得据此标记通知已投递
     */
    boolean persistTurn(String sessionId,
                        String memoryScope,
                        String requestId,
                        Long runId,
                        String entryAgent,
                        List<Message> turnMessages);

    /**
     * 用压缩后的投影整体替换主 scope READY turns。
     */
    default void replaceReadyProjection(String sessionId, String compactRequestId, List<Message> compactedMessages) {
        replaceReadyProjection(sessionId, WorkingMemoryScopes.MAIN, compactRequestId, compactedMessages);
    }

    /**
     * 用压缩后的投影整体替换指定 scope 的 READY turns（旧 turns 标 INVALID）。
     * 仅服务 compaction；不改变 Execution Ledger。
     * <p>实现必须把「旧 turns 标 INVALID + 写入新投影」放在同一事务内原子完成；
     * 失败时必须抛出异常，不得吞掉错误留下半替换状态。</p>
     * <p><b>入参所有权：</b>实现不得在调用返回后继续持有或修改 {@code compactedMessages}。</p>
     */
    void replaceReadyProjection(String sessionId,
                                String memoryScope,
                                String compactRequestId,
                                List<Message> compactedMessages);
}
