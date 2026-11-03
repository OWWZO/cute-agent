package org.wwz.ai.domain.agent.runtime.openui;

/**
 * Autofix 的上下文轮次。只保留文本，工具轮与非文本 part 由调用方提前丢弃。
 * <ul>
 *   <li>{@code role}：{@code user} / {@code assistant}；空白值在消费端按 {@code user} 处理。</li>
 *   <li>{@code content}：为 {@code null}/空白时该轮被丢弃。</li>
 * </ul>
 */
public record OpenUiFixTurn(String role, String content) {
}
