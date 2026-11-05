package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量预登记工具调用的命令对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocationBatchStartRecord {

    private Long runId;

    private String requestId;

    private String sessionId;

    private Long llmInvocationId;

    private String agentName;

    private Integer stepNo;

    /** 非空；同一批内每个 item 的 {@code toolCallId} 必须唯一。 */
    private List<Item> items;

    /**
     * 单个工具调用项。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        private String toolCallId;

        /** 父 Agent 工具 toolCallId（子 Agent 内嵌工具） */
        private String parentToolCallId;

        private String subAgentId;

        private String subAgentType;

        private String subAgentDescription;

        /** 展示排序键，由生产者按批从 1 开始分配；不同批之间可能重复，不得假定全局唯一。 */
        private Integer dispatchIndex;

        private String toolName;

        private String toolProvider;

        private String inputJson;

        /**
         * 派发时刻；应由调用方统一从同一时钟取非空值。为空时写入层回退到
         * {@code LocalDateTime.now()}，混用会导致同批次耗时不一致。
         */
        private LocalDateTime startedAt;
    }
}
