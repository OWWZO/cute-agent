package org.wwz.ai.domain.agent.runtime.subagent;

/** Agent 工具本次由 task 池执行，还是因池满由调用线程原地执行。 */
public final class SubAgentExecutionMode {

    public static final String INLINE = "inline";
    public static final String POOL = "pool";

    private SubAgentExecutionMode() {
    }
}
