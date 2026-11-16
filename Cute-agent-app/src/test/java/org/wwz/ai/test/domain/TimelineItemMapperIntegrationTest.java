package org.wwz.ai.test.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.wwz.ai.domain.agent.ledger.timeline.TimelineItem;
import org.wwz.ai.infrastructure.dao.cute.ITimelineItemDao;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

public class TimelineItemMapperIntegrationTest {

    @Test
    public void upsertMustKeepOrderTerminalStatusAndRunScopedQuery() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:timeline_item_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        SqlSessionFactory factory = mapperFactory(dataSource);

        try (SqlSession session = factory.openSession(true)) {
            ITimelineItemDao dao = session.getMapper(ITimelineItemDao.class);
            dao.upsert(item("req-1", "tool:call-1", "WAITING_INPUT", 0L, 0L));
            dao.upsert(item("req-1", "tool:call-1", "RUNNING", 10L, 2L));
            dao.upsert(item("req-1", "tool:call-1", "COMPLETED", 20L, 3L));
            dao.upsert(item("req-1", "tool:call-1", "FAILED", 30L, 4L));

            TimelineItem stored = dao.queryByRunIds(List.of("req-1")).get(0);
            Assert.assertEquals("COMPLETED", stored.getStatus());
            Assert.assertEquals(Long.valueOf(10L), stored.getOrderKey());
            Assert.assertEquals(Integer.valueOf(2), stored.getSubOrder());
            Assert.assertEquals(Long.valueOf(4L), stored.getUpdatedSeq());
            Assert.assertEquals("tool:call-1", stored.getItemId());

            dao.upsert(item("req-2", "tool:call-2", "RUNNING", 1L, 1L));
            Assert.assertEquals(1, dao.queryByRunIds(List.of("req-2")).size());
        }
    }

    @Test
    public void upsertBatchMustNotReferenceForeachAliasOutsideTheLoop() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:timeline_item_batch_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        SqlSessionFactory factory = mapperFactory(dataSource);
        MappedStatement statement = factory.getConfiguration()
                .getMappedStatement("org.wwz.ai.infrastructure.dao.cute.ITimelineItemDao.upsertBatch");
        BoundSql boundSql = statement.getBoundSql(Map.of("items", List.of(
                item("req-1", "tool:call-1", "WAITING_INPUT", 0L, 0L),
                item("req-1", "tool:call-2", "RUNNING", 10L, 1L))));

        Assert.assertTrue(boundSql.getSql().contains("ON DUPLICATE KEY UPDATE"));
        Assert.assertFalse(boundSql.getParameterMappings().stream()
                .map(mapping -> mapping.getProperty())
                .anyMatch(property -> property != null && property.startsWith("item.")));
    }

    @Test
    public void softDeletedItemMustBeRevivedByTheSameLogicalKey() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:timeline_item_revive;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        SqlSessionFactory factory = mapperFactory(dataSource);

        try (SqlSession session = factory.openSession(true)) {
            ITimelineItemDao dao = session.getMapper(ITimelineItemDao.class);
            dao.upsert(item("req-1", "hitl:call-1", "WAITING_INPUT", 3L, 1L));
            jdbc.update("UPDATE ai_agent_timeline_item SET deleted = 1 WHERE run_id = 'req-1'");

            dao.upsert(item("req-1", "hitl:call-1", "COMPLETED", 4L, 2L));

            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_agent_timeline_item WHERE run_id = 'req-1' AND deleted = 0",
                    Integer.class);
            Assert.assertEquals(Integer.valueOf(1), rows);
        }
    }

    private SqlSessionFactory mapperFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:/mybatis/mapper/timeline_item_mapper.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    private TimelineItem item(String requestId,
                              String itemId,
                              String status,
                              long orderKey,
                              long updatedSeq) {
        return TimelineItem.builder()
                .sessionId("session-1")
                .runId(requestId)
                .itemId(itemId)
                .agentId("main")
                .kind("TOOL_CALL")
                .phase("INTERMEDIATE")
                .status(status)
                .orderKey(orderKey)
                .subOrder((int) updatedSeq)
                .updatedSeq(updatedSeq)
                .sourceType("TOOL")
                .build();
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ai_agent_timeline_item ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "session_id VARCHAR(128) NOT NULL,"
                + "run_id VARCHAR(64) NOT NULL,"
                + "item_id VARCHAR(192) NOT NULL,"
                + "parent_item_id VARCHAR(192),"
                + "agent_id VARCHAR(128),"
                + "kind VARCHAR(32) NOT NULL,"
                + "phase VARCHAR(16) NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "order_key BIGINT NOT NULL DEFAULT 0,"
                + "sub_order INT NOT NULL DEFAULT 0,"
                + "updated_seq BIGINT NOT NULL DEFAULT 0,"
                + "source_type VARCHAR(16) NOT NULL,"
                + "llm_invocation_id BIGINT,"
                + "tool_invocation_id BIGINT,"
                + "artifact_id BIGINT,"
                + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "deleted TINYINT NOT NULL DEFAULT 0,"
                + "UNIQUE KEY uk_timeline_session_item (session_id, item_id, deleted)"
                + ")");
    }
}
