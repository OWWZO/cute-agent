package org.wwz.ai.domain.agent.rag.model.vector;

import java.util.Map;

/**
 * 单条向量召回结果。
 * <p>{@code payload} 只做浅拷贝：Map 结构不可变，但其中的可变值对象仍与调用方共享。</p>
 */
public final class VectorSearchHit {

    private final String id;
    private final float score;
    private final Map<String, Object> payload;

    public VectorSearchHit(String id, float score, Map<String, Object> payload) {
        this.id = id;
        this.score = score;
        this.payload = Map.copyOf(payload == null ? Map.of() : payload);
    }

    public String getId() {
        return id;
    }

    public float getScore() {
        return score;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
