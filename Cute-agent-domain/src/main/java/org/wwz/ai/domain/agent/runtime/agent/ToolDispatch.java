package org.wwz.ai.domain.agent.runtime.agent;

/**
 * 一个 tool call 解析后的派发目标：真实工具名、已解析入参，以及可在执行前确定的早退结果。
 * <p>
 * {@code inputJson} 始终是归一化后的合法 JSON 文本，供账本与 SSE 卡片直接使用。
 * <p>字段组合约定（消费方必须按此优先级判断）：
 * <ul>
 *   <li>{@code earlyOutcome != null}：执行前已确定结果，直接返回该结果，忽略 {@code argsReady}/{@code args}；</li>
 *   <li>{@code argsReady == true} 且 {@code args == null}：合法早退场景（如延迟工具、参数格式非法），
 *       不得再尝试解析；</li>
 *   <li>{@code argsReady == false}：入参尚未就绪，消费方需自行从 tool call 解析。</li>
 * </ul>
 */
final class ToolDispatch {

    final String toolName;
    final Object args;
    final boolean argsReady;
    final String inputJson;
    final ToolExecutionOutcome earlyOutcome;

    ToolDispatch(String toolName,
                 Object args,
                 boolean argsReady,
                 String inputJson,
                 ToolExecutionOutcome earlyOutcome) {
        this.toolName = toolName;
        this.args = args;
        this.argsReady = argsReady;
        this.inputJson = inputJson;
        this.earlyOutcome = earlyOutcome;
    }
}
