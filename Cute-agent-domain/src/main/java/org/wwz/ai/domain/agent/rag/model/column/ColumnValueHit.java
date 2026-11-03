package org.wwz.ai.domain.agent.rag.model.column;

import java.util.Map;

/**
 * 单条列值召回结果。
 */
public final class ColumnValueHit {

    private final Map<String, Object> fields;
    private final Float score;

    public ColumnValueHit(Map<String, Object> fields, Float score) {
        this.fields = Map.copyOf(fields == null ? Map.of() : fields);
        this.score = score;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    /**
     * 召回得分。{@code null} 表示上游未返回得分（不是 0 分），消费方比较/排序前必须判空；
     * 与 {@code VectorSearchHit#getScore()} 的 primitive 语义不同。
     */
    public Float getScore() {
        return score;
    }
}
