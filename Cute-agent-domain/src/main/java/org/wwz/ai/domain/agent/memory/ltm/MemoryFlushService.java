package org.wwz.ai.domain.agent.memory.ltm;

import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.util.List;

/**
 * 压缩前独立 Memory Flush 小回合。
 * Hermes 对齐：尽量复用父 system/tools 前缀；指令仅尾部 user。
 */
public interface MemoryFlushService {

    /**
     * 向后兼容重载：只返回成功写入的条目数，无法区分 skipped 与 failed。
     * 新调用方应使用返回 {@link FlushResult} 的六参版本。
     */
    default int flushBeforeCompact(String sessionId,
                                   String requestId,
                                   LtmOwner owner,
                                   List<Message> messagesAboutToCompact) {
        return flushBeforeCompact(sessionId, requestId, owner, messagesAboutToCompact, null, null).written();
    }

    FlushResult flushBeforeCompact(String sessionId,
                                   String requestId,
                                   LtmOwner owner,
                                   List<Message> messagesAboutToCompact,
                                   String parentSystemPrompt,
                                   ToolCollection parentTools);

    /**
     * flush 执行结果，用于区分「策略性跳过」和「真实失败」：
     * 调用方可以只对 {@code failed} 重试或打点，而 {@code skipped} 属于正常业务分支。
     *
     * @param written 成功写入的条目数
     * @param skipped 是否因禁用/空消息/嵌套 fork/重复请求等策略原因跳过
     * @param failed  是否因 LLM/IO 等真实错误失败
     * @param error   失败原因，仅在 {@code failed} 时非空
     */
    record FlushResult(int written, boolean skipped, boolean failed, String error) {

        public static FlushResult ofApplied(int written) {
            return new FlushResult(Math.max(0, written), false, false, null);
        }

        public static FlushResult ofSkipped() {
            return new FlushResult(0, true, false, null);
        }

        public static FlushResult ofFailed(String error) {
            return new FlushResult(0, false, true, error);
        }
    }
}
