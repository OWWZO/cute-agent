package org.wwz.ai.test.domain.rag;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;
import org.wwz.ai.domain.agent.rag.port.IChatModelMetadataRepository;
import org.wwz.ai.domain.agent.rag.service.ChatModelSchemaService;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 默认召回字段必须从逐个仓储查询收敛为一次批量查询。 */
public class ChatModelSchemaServiceBatchRecallTest {

    @Test
    public void shouldQueryAllModelCodesThroughOneBatchCall() {
        IChatModelMetadataRepository repository = Mockito.mock(IChatModelMetadataRepository.class);
        Collection<String> modelCodes = List.of("sales", "orders", "customers");
        Mockito.when(repository.listDistinctSchemas(modelCodes))
                .thenReturn(List.of(schema(11L, "sales", "amount", 1), schema(12L, "orders", "total", 0)));

        List<ChatModelSchema> defaults = new ChatModelSchemaService(repository)
                .queryDefaultRecallFields(modelCodes);

        Mockito.verify(repository, Mockito.times(1)).listDistinctSchemas(modelCodes);
        Mockito.verify(repository, Mockito.never()).listDistinctSchemas(Mockito.anyString());
        Mockito.verify(repository, Mockito.never()).listDistinctSchemas();
        Assert.assertEquals(List.of("11:sales:amount:1"), descriptions(defaults));
    }

    @Test
    public void shouldKeepTheSameDefaultRecallRowsAsSequentialCalls() {
        List<ChatModelSchema> rows = List.of(
                schema(1L, "sales", "amount", 1),
                schema(2L, "sales", "region", 0),
                schema(3L, "orders", "total", 1),
                schema(4L, "orders", "status", 0),
                schema(5L, "customers", "name", 1));
        IChatModelMetadataRepository sequentialRepository = Mockito.mock(IChatModelMetadataRepository.class);
        IChatModelMetadataRepository batchRepository = Mockito.mock(IChatModelMetadataRepository.class);
        ChatModelSchemaService batchService = new ChatModelSchemaService(batchRepository);
        List<String> modelCodes = List.of("sales", "orders", "customers");
        for (String modelCode : modelCodes) {
            Mockito.when(sequentialRepository.listDistinctSchemas(modelCode))
                    .thenReturn(rowsForModel(rows, modelCode));
        }
        Mockito.when(batchRepository.listDistinctSchemas(Mockito.<Collection<String>>any()))
                .thenReturn(rows);

        List<String> actual = batchService.queryDefaultRecallFields(modelCodes).stream()
                .map(ChatModelSchemaServiceBatchRecallTest::description)
                .toList();
        List<String> expected = modelCodes.stream()
                .flatMap(modelCode -> sequentialRepository.listDistinctSchemas(modelCode).stream())
                .filter(schema -> schema.getDefaultRecall() == 1)
                .map(ChatModelSchemaServiceBatchRecallTest::description)
                .toList();

        Assert.assertEquals(Set.of("1:sales:amount:1", "3:orders:total:1", "5:customers:name:1"),
                Set.copyOf(expected));
        Assert.assertEquals(expected, actual);
    }

    private static ChatModelSchema schema(Long id, String modelCode, String columnId, int defaultRecall) {
        ChatModelSchema schema = new ChatModelSchema();
        schema.setId(id);
        schema.setModelCode(modelCode);
        schema.setColumnId(columnId);
        schema.setDefaultRecall(defaultRecall);
        return schema;
    }

    private static List<String> descriptions(List<ChatModelSchema> schemas) {
        return schemas.stream()
                .map(ChatModelSchemaServiceBatchRecallTest::description)
                .toList();
    }

    private static String description(ChatModelSchema schema) {
        return schema.getId() + ":" + schema.getModelCode() + ":"
                + schema.getColumnId() + ":" + schema.getDefaultRecall();
    }

    private static List<ChatModelSchema> rowsForModel(List<ChatModelSchema> rows, String modelCode) {
        return rows.stream().filter(schema -> modelCode.equals(schema.getModelCode())).toList();
    }
}
