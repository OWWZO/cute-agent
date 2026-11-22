package org.wwz.ai.domain.agent.runtime.subagent;

/**
 * 多层级子 Agent 的首版硬上限。
 *
 * <p>这些值刻意不做配置项：首版先把树形委派的边界锁死，避免通过配置组合
 * 重新制造无界递归或线程饥饿。</p>
 */
public final class SubAgentTreeLimits {

    public static final int MAX_DELEGATION_DEPTH = 3;
    public static final int MAX_AGENTS_PER_TREE = 20;
    public static final int MAX_ALIVE_AGENTS_PER_TREE = 8;
    public static final int MAX_DIRECT_CHILDREN = 5;
    public static final int MAX_CLOSING_TURNS = 5;

    private SubAgentTreeLimits() {
    }
}
