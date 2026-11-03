package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingRequest;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingResult;

/**
 * 文本 embedding 能力端口。
 * <p><b>失败语义：</b>实现应以 {@link TextEmbeddingResult#unavailable()} 表达不可用，而不是抛异常
 * （当前实现捕获所有异常并降级）；{@code request} 为 {@code null} 或 {@code getTexts()} 为
 * {@code null} 时同样返回 unavailable。</p>
 * <p><b>结果对齐：</b>调用方按 {@code getVectors()} 与 {@code getTexts()} 逐项对应使用；端口不校验
 * 数量是否与输入一致。空输入不保证成功，调用方必须以返回值判断可用性。</p>
 */
public interface TextEmbeddingPort {

    /**
     * @param request 待向量化文本；可为 {@code null}（返回 unavailable）
     * @return 非 {@code null} 的结果；不可用时 {@code isAvailable() == false} 且向量为空
     */
    TextEmbeddingResult embed(TextEmbeddingRequest request);
}
