package org.wwz.ai.domain.agent.catalog.port;

import org.wwz.ai.domain.agent.catalog.model.McpRuntimeReloadResult;

/**
 * MCP 配置写入后的运行时刷新端口。
 */
public interface IMcpRuntimeReloadPort {

    /**
     * 按当前配置重新加载已启用的 MCP。
     * <p>失败通过返回值表达：{@link McpRuntimeReloadResult#succeeded()} 表示成功，
     * {@link McpRuntimeReloadResult#failed(String)} 表示失败；实现不得抛出异常，也不得返回 null。
     * 端口未声明幂等/并发保证：每次调用都会执行一次完整 reload；当前实现
     * （{@code McpRegistry.reload()}）以 {@code synchronized} 串行化，并发调用会被排队。</p>
     *
     * @return 非 null 的刷新结果
     */
    McpRuntimeReloadResult reloadEnabledMcps();
}
