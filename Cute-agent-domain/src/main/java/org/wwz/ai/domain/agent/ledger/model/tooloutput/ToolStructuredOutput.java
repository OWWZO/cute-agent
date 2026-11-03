package org.wwz.ai.domain.agent.ledger.model.tooloutput;

/**
 * rich tool 强类型输出根契约。
 */
public interface ToolStructuredOutput {

    /**
     * 返回稳定 toolName，用于 writer/reader 路由。
     * <p>契约：必须非 {@code null} 且非空白，并与 {@code ToolOutputNames} 中登记的常量一致。
     * 路由侧直接用它做 {@code Set.contains} / {@code equals} 比较，返回 {@code null} 或空白
     * 会静默走错分支（持久化被跳过、回放找不到 projector）。</p>
     *
     * @return 非空且非空白的稳定 toolName
     */
    String getToolName();
}
