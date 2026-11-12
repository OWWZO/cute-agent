package org.wwz.ai.infrastructure.inbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPayloads;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPendingHint;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPort;
import org.wwz.ai.infrastructure.dao.cute.ISessionInboxDao;
import org.wwz.ai.infrastructure.dao.cute.po.SessionInboxPO;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Session Inbox 持久化实现。认领使用行锁，ACK 只接受本次 run 持有的 CLAIMED 行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyBatisSessionInboxPersistence implements SessionInboxPort {

    private static final long CLAIM_LEASE_MS = 30_000L;
    private static final int CLAIM_BATCH_LIMIT = 100;

    private final ISessionInboxDao sessionInboxDao;

    @Override
    public SessionInboxItem offer(SessionInboxItem item) {
        SessionInboxPendingHint.WriteState write = SessionInboxPendingHint.beginWrite();
        boolean wrotePending = false;
        try {
            SessionInboxItem stored = offerInternal(item);
            wrotePending = stored != null
                    && SessionInboxItem.STATE_PENDING.equals(stored.getState());
            return stored;
        } finally {
            SessionInboxPendingHint.finishWrite(
                    item == null ? null : item.getSessionId(), write, wrotePending);
        }
    }

    private SessionInboxItem offerInternal(SessionInboxItem item) {
        if (item == null || StringUtils.isBlank(item.getSessionId())
                || StringUtils.isBlank(item.getIdempotencyKey())
                || StringUtils.isBlank(item.getKind())
                || StringUtils.isBlank(item.getVisibility())) {
            return null;
        }
        String sessionId = StringUtils.trim(item.getSessionId());
        String idempotencyKey = StringUtils.trim(item.getIdempotencyKey());
        if (sessionId.length() > 128 || idempotencyKey.length() > 160
                || StringUtils.length(StringUtils.trim(item.getSourceRunId())) > 64) {
            log.warn("session inbox offer rejected: value too long sessionId={} keyLength={}",
                    sessionId, idempotencyKey.length());
            return null;
        }
        String kind = StringUtils.trim(item.getKind());
        String visibility = StringUtils.trim(item.getVisibility());
        boolean invalidKind = !SessionInboxItem.KIND_USER_MESSAGE.equals(kind)
                && !SessionInboxItem.KIND_BACKGROUND_NOTIFICATION.equals(kind);
        boolean invalidVisibility = !SessionInboxItem.VISIBILITY_ALL.equals(visibility)
                && !SessionInboxItem.VISIBILITY_MODEL_ONLY.equals(visibility);
        if (invalidKind || invalidVisibility) {
            log.warn("session inbox offer rejected: invalid kind/visibility sessionId={} kind={} visibility={}",
                    sessionId, kind, visibility);
            return null;
        }
        SessionInboxPO row = toPo(item);
        sessionInboxDao.insertIfAbsent(row);
        return toDomain(sessionInboxDao.selectByKey(
                sessionId,
                idempotencyKey));
    }

    @Override
    @Transactional
    public List<SessionInboxItem> claimPending(String sessionId, String runId, String kind, int limit) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(runId) || StringUtils.isBlank(kind)) {
            return List.of();
        }
        int actualLimit = limit <= 0 ? CLAIM_BATCH_LIMIT : Math.min(limit, CLAIM_BATCH_LIMIT);
        long now = System.currentTimeMillis();
        List<SessionInboxPO> rows = sessionInboxDao.selectClaimableForUpdate(
                StringUtils.trim(sessionId), kind, StringUtils.trim(runId), now, actualLimit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(SessionInboxPO::getId).toList();
        int claimedCount = sessionInboxDao.markClaimed(
                ids, StringUtils.trim(runId), now + CLAIM_LEASE_MS, now);
        if (claimedCount != ids.size()) {
            throw new IllegalStateException("session inbox claim changed concurrently");
        }
        List<SessionInboxItem> claimed = new ArrayList<>(rows.size());
        for (SessionInboxPO row : rows) {
            row.setState(SessionInboxItem.STATE_CLAIMED);
            row.setClaimedByRunId(StringUtils.trim(runId));
            row.setLeaseUntilMs(now + CLAIM_LEASE_MS);
            claimed.add(toDomain(row));
        }
        return claimed;
    }

    @Override
    public int ackDelivered(String sessionId, Collection<Long> itemIds, String runId) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(runId)
                || itemIds == null || itemIds.isEmpty()) {
            return 0;
        }
        return sessionInboxDao.ackDelivered(itemIds, StringUtils.trim(runId), System.currentTimeMillis());
    }

    @Override
    public int releaseClaims(String sessionId, String runId) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(runId)) {
            return 0;
        }
        SessionInboxPendingHint.WriteState write = SessionInboxPendingHint.beginWrite();
        boolean released = false;
        try {
            int count = sessionInboxDao.releaseClaims(
                    StringUtils.trim(sessionId), StringUtils.trim(runId));
            released = count > 0;
            return count;
        } finally {
            SessionInboxPendingHint.finishWrite(sessionId, write, released);
        }
    }

    @Override
    public int holdPendingUserMessages(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return 0;
        }
        SessionInboxPendingHint.WriteState write = SessionInboxPendingHint.beginWrite();
        try {
            return sessionInboxDao.holdPendingUserMessages(StringUtils.trim(sessionId));
        } finally {
            SessionInboxPendingHint.finishWrite(sessionId, write, false);
        }
    }

    @Override
    public int releaseHeldUserMessages(String sessionId, String idempotencyKey) {
        if (StringUtils.isBlank(sessionId)) {
            return 0;
        }
        SessionInboxPendingHint.WriteState write = SessionInboxPendingHint.beginWrite();
        boolean released = false;
        try {
            int count = sessionInboxDao.releaseHeldUserMessages(
                    StringUtils.trim(sessionId),
                    StringUtils.trimToNull(idempotencyKey));
            released = count > 0;
            return count;
        } finally {
            SessionInboxPendingHint.finishWrite(sessionId, write, released);
        }
    }

    @Override
    public boolean hasOutstanding(String sessionId) {
        return StringUtils.isNotBlank(sessionId)
                && sessionInboxDao.countOutstanding(StringUtils.trim(sessionId)) > 0;
    }

    @Override
    public boolean hasDeliverableKind(String sessionId, String kind) {
        return StringUtils.isNotBlank(sessionId)
                && StringUtils.isNotBlank(kind)
                && sessionInboxDao.countDeliverableKind(
                        StringUtils.trim(sessionId), kind, System.currentTimeMillis()) > 0;
    }

    @Override
    public boolean hasHeldUserMessage(String sessionId) {
        return StringUtils.isNotBlank(sessionId)
                && sessionInboxDao.countHeldUserMessages(StringUtils.trim(sessionId)) > 0;
    }

    @Override
    public String peekFirstDeliverableUserText(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        SessionInboxPO row = sessionInboxDao.selectFirstDeliverableUser(
                StringUtils.trim(sessionId), System.currentTimeMillis());
        return row == null ? null : SessionInboxPayloads.userText(row.getPayloadJson());
    }

    private static SessionInboxPO toPo(SessionInboxItem item) {
        SessionInboxPO row = new SessionInboxPO();
        row.setSessionId(StringUtils.trim(item.getSessionId()));
        row.setKind(StringUtils.trim(item.getKind()));
        row.setVisibility(StringUtils.trim(item.getVisibility()));
        row.setState(StringUtils.defaultIfBlank(item.getState(), SessionInboxItem.STATE_PENDING));
        row.setIdempotencyKey(StringUtils.trim(item.getIdempotencyKey()));
        row.setPayloadJson(item.getPayloadJson());
        row.setSourceRunId(StringUtils.trimToNull(item.getSourceRunId()));
        row.setCreatedAtMs(item.getCreatedAtMs() == null
                ? System.currentTimeMillis() : item.getCreatedAtMs());
        return row;
    }

    private static SessionInboxItem toDomain(SessionInboxPO row) {
        if (row == null) {
            return null;
        }
        return SessionInboxItem.builder()
                .id(row.getId())
                .sessionId(row.getSessionId())
                .kind(row.getKind())
                .visibility(row.getVisibility())
                .state(row.getState())
                .idempotencyKey(row.getIdempotencyKey())
                .payloadJson(row.getPayloadJson())
                .sourceRunId(row.getSourceRunId())
                .claimedByRunId(row.getClaimedByRunId())
                .leaseUntilMs(row.getLeaseUntilMs())
                .createdAtMs(row.getCreatedAtMs())
                .deliveredAtMs(row.getDeliveredAtMs())
                .build();
    }
}
