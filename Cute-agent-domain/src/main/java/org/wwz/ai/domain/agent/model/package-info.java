/**
 * Agent 领域模型包。
 * <p>当前只包含 {@code valobj} 子包：跨层传递的领域值对象（会话附件、MCP 工具配置等），
 * 不依赖 infrastructure DTO，也不承载 Spring 组件装配。</p>
 * <p>TODO: 后续用于承载 LLM 动态配置模型（原注释保留的待办，尚未实现）。</p>
 */
package org.wwz.ai.domain.agent.model;
