package org.wwz.ai.domain.agent.adapter.port;

/**
 * Agent 浏览器会话端口。
 *
 * <p>契约（与当前唯一实现 {@code AgentBrowserSessionAdapter} 一致）：</p>
 * <ul>
 *   <li>{@code userId} 为 null 或空白时，所有方法统一抛 {@link IllegalArgumentException}
 *       （实现会先 trim）；</li>
 *   <li>{@link #resolveForUser(String)} / {@link #ensureLiveView(String)} 不返回 null：
 *       未配置、远端失败或响应缺少必需字段时抛 {@link IllegalStateException}；</li>
 *   <li>{@link #statusForUser(String)} 不返回 null，无映射时返回
 *       {@link AgentBrowserSessionStatus#absent()}；</li>
 *   <li>{@link #deleteForUser(String)} 幂等：无本地映射时为空操作，远端 404 视为成功；
 *       其它远端失败抛 {@link IllegalStateException}。</li>
 * </ul>
 */
public interface AgentBrowserSessionPort {

    /**
     * 是否为全局能力探测：只表示当前部署是否配置了浏览器会话适配器，与具体用户无关。
     * <p>返回 {@code true} 不代表 {@link #resolveForUser(String)} / {@link #ensureLiveView(String)}
     * 对任意 {@code userId} 都会成功——适配器仍可能因用户未开通或会话不可用而失败，
     * 调用方必须继续处理按用户维度的不可用情况。</p>
     */
    boolean isConfigured();

    AgentBrowserSession resolveForUser(String userId);

    AgentBrowserSessionStatus statusForUser(String userId);

    AgentBrowserLiveView ensureLiveView(String userId);

    void deleteForUser(String userId);
}
