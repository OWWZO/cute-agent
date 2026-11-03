package org.wwz.ai.test.domain.rag;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;
import org.wwz.ai.domain.agent.rag.model.config.DataQueryModelConfig;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;
import org.wwz.ai.domain.agent.rag.port.IChatModelMetadataRepository;
import org.wwz.ai.domain.agent.rag.service.ChatModelSchemaService;

import java.util.List;
import java.util.Map;

/**
 * ignore_fields 是「排除敏感列」的信任边界：分隔符两侧的空格和大小写差异都不能让它失效。
 */
public class ChatModelSchemaIgnoreFieldsTest {

    @Test
    public void ignoreFieldsMatchTrimmedAndCaseInsensitively() {
        IChatModelMetadataRepository repository = Mockito.mock(IChatModelMetadataRepository.class);
        ChatModelSchemaService service = new ChatModelSchemaService(repository);
        DataQueryModelConfig config = DataQueryModelConfig.builder()
                .id("sales")
                .type("table")
                .content("sales_data")
                .ignoreFields(" secret , Password ")
                .build();

        List<ChatModelSchema> saved = service.saveModelSchema("sales", config, List.of(
                column("SECRET"),
                column("password"),
                column("visible_col")), Map.of());

        Assert.assertEquals("被忽略列不得进入 schema", 1, saved.size());
        Assert.assertEquals("visible_col", saved.get(0).getColumnId());
        Mockito.verify(repository).saveModelSchemas(saved);
    }

    private static DataQueryTableColumn column(String name) {
        return DataQueryTableColumn.builder()
                .name(name)
                .comment(name)
                .dataType("VARCHAR")
                .build();
    }
}
