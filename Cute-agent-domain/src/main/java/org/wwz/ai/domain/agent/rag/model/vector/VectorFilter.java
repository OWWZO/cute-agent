package org.wwz.ai.domain.agent.rag.model.vector;

import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Map;

/**
 * 向量索引的等值过滤条件。
 */
@EqualsAndHashCode
@ToString
public final class VectorFilter {

    private final Map<String, Object> must;

    public VectorFilter(Map<String, Object> must) {
        // 空过滤在向量库里等价于 match-all：deleteByFilter 会误删整个集合，必须快速失败。
        if (must == null || must.isEmpty()) {
            throw new IllegalArgumentException("vector filter must not be null or empty");
        }
        for (Map.Entry<String, Object> entry : must.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "vector filter must not contain null key or value: " + entry.getKey());
            }
        }
        this.must = Map.copyOf(must);
    }

    public Map<String, Object> getMust() {
        return must;
    }
}
