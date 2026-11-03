package org.wwz.ai.domain.agent.rag.model.vector;

import lombok.Data;

/** Schema fields stored as a vector payload; property names are part of the recall contract. */
@Data
public class VectorSchemaPayload {
    private String modelCode;
    private String columnId;
    private String columnName;
    private String columnComment;
    private String fewShot;
    private String dataType;
    private String synonyms;
    private String vectorUuid;
    // 源模型 ChatModelSchema 用 int：必须保持包装类型才能被 BeanUtils.copyProperties 赋值并参与 JSON 序列化。
    private Integer defaultRecall;
    private Integer analyzeSuggest;
}
