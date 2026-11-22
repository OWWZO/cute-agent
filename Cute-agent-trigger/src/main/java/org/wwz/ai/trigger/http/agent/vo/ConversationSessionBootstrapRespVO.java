package org.wwz.ai.trigger.http.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 会话切换 bootstrap 聚合响应。
 * <p>
 * 一次返回恢复会话所需的全部数据（detail + files + Todo + 三类 HITL pending）。
 * 末尾 turn 的 run 回放按字节预算内联，超预算时 {@code replaysIncluded=false}，前端走并行补拉。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSessionBootstrapRespVO {

    private ConversationHistoryPageRespVO detail;

    private List<ArtifactReferenceRespVO> files;

    private List<Map<String, Object>> askUser;

    private List<Map<String, Object>> desktopControl;

    private List<Map<String, Object>> planApproval;

    /** 会话当前 Todo 快照；与实时 session_tasks 使用同一结构。 */
    private Map<String, Object> sessionTasks;

    /** 末尾 turn 的 run 回放，key = requestId。 */
    private Map<String, ConversationRunReplayRespVO> replays;

    /** false 表示未内联（超预算或无可用回放），前端需走并行补拉。 */
    private boolean replaysIncluded;
}
