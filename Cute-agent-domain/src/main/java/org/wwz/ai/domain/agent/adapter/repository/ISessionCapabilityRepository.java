package org.wwz.ai.domain.agent.adapter.repository;

import java.util.List;
import java.util.Map;

/**
 * 会话能力差集：只存用户显式改过的 skill/mcp 开关。
 */
public interface ISessionCapabilityRepository {

    /**
     * 读取会话的能力差集。
     *
     * @return kind → (refId → enabled)。没有覆盖时必须返回空 Map，<b>不得返回 null</b>；
     *         返回的 Map（含嵌套 Map）是调用方独占的独立快照，不得修改；实现也不得返回
     *         会被后续写入改动的内部 Map
     */
    Map<String, Map<String, Boolean>> findOverrides(String sessionId);

    /**
     * 写入单条能力覆盖。
     *
     * @param kind 能力类型，只允许 {@code SessionCapabilityService.KIND_SKILL}（"skill"）或
     *             {@code KIND_MCP}（"mcp"）；调用方必须先 trim 并转为小写，实现按原样落库，
     *             不做归一化
     * @param refId 能力标识（skill id / mcp id），不能为空
     */
    void upsert(String sessionId, String kind, String refId, boolean enabled);

    List<SessionCapabilityRow> listBySession(String sessionId);

    record SessionCapabilityRow(String kind, String refId, boolean enabled) {
    }
}
