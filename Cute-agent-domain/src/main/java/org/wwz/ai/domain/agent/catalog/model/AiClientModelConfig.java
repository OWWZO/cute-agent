package org.wwz.ai.domain.agent.catalog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 模型配置领域模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiClientModelConfig {

    /** 启用。与 DB 列 {@code status=1} 对应；仓储 {@code findEnabled()} 依赖此值。 */
    public static final int STATUS_ENABLED = 1;
    /** 停用。与 DB 列 {@code status=0} 对应。 */
    public static final int STATUS_DISABLED = 0;

    private Long id;
    private String modelId;
    private String apiId;
    private String modelName;
    private String modelType;
    private String modelUsage;
    private Integer supportsThinking;
    private Integer contextWindow;
    @Builder.Default
    private Integer status = STATUS_ENABLED;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
