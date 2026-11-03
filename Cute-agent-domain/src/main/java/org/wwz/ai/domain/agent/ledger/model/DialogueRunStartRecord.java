package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 创建 run 的命令对象。
 * <p>{@code requestId} 为必填：为空时 {@code AgentExecutionRecorderImpl#createRun} 直接返回
 * {@code null}，整次 run 不会落账本。{@code runUid} 为空时回退为 {@code requestId}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DialogueRunStartRecord {

    private String runUid;

    private String requestId;

    private String sessionId;

    private String userId;

    private String entryAgent;

    private String queryText;

    private LocalDateTime startedAt;
}
