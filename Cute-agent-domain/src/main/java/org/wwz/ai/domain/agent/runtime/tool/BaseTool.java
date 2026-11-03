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
     * <p>若同时覆写了 {@link #getDescription(AgentContext)}，本方法必须与
     * {@code getDescription(null)} 保持一致；注册期快照（如 DeferredToolEntry）读的是无参版本，
     * 两套描述分叉会让同一工具对外暴露不同说明。</p>
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
     * <p>若同时覆写了 {@link #toParams(AgentContext)}，本方法必须与 {@code toParams(null)}
     * 保持一致；DeferredToolCatalog 等注册期路径仍读取无参版本，两份 schema 分叉会导致
     * 同一工具出现不一致的参数定义。</p>
     */
    Map<String, Object> toParams();

    /**
     * 显式接收上下文的执行入口。
     *
     * @param input   工具入参；具体类型由各工具约定（常见 String / Map / JSONObject），
     *                建议用 {@code ToolArgs} 统一解析，不得假设固定类型；可为 null
     * @param context 本次调用的 Agent 上下文，可为 null（与 {@link #getDescription(AgentContext)} 一致）；
     *                依赖运行时依赖的实现必须先判空并返回失败载荷，不得直接解引用
     * @return 工具执行结果（本地工具通常为 {@code ToolResultPayload}）；业务失败用失败载荷表达，
     *         不抛出受检异常
     */
    Object execute(Object input, AgentContext context);
}
