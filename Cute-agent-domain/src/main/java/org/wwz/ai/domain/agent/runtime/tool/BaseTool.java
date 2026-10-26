package org.wwz.ai.domain.agent.runtime.tool;

import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

import java.util.Map;

/**
 * 工具基接口。
 *
 * <p><b>工具必须无状态：</b>所有运行时信息（requestId / 依赖端口 / 装配配置）一律从
 * {@code execute} 的 {@code context} 参数取，禁止在实例上持有 {@code AgentContext}
 * 或缓存到 static 字段——同一个实例会被多个 Agent 并发共享
 * （见 {@code StatelessToolSharingTest}）。</p>
 */
public interface BaseTool {

    String getName();

    /**
     * 带上下文的工具描述。
     *
     * <p>描述内容依赖运行时配置的工具（如 browser 的 bsk/opencli 命令面、ToolSearch 的
     * 延迟目录清单）覆写本方法，从 context 读取配置，从而无需在实例上绑定 AgentContext。</p>
     *
     * @param context 本次请求的 Agent 上下文，可能为 null
     */
    default String getDescription(AgentContext context) {
        return getDescription();
    }

    /**
     * 无上下文的工具描述。描述与运行时配置无关的工具只实现本方法。
     */
    String getDescription();

    /**
     * 带上下文的参数 schema。
     *
     * <p>参数说明依赖运行时配置的工具（如 browser 的 bsk/opencli 命令面）覆写本方法，
     * 从 context 读取配置。</p>
     *
     * @param context 本次请求的 Agent 上下文，可能为 null
     */
    default Map<String, Object> toParams(AgentContext context) {
        return toParams();
    }

    /**
     * 无上下文的参数 schema。schema 与运行时配置无关的工具只实现本方法。
     */
    Map<String, Object> toParams();

    /**
     * 显式接收上下文的执行入口。
     *
     * @param input   工具入参
     * @param context 本次调用的 Agent 上下文
     */
    Object execute(Object input, AgentContext context);
}
