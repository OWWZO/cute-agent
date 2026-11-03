package org.wwz.ai.domain.agent.browser.model;

/**
 * Transport-neutral channel owned by the inbound adapter.
 *
 * <p>Only typed commands cross this boundary. The implementation may wrap a
 * WebSocket, but that transport type never appears in this contract.</p>
 */
public interface BrowserRelayConnection {

    /**
     * 连接归属用户；可能为 {@code null}/空白（无法从会话属性解析时），
     * 调用方须按 {@code BrowserRelayOwnershipPolicy#owns} 的规则过滤。
     */
    String userId();

    /**
     * 连接标识，在连接生命周期内稳定且非空（当前实现为 WebSocket session id）。
     */
    String id();

    boolean isOpen();

    void send(BrowserCommand command) throws Exception;

    void terminate() throws Exception;
}
