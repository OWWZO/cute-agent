package org.wwz.ai.infrastructure.adapter.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;
import org.wwz.ai.domain.agent.rag.port.IChatModelMetadataRepository;
import org.wwz.ai.infrastructure.adapter.repository.dataquery.ChatModelMetadataRepository;
import org.wwz.ai.infrastructure.dao.cute.chatmodel.ChatModelSchemaMapper;
import org.wwz.ai.infrastructure.dao.po.cute.chatmodel.ChatModelSchemaPO;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatModelMetadataRepositoryBatchTest {

    private JdbcDataSource dataSource;

    @Before
    public void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:chat_metadata_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE chat_model_schema (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        model_code VARCHAR(200) NOT NULL,
                        column_id VARCHAR(1000) NOT NULL,
                        column_name VARCHAR(200) NOT NULL,
                        column_comment VARCHAR(1000) NOT NULL,
                        few_shot CLOB,
                        data_type VARCHAR(20),
                        synonyms VARCHAR(300),
                        vector_uuid VARCHAR(400),
                        default_recall TINYINT NOT NULL DEFAULT 0,
                        analyze_suggest TINYINT NOT NULL DEFAULT 0,
                        yn TINYINT NOT NULL DEFAULT 1
                    )
                    """);
        }
    }

    @Test
    public void shouldInsertAllSchemasThroughOneBatchCall() throws Exception {
        AtomicInteger batchCalls = new AtomicInteger();
        AtomicInteger singleCalls = new AtomicInteger();
        IChatModelMetadataRepository repository = transactionalRepository(
                mapper(0, batchCalls, singleCalls));

        repository.saveModelSchemas(List.of(schema("a"), schema("b"), schema("c")));

        Assert.assertEquals(1, batchCalls.get());
        Assert.assertEquals(0, singleCalls.get());
        Assert.assertEquals(List.of("a", "b", "c"), columnIds());
    }

    @Test
    public void shouldExecuteBatchInsertSqlAgainstDatabase() throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(ChatModelSchemaMapper.class);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        ChatModelSchemaMapper mapper = new SqlSessionTemplate(factory).getMapper(ChatModelSchemaMapper.class);

        transactionalRepository(mapper)
                .saveModelSchemas(List.of(schema("a"), schema("b"), schema("c")));

        Assert.assertEquals(List.of("a", "b", "c"), columnIds());
    }

    @Test
    public void shouldRollbackAllRowsWhenBatchFails() throws Exception {
        AtomicInteger batchCalls = new AtomicInteger();
        AtomicInteger singleCalls = new AtomicInteger();
        IChatModelMetadataRepository repository = transactionalRepository(
                mapper(2, batchCalls, singleCalls));

        try {
            repository.saveModelSchemas(List.of(schema("a"), schema("b"), schema("c")));
            Assert.fail("second row failure must abort the whole save");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("simulated"));
        }

        Assert.assertEquals(0, countRows());
    }

    private IChatModelMetadataRepository transactionalRepository(ChatModelSchemaMapper mapper) {
        ChatModelMetadataRepository target = new ChatModelMetadataRepository(null, mapper);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (IChatModelMetadataRepository) proxyFactory.getProxy();
    }

    private ChatModelSchemaMapper mapper(int failAt,
                                         AtomicInteger batchCalls,
                                         AtomicInteger singleCalls) {
        return (ChatModelSchemaMapper) Proxy.newProxyInstance(
                ChatModelSchemaMapper.class.getClassLoader(),
                new Class<?>[]{ChatModelSchemaMapper.class},
                (proxy, method, args) -> {
                    if ("insertBatch".equals(method.getName())) {
                        batchCalls.incrementAndGet();
                        insertRows((Collection<?>) args[0], failAt);
                        return ((Collection<?>) args[0]).size();
                    }
                    if ("insert".equals(method.getName())) {
                        if (args[0] instanceof Collection<?>) {
                            batchCalls.incrementAndGet();
                            insertRows((Collection<?>) args[0], failAt);
                            return List.of();
                        }
                        singleCalls.incrementAndGet();
                        insertRows(List.of(args[0]), failAt);
                        return 1;
                    }
                    if ("toString".equals(method.getName())) {
                        return "ChatModelSchemaMapper test proxy";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }

    private void insertRows(Collection<?> rows, int failAt) throws Exception {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_model_schema
                (model_code, column_id, column_name, column_comment, default_recall, analyze_suggest, yn)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            int index = 0;
            for (Object row : rows) {
                index++;
                if (index == failAt) {
                    throw new IllegalStateException("simulated insert failure at row " + index);
                }
                ChatModelSchemaPO schema = (ChatModelSchemaPO) row;
                statement.setString(1, schema.getModelCode());
                statement.setString(2, schema.getColumnId());
                statement.setString(3, schema.getColumnName());
                statement.setString(4, schema.getColumnComment());
                statement.setInt(5, schema.getDefaultRecall());
                statement.setInt(6, schema.getAnalyzeSuggest());
                statement.setInt(7, schema.getYn());
                statement.executeUpdate();
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private int countRows() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM chat_model_schema")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private List<String> columnIds() throws Exception {
        List<String> columnIds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT column_id FROM chat_model_schema ORDER BY column_id")) {
            while (resultSet.next()) {
                columnIds.add(resultSet.getString(1));
            }
        }
        return columnIds;
    }

    private ChatModelSchema schema(String columnId) {
        ChatModelSchema schema = new ChatModelSchema();
        schema.setModelCode("sales");
        schema.setColumnId(columnId);
        schema.setColumnName("字段" + columnId);
        schema.setColumnComment("字段说明" + columnId);
        schema.setYn(1);
        return schema;
    }
}
