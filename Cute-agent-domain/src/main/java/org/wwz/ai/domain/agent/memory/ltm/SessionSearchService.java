package org.wwz.ai.domain.agent.memory.ltm;

/**
 * 情节按需检索。实现应查询持久化的消息级历史投影，并保留压缩前的失效投影。
 * Execution Ledger 仍是执行事实源；本接口只负责面向 Agent 的历史回忆视图。
 */
public interface SessionSearchService {

    /**
     * Hermes 风格的消息历史检索。实现根据请求参数选择 discovery、scroll、read 或 browse。
     * <p>
     * {@code request == null} 与「字段全空的 request」语义一致：浏览当前用户最近的会话，
     * 不是错误，也不代表「搜索不到」。{@code limit == null} 用实现默认值；
     * {@code limit == 0} 表示调用方未指定上限，由实现决定（通常回退默认值）；
     * 显式负数由实现按参数错误处理。
     * <p>
     * 这是给只实现五参版本的旧适配器用的兜底：五参签名没有 scroll / 角色过滤能力，
     * 遇到这类请求必须显式返回错误，不能静默降级成另一种模式。
     */
    default String search(SessionSearchRequest request) {
        if (request == null) {
            return search(null, null, null, 0, null);
        }
        if (request.getAroundMessageId() != null || request.getWindow() != null) {
            return unsupported("around_message_id/window");
        }
        if (request.getRoleFilter() != null && !request.getRoleFilter().isBlank()) {
            return unsupported("role_filter");
        }
        int limit = request.getLimit() == null ? 0 : request.getLimit();
        String requestedSessionId = request.getSessionId();
        String sessionId = requestedSessionId != null && !requestedSessionId.isBlank()
                ? requestedSessionId.trim()
                : request.getCurrentSessionId();
        return search(sessionId, request.getUserId(), request.getQuery(), limit, request.getScope());
    }

    /**
     * @param sessionId 当前会话（scope=session 时必填；scope=user 时用于排序/标注当前会话）
     * @param userId    用户归属（scope=user 时优先；可为空则回退本会话）
     * @param query     关键词；为空时表示不过滤关键词
     * @param limit     最大命中；{@code 0} 表示未指定，由实现回退默认值
     * @param scope     session=仅本会话；user=该 userId 下跨会话
     */
    String search(String sessionId, String userId, String query, int limit, String scope);

    private static String unsupported(String option) {
        return "{\"success\":false,\"error\":\"legacy session search adapter does not support "
                + option + "\"}";
    }
}
