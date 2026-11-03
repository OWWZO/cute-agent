package org.wwz.ai.test.domain.rag;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelInfo;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;
import org.wwz.ai.domain.agent.rag.model.config.DataQueryModelConfig;
import org.wwz.ai.domain.agent.rag.model.config.DataQuerySettings;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingRequest;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingResult;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;
import org.wwz.ai.domain.agent.rag.port.ColumnValueIndexAdminPort;
import org.wwz.ai.domain.agent.rag.port.ColumnValueSyncPort;
import org.wwz.ai.domain.agent.rag.port.DataQueryExecutionPort;
import org.wwz.ai.domain.agent.rag.port.DataQueryMetadataPort;
import org.wwz.ai.domain.agent.rag.port.IChatModelMetadataRepository;
import org.wwz.ai.domain.agent.rag.port.TextEmbeddingPort;
import org.wwz.ai.domain.agent.rag.port.VectorIndexAdminPort;
import org.wwz.ai.domain.agent.rag.service.ChatModelInfoService;
import org.wwz.ai.domain.agent.rag.service.ChatModelSchemaService;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 元数据清理的原子性 + 外部索引同步的事务边界回归。
 */
public class ChatModelInfoServiceTransactionTest {

    private final IChatModelMetadataRepository repository = Mockito.mock(IChatModelMetadataRepository.class);
    private final ChatModelSchemaService schemaService = Mockito.mock(ChatModelSchemaService.class);
    private final TextEmbeddingPort embeddingPort = Mockito.mock(TextEmbeddingPort.class);
    private final VectorIndexAdminPort vectorIndexPort = Mockito.mock(VectorIndexAdminPort.class);
    private final DataQuerySettings settings = new DataQuerySettings();
    private final ChatModelInfoService service = new ChatModelInfoService(
            repository,
            settings,
            Mockito.mock(DataQueryMetadataPort.class),
            Mockito.mock(DataQueryExecutionPort.class),
            schemaService,
            embeddingPort,
            vectorIndexPort,
            Mockito.mock(ColumnValueIndexAdminPort.class),
            Mockito.mock(ColumnValueSyncPort.class));

    @After
    public void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    public void cleanModelMetadataUsesSingleAtomicDelete() {
        service.cleanModelMetadata("model-1");

        verify(repository).deleteByModelCode("model-1");
        verify(repository, never()).deleteModelInfoByCode(anyString());
        verify(schemaService, never()).cleanModelSchema(anyString());
    }

    @Test
    public void externalIndexSyncRunsOnlyAfterCommit() throws Exception {
        settings.getQdrantConfig().setEnable(true);
        stubSchemaAndEmbedding();

        TransactionSynchronizationManager.initSynchronization();
        service.saveModelInfo(modelConfig(), columns(), Map.of());

        verify(vectorIndexPort, never()).deleteByFilter(anyString(), any());
        verify(embeddingPort, never()).embed(any(TextEmbeddingRequest.class));

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(vectorIndexPort).deleteByFilter(anyString(), any());
        verify(vectorIndexPort).upsert(anyString(), any());
    }

    @Test
    public void externalIndexSyncStillRunsWhenNoTransactionIsActive() throws Exception {
        settings.getQdrantConfig().setEnable(true);
        stubSchemaAndEmbedding();

        service.saveModelInfo(modelConfig(), columns(), Map.of());

        verify(vectorIndexPort).deleteByFilter(anyString(), any());
        verify(vectorIndexPort).upsert(anyString(), any());
    }

    private void stubSchemaAndEmbedding() throws Exception {
        ChatModelSchema schema = new ChatModelSchema();
        schema.setModelCode("model-1");
        schema.setColumnId("C1");
        schema.setColumnName("客户名称");
        schema.setDataType("VARCHAR");
        schema.setVectorUuid("u1,u2,u3,u4");
        when(schemaService.saveModelSchema(anyString(), any(), any(), any())).thenReturn(List.of(schema));
        when(embeddingPort.embed(any(TextEmbeddingRequest.class)))
                .thenReturn(TextEmbeddingResult.available(List.of(List.of(0.1f, 0.2f))));
    }

    private static DataQueryModelConfig modelConfig() {
        DataQueryModelConfig config = new DataQueryModelConfig();
        config.setId("model-1");
        config.setName("模型一");
        config.setType("table");
        config.setContent("sales_data");
        return config;
    }

    private static List<DataQueryTableColumn> columns() {
        return List.of(DataQueryTableColumn.builder().name("C1").dataType("VARCHAR").build());
    }
}
