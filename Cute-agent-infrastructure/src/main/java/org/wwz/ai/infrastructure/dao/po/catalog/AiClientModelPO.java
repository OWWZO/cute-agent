package org.wwz.ai.infrastructure.dao.po.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Catalog 上下文模型配置持久化对象。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiClientModelPO implements Serializable {
    /** 数据库自增主键；仅用于按 id 更新/查询。 */
    private Long id;

    /** 模型业务标识，同一 modelId 可对应多条配置。 */
    private String modelId;

    /** 关联 AiClientApiPO.apiId 的业务外键。 */
    private String apiId;
    private String modelName;
    private String modelType;
    private String modelUsage;
    private Integer supportsThinking;
    private Integer contextWindow;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
