package org.wwz.ai.domain.agent.adapter.port;

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
