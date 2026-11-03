package org.wwz.ai.domain.agent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingRequest;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingResult;
import org.wwz.ai.domain.agent.rag.port.TextEmbeddingPort;

import java.util.List;

/**
 * Domain-facing embedding facade kept for the startup runner compatibility contract.
 */
@Slf4j
@Service
public class TextEmbeddingService {

    /** 健康检查会被频繁轮询，短缓存避免每次调用都打一次真实 embedding。 */
    private static final long HEALTH_CACHE_TTL_MS = 30_000L;

    private TextEmbeddingPort textEmbeddingPort;
    private volatile boolean healthResult;
    private volatile long healthCheckedAtMs;

    public TextEmbeddingService() {
    }

    @Autowired
    public TextEmbeddingService(TextEmbeddingPort textEmbeddingPort) {
        this.textEmbeddingPort = textEmbeddingPort;
    }

    public List<List<Float>> getVectorBatch(List<String> texts) {
        if (textEmbeddingPort == null) {
            return null;
        }
        try {
            TextEmbeddingResult result = textEmbeddingPort.embed(new TextEmbeddingRequest(texts, true));
            return result == null || !result.isAvailable() ? null : result.getVectors();
        } catch (Exception e) {
            log.warn("文本向量化失败，返回空结果: textCount={}", texts == null ? 0 : texts.size(), e);
            return null;
        }
    }

    public List<Float> getVector(String text) {
        List<List<Float>> vectorBatch = getVectorBatch(List.of(text));
        if (vectorBatch != null && !vectorBatch.isEmpty()) {
            return vectorBatch.get(0);
        }
        return null;
    }

    public boolean healthCheck() {
        long now = System.currentTimeMillis();
        if (healthCheckedAtMs > 0 && now - healthCheckedAtMs < HEALTH_CACHE_TTL_MS) {
            return healthResult;
        }
        List<Float> vector = getVector("health_check");
        boolean healthy = vector != null && !vector.isEmpty();
        healthResult = healthy;
        healthCheckedAtMs = System.currentTimeMillis();
        return healthy;
    }

    public void setTextEmbeddingPort(TextEmbeddingPort textEmbeddingPort) {
        this.textEmbeddingPort = textEmbeddingPort;
    }
}
