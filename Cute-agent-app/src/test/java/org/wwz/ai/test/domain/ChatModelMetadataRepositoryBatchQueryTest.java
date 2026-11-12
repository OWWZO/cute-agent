package org.wwz.ai.test.domain;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;
import org.wwz.ai.infrastructure.adapter.repository.dataquery.ChatModelMetadataRepository;
import org.wwz.ai.infrastructure.dao.cute.chatmodel.ChatModelInfoMapper;
import org.wwz.ai.infrastructure.dao.cute.chatmodel.ChatModelSchemaMapper;
import org.wwz.ai.infrastructure.dao.po.cute.chatmodel.ChatModelSchemaPO;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 批量 schema 查询的 SQL 次数、等价性与空输入边界。 */
public class ChatModelMetadataRepositoryBatchQueryTest {

    private static final int LIMIT = 5_000;

    @BeforeClass
    public static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ChatModelSchemaPO.class);
    }

    private final ChatModelInfoMapper infoMapper = Mockito.mock(ChatModelInfoMapper.class);
    private final ChatModelSchemaMapper schemaMapper = Mockito.mock(ChatModelSchemaMapper.class);
    private final ChatModelMetadataRepository repository =
            new ChatModelMetadataRepository(infoMapper, schemaMapper);

    @Test
    public void shouldUseOneSqlAndMatchSequentialResults() {
        List<String> modelCodes = List.of(" sales ", "orders", "customers");
        List<ChatModelSchemaPO> rows = List.of(
                row(8L, "orders", "status", 0),
                row(7L, "customers", "name", 1),
                row(6L, "sales", "amount", 1),
                row(5L, "orders", "total", 1),
                row(4L, "sales", "region", 0),
                row(3L, "sales", "amount", 0));
        Mockito.doAnswer(invocation -> filterByCode(rows, inCodes(invocation.getArgument(0))))
                .when(schemaMapper).selectList(Mockito.<Wrapper<ChatModelSchemaPO>>any());

        List<ChatModelSchema> sequential = modelCodes.stream()
                .flatMap(modelCode -> repository.listDistinctSchemas(modelCode).stream())
                .toList();
        Mockito.clearInvocations(schemaMapper);

        List<ChatModelSchema> batch = repository.listDistinctSchemas(modelCodes);

        Assert.assertEquals(descriptions(sequential), descriptions(batch));
        ArgumentCaptor<Wrapper<ChatModelSchemaPO>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(schemaMapper, Mockito.times(1)).selectList(wrapper.capture());
        String sqlSegment = wrapper.getValue().getSqlSegment();
        Assert.assertTrue(sqlSegment.contains("IN"));
        Assert.assertTrue(sqlSegment.contains("ORDER BY"));
        Assert.assertTrue(sqlSegment.contains("DESC"));
        Assert.assertTrue(sqlSegment.contains("LIMIT " + LIMIT));
        Assert.assertEquals(Set.of("sales", "orders", "customers"), inCodes(wrapper.getValue()));
    }

    @Test
    public void shouldSkipSqlForEmptyOrBlankModelCodes() {
        Assert.assertTrue(repository.listDistinctSchemas(List.of()).isEmpty());
        Assert.assertTrue(repository.listDistinctSchemas((Collection<String>) null).isEmpty());
        Assert.assertTrue(repository.listDistinctSchemas(List.of(" ", "\t")).isEmpty());
        Mockito.verifyNoInteractions(schemaMapper);
    }

    @Test
    public void shouldKeepOneGlobalLimitQueryWhenRowsReachLimit() {
        List<ChatModelSchemaPO> manyRows = new java.util.ArrayList<>();
        for (long id = 1; id <= LIMIT; id++) {
            manyRows.add(row(id, "sales", "column-" + id, 1));
        }
        Mockito.when(schemaMapper.selectList(Mockito.<Wrapper<ChatModelSchemaPO>>any()))
                .thenReturn(manyRows);

        List<String> modelCodes = List.of("sales", "orders");
        List<ChatModelSchema> batch = repository.listDistinctSchemas(modelCodes);

        Assert.assertEquals(LIMIT, batch.size());
        Assert.assertEquals("sales", batch.get(0).getModelCode());
        Mockito.verify(schemaMapper, Mockito.times(1))
                .selectList(Mockito.<Wrapper<ChatModelSchemaPO>>any());
        ArgumentCaptor<Wrapper<ChatModelSchemaPO>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        Mockito.verify(schemaMapper).selectList(wrapper.capture());
        Assert.assertTrue(wrapper.getValue().getSqlSegment().contains("LIMIT " + LIMIT));
    }

    private static List<ChatModelSchemaPO> filterByCode(List<ChatModelSchemaPO> rows, Set<String> codes) {
        return rows.stream().filter(row -> codes.contains(row.getModelCode())).toList();
    }

    /** MyBatis-Plus 将 IN 的每个元素展开为独立参数；从 wrapper 参数还原 SQL 里的 codes。 */
    @SuppressWarnings("unchecked")
    private static Set<String> inCodes(Wrapper<ChatModelSchemaPO> wrapper) {
        AbstractWrapper<ChatModelSchemaPO, ?, ?> lambdaWrapper = (AbstractWrapper<ChatModelSchemaPO, ?, ?>) wrapper;
        lambdaWrapper.getSqlSegment();
        return lambdaWrapper.getParamNameValuePairs().values().stream()
                .map(String::valueOf)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> descriptions(List<ChatModelSchema> schemas) {
        return schemas.stream()
                .map(schema -> schema.getId() + ":" + schema.getModelCode() + ":"
                        + schema.getColumnId() + ":" + schema.getDefaultRecall())
                .toList();
    }

    private static ChatModelSchemaPO row(Long id, String modelCode, String columnId, int defaultRecall) {
        return ChatModelSchemaPO.builder()
                .id(id)
                .modelCode(modelCode)
                .columnId(columnId)
                .defaultRecall(defaultRecall)
                .build();
    }
}
