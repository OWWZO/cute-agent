package org.wwz.ai.config.cute;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作记忆压缩配置（{@code autobots.autoagent.compaction.*}）。
 *
 * <p>取代 {@code SessionContextCompactionServiceImpl} 构造器上原有的 25 个 {@code @Value} 参数。
 * 字段默认值与原 {@code @Value} 冒号后的值逐一相同；生产值由
 * {@code application.yml} 的 {@code compaction:} 段显式覆盖（25 项全覆盖）。</p>
 *
 * <p>消费方式：{@link CuteRuntimeAutoConfiguration} 用它构建不可变的
 * {@code CompactionBudget}（domain 值对象，WorkingMemoryCompactor 与压缩服务共用），
 * 压缩配置从此只有这一份来源。</p>
 */
@Data
@ConfigurationProperties(prefix = "autobots.autoagent.compaction")
public class CompactionProperties {

    private boolean enabled = true;

    private boolean llmEnabled = true;

    private boolean microEnabled = true;

    private boolean sessionMemoryEnabled = false;

    private double thresholdPercent = 0.50d;

    private int keepMinTokens = 10_000;

    private int keepMinTextMessages = 5;

    private int keepMaxTokens = 40_000;

    private int maxConsecutiveFailures = 3;

    private double temperature = 0.2d;

    private int messageContentCharLimit = 4000;

    private int microKeepRecentToolResults = 5;

    private int microToolResultMaxChars = 8000;

    private double summaryTargetRatio = 0.20d;

    private int protectFirstN = 3;

    private int protectLastN = 8;

    private int contentMaxChars = 6000;

    private int contentHeadChars = 4000;

    private int contentTailChars = 1500;

    private int summaryInputMaxChars = 160_000;

    private int summarizerTimeoutSeconds = 120;

    private boolean persistProjection = true;

    private boolean auditEnabled = true;

    private boolean auditStoreMessages = true;

    private boolean midRunEnabled = true;
}
