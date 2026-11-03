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

    /**
     * 发送一条 typed 命令。
     *
     * <p>传输层失败（对端已断开、写入中断等）应由实现以 {@link java.io.IOException} 抛出；
     * 命令为空等编程错误保持非受检异常，不被包装成传输失败。</p>
     */
    void send(BrowserCommand command) throws Exception;

    /**
     * 终止连接并释放底层资源。
     *
     * <p>契约：</p>
     * <ul>
     *   <li><b>幂等</b>：重复调用不得抛异常；连接已关闭时直接返回；</li>
     *   <li><b>可并发</b>：允许与 {@link #send(BrowserCommand)} 并发调用，实现须保证关闭过程线程安全；</li>
     *   <li><b>异常语义</b>：传输层关闭失败以 {@link java.io.IOException} 抛出，编程错误保持非受检异常。</li>
     * </ul>
     */
    void terminate() throws Exception;
}
