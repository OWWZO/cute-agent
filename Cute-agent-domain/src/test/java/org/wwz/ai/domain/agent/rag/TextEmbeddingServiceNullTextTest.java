package org.wwz.ai.domain.agent.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.service.TextEmbeddingService;

/**
 * getVector(null) 必须在 List.of(text) 之前收口，否则 NPE 会绕过 getVectorBatch 的降级路径。
 */
public class TextEmbeddingServiceNullTextTest {

    @Test
    public void nullTextShouldReturnNullInsteadOfThrowing() {
        TextEmbeddingService service = new TextEmbeddingService();

        Assert.assertNull(service.getVector(null));
    }
}
