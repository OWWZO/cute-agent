package org.wwz.ai.domain.agent.memory;

import lombok.Builder;
import lombok.Value;

/**
 * 会话上下文压缩预算。
 *
 * <p>threshold 对齐 Hermes：按模型窗口的百分比触发，计数对象是整包请求
 *（system + tools + messages），不再扣 max_tokens / buffer / output-reserve。</p>
 */
@Value
@Builder(toBuilder = true)
public class CompactionBudget {

    public static final double DEFAULT_THRESHOLD_PERCENT = 0.50d;
    public static final int DEFAULT_KEEP_MIN_TOKENS = 10_000;
    public static final int DEFAULT_KEEP_MIN_TEXT_MESSAGES = 5;
    public static final int DEFAULT_KEEP_MAX_TOKENS = 40_000;
    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;
    public static final int DEFAULT_CONTEXT_WINDOW = 100_000;
    public static final int DEFAULT_MICRO_KEEP_RECENT_TOOL_RESULTS = 5;
    public static final int DEFAULT_MICRO_TOOL_RESULT_MAX_CHARS = 8_000;
    public static final String CLEARED_TOOL_RESULT =
            "[Old tool result content cleared]";

    /** 窗口低于此值时，把触发比例至少抬到 {@link #SMALL_CTX_THRESHOLD_PERCENT}。 */
    public static final int SMALL_CTX_WINDOW_LIMIT = 512_000;
    public static final double SMALL_CTX_THRESHOLD_PERCENT = 0.75d;
    /** 64k 地板把阈值顶到窗口外时，改用窗口的这一比例。 */
    public static final double MIN_CTX_TRIGGER_RATIO = 0.85d;
    public static final int MINIMUM_THRESHOLD_TOKENS = 64_000;

    boolean enabled;
    boolean llmEnabled;
    boolean microEnabled;
    boolean sessionMemoryEnabled;
    int contextWindow;
    double thresholdPercent;
    int keepMinTokens;
    int keepMinTextMessages;
    int keepMaxTokens;
    int maxConsecutiveFailures;
    double temperature;
    int messageContentCharLimit;
    double summaryTargetRatio;
    int protectFirstN;
    int protectLastN;
    int contentMaxChars;
    int contentHeadChars;
    int contentTailChars;
    int summaryInputMaxChars;
    int summarizerTimeoutSeconds;
    int microKeepRecentToolResults;
    int microToolResultMaxChars;

    /** mid-run（流式中途）是否允许触发压缩；与 @Value 时代 SessionContextCompactionServiceImpl 的同名开关同义。 */
    @Builder.Default
    boolean midRunEnabled = true;

    /** 压缩结果是否回写 working memory 投影；false 只产出审计事件。 */
    @Builder.Default
    boolean persistProjection = true;

    /** 是否写 ai_agent_working_memory_compaction 审计表。 */
    @Builder.Default
    boolean auditEnabled = true;

    /** 审计事件是否携带压缩前后的完整消息 JSON。 */
    @Builder.Default
    boolean auditStoreMessages = true;

    public int threshold() {
        int window = Math.max(contextWindow, 0);
        if (window <= 0) {
            return 1;
        }
        double percent = thresholdPercent > 0 ? thresholdPercent : DEFAULT_THRESHOLD_PERCENT;
        if (window < SMALL_CTX_WINDOW_LIMIT) {
            percent = Math.max(percent, SMALL_CTX_THRESHOLD_PERCENT);
        }
        int pctValue = (int) (window * percent);
        int floored = Math.max(pctValue, MINIMUM_THRESHOLD_TOKENS);
        if (floored >= window) {
            return Math.max(1, Math.min((int) (window * MIN_CTX_TRIGGER_RATIO), window - 1));
        }
        return floored;
    }

    public static CompactionBudget defaults() {
        return CompactionBudget.builder()
                .enabled(true)
                .llmEnabled(true)
                .microEnabled(true)
                .sessionMemoryEnabled(false)
                .contextWindow(DEFAULT_CONTEXT_WINDOW)
                .thresholdPercent(DEFAULT_THRESHOLD_PERCENT)
                .keepMinTokens(DEFAULT_KEEP_MIN_TOKENS)
                .keepMinTextMessages(DEFAULT_KEEP_MIN_TEXT_MESSAGES)
                .keepMaxTokens(DEFAULT_KEEP_MAX_TOKENS)
                .maxConsecutiveFailures(DEFAULT_MAX_CONSECUTIVE_FAILURES)
                .temperature(0.2d)
                .messageContentCharLimit(4000)
                .summaryTargetRatio(0.20d)
                .protectFirstN(3)
                .protectLastN(8)
                .contentMaxChars(6000)
                .contentHeadChars(4000)
                .contentTailChars(1500)
                .summaryInputMaxChars(160000)
                .summarizerTimeoutSeconds(120)
                .microKeepRecentToolResults(DEFAULT_MICRO_KEEP_RECENT_TOOL_RESULTS)
                .microToolResultMaxChars(DEFAULT_MICRO_TOOL_RESULT_MAX_CHARS)
                .midRunEnabled(true)
                .persistProjection(true)
                .auditEnabled(true)
                .auditStoreMessages(true)
                .build();
    }
}
