package org.wwz.ai.domain.agent.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelInfo;
import org.wwz.ai.domain.agent.rag.model.config.DataQuerySettings;
import org.wwz.ai.domain.agent.rag.model.query.Nl2SqlQuery;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryModelDescriptor;
import org.wwz.ai.domain.agent.rag.model.schema.DataQuerySchema;
import org.wwz.ai.domain.agent.rag.port.DataQueryExecutionPort;
import org.wwz.ai.domain.agent.rag.service.ChatModelInfoService;
import org.wwz.ai.domain.agent.rag.service.ChatModelSchemaService;
import org.wwz.ai.domain.agent.rag.service.SchemaRecallService;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Data Query 的空值边界：缺 modelCode 的召回行、未配置的 enable 开关都不能让整次查询失败。
 */
public class DataAgentQueryServiceNullSafetyTest {

    private final DataQuerySettings settings = new DataQuerySettings();
    private final TableRagService tableRagService = mock(TableRagService.class);
    private final ChatModelInfoService chatModelInfoService = mock(ChatModelInfoService.class);
    private final ChatModelSchemaService chatModelSchemaService = mock(ChatModelSchemaService.class);
    private final DataAgentQueryServiceImpl service = new DataAgentQueryServiceImpl(
            settings,
            tableRagService,
            chatModelInfoService,
            chatModelSchemaService,
            mock(Nl2SqlQueryService.class),
            mock(DataQueryExecutionPort.class),
            mock(SchemaRecallService.class));

    @Test
    public void recalledRowWithoutModelCodeShouldNotAbortEnrichment() throws Exception {
        when(tableRagService.tableRag(any())).thenReturn(new ArrayList<>(List.of(
                schema(null, "column-without-model"),
                schema("m1", "column-with-model"))));
        Nl2SqlQuery query = new Nl2SqlQuery();
        query.setSchemaInfo(new ArrayList<>(List.of(descriptor("m1"), descriptor("m2"))));

        service.enrichNl2Sql(query);

        Assert.assertEquals(1, query.getSchemaInfo().get(0).getSchemaList().size());
        Assert.assertEquals("column-with-model", query.getSchemaInfo().get(0).getSchemaList().get(0).getColumnId());
        // 没召回任何列的模型回退为空列表，而不是 null。
        Assert.assertNotNull(query.getSchemaInfo().get(1).getSchemaList());
        Assert.assertTrue(query.getSchemaInfo().get(1).getSchemaList().isEmpty());
    }

    @Test
    public void unsetEnableFlagsShouldBeTreatedAsDisabled() throws Exception {
        ChatModelInfo model = new ChatModelInfo();
        model.setCode("m1");
        model.setName("模型一");
        when(chatModelInfoService.listDistinctModels()).thenReturn(List.of(model));
        when(tableRagService.tableRag(any())).thenReturn(new ArrayList<>());
        when(chatModelSchemaService.queryAllSchemas()).thenReturn(new ArrayList<>());

        Nl2SqlQuery query = service.buildNl2SqlQuery("最近一周订单量");

        Assert.assertFalse(query.isUseElastic());
        Assert.assertFalse(query.isUseVector());
    }

    private DataQuerySchema schema(String modelCode, String columnId) {
        DataQuerySchema schema = new DataQuerySchema();
        schema.setModelCode(modelCode);
        schema.setColumnId(columnId);
        return schema;
    }

    private DataQueryModelDescriptor descriptor(String modelCode) {
        DataQueryModelDescriptor descriptor = new DataQueryModelDescriptor();
        descriptor.setModelCode(modelCode);
        return descriptor;
    }
}
