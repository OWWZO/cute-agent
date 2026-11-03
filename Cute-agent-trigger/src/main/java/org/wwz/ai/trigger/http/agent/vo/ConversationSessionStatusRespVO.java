package org.wwz.ai.trigger.http.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话运行状态返回对象：供 Web 端低成本轮询「该会话是否新起了一轮」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSessionStatusRespVO {

    private String sessionId;

    /** RUNNING / SUCCESS / FAILED / TIMEOUT / STOPPED / WAITING_INPUT。 */
    private String status;

    /** 最近一轮的 requestId；未跑过时为 null。 */
    private String latestRequestId;

    /** 所属 IM 渠道绑定；非渠道会话为 null。 */
    private Long bindingId;
}
