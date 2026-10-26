package org.wwz.ai.domain.agent.runtime.tool;

import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

import java.util.Map;

/**
 * 工具基接口
 */
public interface BaseTool {
    String getName();

    String getDescription();

    /**
     * 带上下文的工具描述。
     *
     * <p>默认回落到 {@link #getDescription()}。描述内容依赖运行时配置的工具
     * （如 browser 的 bsk/opencli 命令面、ToolSearch 的延迟目录清单）覆写本方法，
     * 从 context 读取配置，从而无需在实例上绑定 AgentContext。</p>
     *
     * @param context 本次请求的 Agent 上下文，可能为 null
     */
    default String getDescription(AgentContext context) {
        return getDescription();
    }

    Map<String, Object> toParams();

    /**
     * 带上下文的参数 schema。
     *
     * <p>默认回落到 {@link #toParams()}。参数说明依赖运行时配置的工具
     * （如 browser 的 bsk/opencli 命令面）覆写本方法，从 context 读取配置。</p>
     *
     * @param context 本次请求的 Agent 上下文，可能为 null
     */
    default Map<String, Object> toParams(AgentContext context) {
        return toParams();
    }

    /**
     * 旧签名：不携带上下文的执行入口。
     *
     * <p>工具应改为无状态实现，覆写 {@link #execute(Object, AgentContext)} 显式接收上下文，
     * 旧签名只作为后台/测试路径的回落。</p>
     */
    Object execute(Object input);

    /**
     * 显式接收上下文的执行入口。
     *
     * <p>默认回落到 {@link #execute(Object)}，因此尚未迁移的工具无需改动即可编译；
     * 覆写本方法的工具必须自身不持有 context 实例字段，否则并发调用会互相覆盖。</p>
     *
     * @param input   工具入参
     * @param context 本次调用的 Agent 上下文，可能为 null（后台/测试路径）
     */
    default Object execute(Object input, AgentContext context) {
        return execute(input);
    }
}