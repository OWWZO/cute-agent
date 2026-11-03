package org.wwz.ai.domain.agent.memory.ltm;

import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.util.List;

/**
 * 周期性后台整理。
 * Hermes 对齐：同模型 + 父 system/tools 原样 + 全量 messages 重放 + runtime memory 白名单。
 */
public interface BackgroundReviewService {

    default void maybeScheduleAfterSuccessTurn(String sessionId,
                                               String requestId,
                                               LtmOwner owner,
                                               String userQuery,
                                               String assistantSummary,
                                               List<Message> conversationSnapshot,
                                               String parentSystemPrompt) {
        maybeScheduleAfterSuccessTurn(
                sessionId,
                requestId,
                owner,
                userQuery,
                assistantSummary,
                conversationSnapshot,
                parentSystemPrompt,
                null);
    }

    /**
     * 成功回合后按频率决定是否调度后台 review。
     * <p>{@code parentTools} 允许为 {@code null}：7 参便捷重载即以 {@code null} 委托，
     * 表示「不带父工具运行」，实现不得直接解引用或遍历它。</p>
     */
    void maybeScheduleAfterSuccessTurn(String sessionId,
                                       String requestId,
                                       LtmOwner owner,
                                       String userQuery,
                                       String assistantSummary,
                                       List<Message> conversationSnapshot,
                                       String parentSystemPrompt,
                                       ToolCollection parentTools);
}
