package org.wwz.ai.domain.agent.rag.model.schema;

import lombok.Data;

/** Schema knowledge for one model column, including recall and analysis policy. */
@Data
public class DataQuerySchema {
    private String modelCode;
    private String columnId;
    private String columnName;
    private String columnComment;
    private String fewShot;
    private String dataType;
    private String synonyms;
    /**
     * 逗号分隔的向量 UUID 列表，消费方按位置索引（如 {@code uuid[1..3]}）。
     * 持久化向量时必须非空且顺序稳定。
     */
    private String vectorUuid;
    private int defaultRecall;
    /**
     * 分析建议策略：{@code 1}=允许，{@code -1}=显式排除，{@code 0}=未配置。
     */
    private int analyzeSuggest;
}
