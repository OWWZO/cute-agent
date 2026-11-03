package org.wwz.ai.domain.agent.rag.model.vector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条向量召回结果。
 * <p>{@code payload} 只做浅拷贝：Map 结构不可变，但其中的可变值对象仍与调用方共享。
 * 外部向量库的 payload 常带显式 null 值，拷贝必须允许 null（{@code Map.copyOf} 会直接拒绝）。</p>
 */
public final class VectorSearchHit {

    private final String id;
    private final float score;
    private final Map<String, Object> payload;

    public VectorSearchHit(String id, float score, Map<String, Object> payload) {
        this.id = id;
        this.score = score;
        this.payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
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
