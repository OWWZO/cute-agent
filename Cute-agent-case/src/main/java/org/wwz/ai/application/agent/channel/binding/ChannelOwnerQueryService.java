package org.wwz.ai.application.agent.channel.binding;

import lombok.Data;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * owner 查看自己 bot 下的会话列表。
 * <p>只返回会话元信息，<b>不返回</b>对方 openid / 昵称——owner 能看到对话内容，
 * 但不应借这个入口反查陌生人身份。</p>
 */
public class ChannelOwnerQueryService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LIMIT = 100;

    private final ChannelBindingApplicationService bindingService;
    private final IExecutionLedgerReadRepository ledgerReadRepository;

    public ChannelOwnerQueryService(ChannelBindingApplicationService bindingService,
                                    IExecutionLedgerReadRepository ledgerReadRepository) {
        this.bindingService = bindingService;
        this.ledgerReadRepository = ledgerReadRepository;
    }

    /**
     * 查询绑定下的会话列表。
     * <p>{@code limit} 会被归一到 {@code [1, 100]}；非正数按 1 处理，大于 100 时按 100 处理。</p>
     */
    public SessionListView listSessions(Long bindingId, String ownerUserId, int limit) {
        ChannelBinding binding = bindingService.requireOwned(bindingId, ownerUserId);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<SessionItem> sessions = ledgerReadRepository
                .querySessionsByBindingId(binding.getId(), safeLimit)
                .stream()
                .map(ChannelOwnerQueryService::toItem)
                .toList();
        return new SessionListView(sessions);
    }

    private static SessionItem toItem(DialogueSessionView view) {
        SessionItem item = new SessionItem();
        item.setSessionId(view.getSessionId());
        item.setTitle(view.getTitle());
        item.setRunCount(view.getRunCount());
        item.setLastActiveAt(view.getLastActiveAt() == null ? null : view.getLastActiveAt().format(TIME_FORMAT));
        return item;
    }

    @Data
    public static class SessionItem {
        private String sessionId;
        private String title;
        private Integer runCount;
        private String lastActiveAt;
    }

    public record SessionListView(List<SessionItem> sessions) {
    }
}
