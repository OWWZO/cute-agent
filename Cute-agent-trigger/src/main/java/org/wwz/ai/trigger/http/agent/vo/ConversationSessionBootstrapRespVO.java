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
 * 一次返回恢复会话所需的全部数据，替代原先 5 个串行请求（detail + files + 三类 HITL pending）。
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
}
