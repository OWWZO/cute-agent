package org.wwz.ai.test.domain.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingRequest;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingResult;
import org.wwz.ai.domain.agent.rag.model.query.ColumnSchemaRecallQuery;
import org.wwz.ai.domain.agent.rag.model.vector.VectorSearchRequest;
import org.wwz.ai.domain.agent.rag.model.vector.VectorSearchResult;
import org.wwz.ai.domain.agent.rag.port.TextEmbeddingPort;
import org.wwz.ai.domain.agent.rag.service.SchemaRecallService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * schema 召回：没有指定模型时不得带空的 modelCode 过滤条件。
 */
public class SchemaRecallServiceFilterTest {

    @Test
    public void emptyModelCodesDoNotProduceEmptyInFilter() {
        AtomicReference<VectorSearchRequest> captured = new AtomicReference<>();
        SchemaRecallService service = new SchemaRecallService(embeddingPort(), request -> {
            captured.set(request);
            return VectorSearchResult.empty();
        }, request -> null);

        service.vectorRecall(new ColumnSchemaRecallQuery("销售额", 10, null, null, List.of()));

        Assert.assertNotNull(captured.get());
        Assert.assertTrue("空模型列表必须生成无过滤请求", captured.get().getFilters().isEmpty());
    }

    @Test
    public void nonEmptyModelCodesProduceModelFilter() {
        AtomicReference<VectorSearchRequest> captured = new AtomicReference<>();
        SchemaRecallService service = new SchemaRecallService(embeddingPort(), request -> {
            captured.set(request);
            return VectorSearchResult.empty();
        }, request -> null);

        service.vectorRecall(new ColumnSchemaRecallQuery("销售额", 10, null, null, List.of("m1", "m2")));

        Map<String, Object> filters = captured.get().getFilters();
        Assert.assertEquals(List.of("m1", "m2"), filters.get("modelCode"));
    }

    private static TextEmbeddingPort embeddingPort() {
        return new TextEmbeddingPort() {
            @Override
            public TextEmbeddingResult embed(TextEmbeddingRequest request) {
                return TextEmbeddingResult.available(List.of(List.of(0.1f, 0.2f)));
            }
        };
    }
}
