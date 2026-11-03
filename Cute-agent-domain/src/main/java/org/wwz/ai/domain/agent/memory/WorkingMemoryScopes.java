package org.wwz.ai.domain.agent.memory;

import org.apache.commons.lang3.StringUtils;

/**
 * working_memory 投影作用域。
 *
 * <p>子会话已统一为 {@link #MAIN}：子 Agent 的记忆按 {@code (childSessionId, main)} 存，
 * 与主会话完全同构——「父派发子 Agent」与「用户从会话树点进子会话」是同一个会话的两种驱动方式。
 * 历史上的 {@code sub:{agentId}} 作用域只存在于迁移前的存量数据里。</p>
 */
public final class WorkingMemoryScopes {

    public static final String MAIN = "main";

    private WorkingMemoryScopes() {
    }

    /**
     * 归一化 scope：空白回退到 {@link #MAIN}，其余仅 trim。
     * <p>注意：这里<b>不迁移</b>历史 {@code sub:{agentId}} 值；读取存量数据时调用方需自行识别，
     * 否则会继续按旧 scope 键读写而与统一后的 main 视图互不可见。</p>
     */
    public static String normalize(String scope) {
        return StringUtils.isBlank(scope) ? MAIN : scope.trim();
    }
}
