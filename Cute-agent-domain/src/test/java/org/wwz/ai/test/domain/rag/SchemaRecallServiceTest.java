package org.wwz.ai.test.domain.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.column.ColumnValueSearchRequest;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingRequest;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingResult;
import org.wwz.ai.domain.agent.rag.model.vector.VectorRecallRequest;
import org.wwz.ai.domain.agent.rag.model.vector.VectorSearchRequest;
import org.wwz.ai.domain.agent.rag.model.vector.VectorSearchResult;
import org.wwz.ai.domain.agent.rag.port.ColumnValueSearchPort;
import org.wwz.ai.domain.agent.rag.port.TextEmbeddingPort;
import org.wwz.ai.domain.agent.rag.port.VectorSearchPort;
import org.wwz.ai.domain.agent.rag.service.SchemaRecallService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 召回降级回归：外呼失败必须留下可诊断信号，并且中断标记不能被吞掉。
 */
public class SchemaRecallServiceTest {

    @Test
    public void vectorRecallRestoresInterruptFlag() {
        TextEmbeddingPort embedding = new TextEmbeddingPort() {
            @Override
            public TextEmbeddingResult embed(TextEmbeddingRequest request) {
                throw new IllegalStateException("transport aborted", new InterruptedException("cancelled"));
            }
        };
        SchemaRecallService service = new SchemaRecallService(embedding, noopVectorSearch(), null);

        List<Map<String, Object>> result = service.vectorRecall(recallRequest());

        Assert.assertTrue(result.isEmpty());
        Assert.assertTrue("中断标记必须恢复", Thread.currentThread().isInterrupted());
        Thread.interrupted();
    }

    @Test
    public void esValueRecallRestoresInterruptFlag() {
        ColumnValueSearchPort columnValueSearch = request -> {
            throw new IOException("es down", new InterruptedException("cancelled"));
        };
        SchemaRecallService service = new SchemaRecallService(null, null, columnValueSearch);

        List<Map<String, Object>> result =
                service.esValueRecall(new ColumnValueSearchRequest("客户", List.of("m1"), 10));

        Assert.assertTrue(result.isEmpty());
        Assert.assertTrue("中断标记必须恢复", Thread.currentThread().isInterrupted());
        Thread.interrupted();
    }

    @Test
    public void esValueRecallStillDegradesOnOrdinaryFailure() {
        ColumnValueSearchPort columnValueSearch = request -> {
            throw new IOException("es down");
        };
        SchemaRecallService service = new SchemaRecallService(null, null, columnValueSearch);

        List<Map<String, Object>> result =
                service.esValueRecall(new ColumnValueSearchRequest("客户", List.of("m1"), 10));

        Assert.assertTrue(result.isEmpty());
        Assert.assertFalse("普通失败不能把线程标成中断", Thread.currentThread().isInterrupted());
    }

    private static VectorRecallRequest recallRequest() {
        return new VectorRecallRequest("客户名称", "cute_model_schema", 10, null,
                Map.of("modelCode", List.of("m1")), List.of(), null);
    }

    private static VectorSearchPort noopVectorSearch() {
        return new VectorSearchPort() {
            @Override
            public VectorSearchResult search(VectorSearchRequest request) {
                return VectorSearchResult.empty();
            }
        };
    }
}
