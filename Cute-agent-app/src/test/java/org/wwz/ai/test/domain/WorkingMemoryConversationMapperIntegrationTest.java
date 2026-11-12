package org.wwz.ai.test.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.wwz.ai.domain.agent.ledger.model.WorkingMemoryConversationRow;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryConversationReadDao;

import javax.sql.DataSource;
import java.util.List;

/**
 * 真正走 MyBatis ObjectFactory 与 resultMap 的工作记忆联表查询回归。
 */
public class WorkingMemoryConversationMapperIntegrationTest {

    @Test
    public void queryBySessionIdsInstantiatesResultMapRow() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:working_memory_conversation_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);

        try (SqlSession session = mapperFactory(dataSource).openSession(true)) {
            WorkingMemoryConversationRow constructed = new DefaultObjectFactory()
                    .create(WorkingMemoryConversationRow.class);
            Assert.assertNotNull("MyBatis 默认 ObjectFactory 必须能实例化 resultMap 类型", constructed);

            IWorkingMemoryConversationReadDao dao =
                    session.getMapper(IWorkingMemoryConversationReadDao.class);
            List<WorkingMemoryConversationRow> rows = dao.queryBySessionIds(List.of("session-1"));

            Assert.assertEquals(1, rows.size());
            WorkingMemoryConversationRow row = rows.get(0);
            Assert.assertEquals(Long.valueOf(1L), row.getTurnId());
            Assert.assertEquals(Long.valueOf(2L), row.getMessageId());
            Assert.assertEquals("session-1", row.getSessionId());
            Assert.assertEquals("req-1", row.getRequestId());
            Assert.assertEquals("assistant", row.getRole());
            Assert.assertEquals("继续吗", row.getContent());
            Assert.assertEquals(Integer.valueOf(1), row.getSeqNo());
            Assert.assertNotNull(row.getCreateTime());
        }
    }

    private static SqlSessionFactory mapperFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:/mybatis/mapper/working_memory_conversation_read_mapper.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ai_agent_working_memory_turn ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "session_id VARCHAR(128) NOT NULL,"
                + "request_id VARCHAR(128),"
                + "run_id BIGINT,"
                + "turn_seq INT,"
                + "memory_scope VARCHAR(64) NOT NULL,"
                + "deleted INT NOT NULL DEFAULT 0"
                + ")");
        jdbc.execute("CREATE TABLE ai_agent_working_memory_message ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "turn_id BIGINT NOT NULL,"
                + "seq_no INT,"
                + "role VARCHAR(32),"
                + "content VARCHAR(1024),"
                + "tool_call_id VARCHAR(128),"
                + "tool_calls_json VARCHAR(1024),"
                + "message_kind VARCHAR(32),"
                + "visibility VARCHAR(32),"
                + "token_estimate INT,"
                + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "memory_scope VARCHAR(64) NOT NULL,"
                + "deleted INT NOT NULL DEFAULT 0"
                + ")");
        jdbc.update("INSERT INTO ai_agent_working_memory_turn "
                + "(id, session_id, request_id, run_id, turn_seq, memory_scope, deleted) "
                + "VALUES (1, 'session-1', 'req-1', 9, 1, 'main', 0)");
        jdbc.update("INSERT INTO ai_agent_working_memory_message "
                + "(id, turn_id, seq_no, role, content, visibility, token_estimate, "
                + "memory_scope, deleted) "
                + "VALUES (2, 1, 1, 'assistant', '继续吗', 'ALL', 4, 'main', 0)");
    }
}
