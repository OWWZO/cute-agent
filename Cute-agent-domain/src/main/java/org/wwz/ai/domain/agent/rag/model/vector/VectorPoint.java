package org.wwz.ai.domain.agent.rag.model.vector;

import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/**
 * 待写入向量索引的领域点模型。
 */
@EqualsAndHashCode
@ToString
public final class VectorPoint {

    private final String id;
    private final List<Float> vector;
    private final Map<String, Object> payload;

    public VectorPoint(String id, List<Float> vector, Map<String, Object> payload) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("向量点 id 不能为空");
        }
        this.id = id;
        this.vector = List.copyOf(vector == null ? List.of() : vector);
        this.payload = Map.copyOf(payload == null ? Map.of() : payload);
    }

    public String getId() {
        return id;
    }

    public List<Float> getVector() {
        return vector;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
