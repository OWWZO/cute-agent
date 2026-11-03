package org.wwz.ai.application.catalog.subagent;

import java.util.Set;

/**
 * 普通目录入口创建 SubAgent 的应用命令。
 * <p>该 record 不会防御性复制工具集合：传入的 Set 引用仍归调用方所有，命令交给应用服务后
 * 不得再修改这些集合。必填字段由应用服务校验，空值语义见各组件说明。</p>
 */
public record CatalogSubAgentCreateCommand(
        /**
         * 子 Agent 唯一标识；必填。格式为字母开头，仅含字母、数字、下划线、短横线或斜杠，
         * 长度 2-63，且不能覆盖内置的 general-purpose。
         */
        String agentKey,

        /** 展示名称；可空，当前创建路径会对空白值做 trim-to-null。 */
        String displayName,

        /** 何时使用该子 Agent 的说明；必填且不能为空白。 */
        String whenToUse,

        /** 子 Agent 的 system prompt；必填且不能为空白。 */
        String systemPrompt,

        /**
         * 允许使用的工具名集合；inherit 与 custom 模式都会以它作为 allow-list。
         * null 表示不限制，包含 {@code "*"} 也表示全部允许。
         * 当前创建持久化会把 null 或空集合写为未设置，是否需要“不允许任何工具”应显式确认。
         */
        Set<String> allowedTools,

        /**
         * 额外禁用的工具名集合；在全局禁用和允许范围过滤之后仍然优先生效。
         * 可为 null，当前下游按空集合处理。
         */
        Set<String> disallowedTools,

        /**
         * 工具策略，仅接受 {@code inherit} 或 {@code custom}（忽略大小写）。
         * 空白或 null 默认 {@code inherit}：从父 Agent 工具视图继承，但仍受
         * {@link #allowedTools} 和 {@link #disallowedTools} 过滤；{@code custom} 会使用同样的
         * 过滤规则并额外按 {@link #deferredTools} 构造自定义延迟工具视图。
         * <p>冲突时 {@link #disallowedTools} 始终优先；{@link #deferredTools} 仅在 custom
         * 模式下生效。</p>
         */
        String toolPolicyMode,

        /**
         * custom 模式下从 eager 工具移到延迟 catalog 的工具名集合；可为 null，
         * inherit 模式下忽略。该集合同样不会防御性复制。
         */
        Set<String> deferredTools,

        /** 最大执行步数；null 表示沿用运行时默认值，非 null 时必须为正整数。 */
        Integer maxSteps,

        /** 状态：0 表示禁用，1 表示启用；null 在创建路径默认按 1 处理。 */
        Integer status) {
}
