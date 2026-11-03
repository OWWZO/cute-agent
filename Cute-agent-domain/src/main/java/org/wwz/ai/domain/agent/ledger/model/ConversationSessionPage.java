package org.wwz.ai.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A page of recent conversation sessions.
 * <p>分页不变量：{@code hasMore=true} 时必须同时提供非空 {@code nextCursor}；
 * {@code hasMore=false} 时 {@code nextCursor} 必须为 {@code null}。
 * 违反该约定会让客户端提前停止或反复请求同一页。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSessionPage {

    private List<DialogueSessionView> sessions;

    private String nextCursor;

    private boolean hasMore;
}
