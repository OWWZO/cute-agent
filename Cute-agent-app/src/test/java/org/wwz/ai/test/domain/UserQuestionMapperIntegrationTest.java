package org.wwz.ai.test.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionRecord;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionStatuses;
import org.wwz.ai.infrastructure.adapter.repository.UserQuestionRepository;
import org.wwz.ai.infrastructure.dao.cute.IUserQuestionDao;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 用户提问并发写入：先跑迁移脚本建索引，再用两个线程验证唯一索引与业务错误。
 */
public class UserQuestionMapperIntegrationTest {

    private static final Path MIGRATION = Path.of("..", "db", "migrations",
            "20261112_user_question_open_unique.sql");

    @Test
    public void migrationSoftDeletesHistoryDuplicatesBeforeCreatingIndex() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:user_question_open_unique_history;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        insertHistoryDuplicate(jdbc, 1, "old-1");
        insertHistoryDuplicate(jdbc, 2, "old-2");
        insertHistoryDuplicate(jdbc, 3, "keep-3");

        executeMigration(dataSource);

        assertIndexExists(jdbc);
        Assert.assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ("
                        + "SELECT session_id, status, deleted FROM ai_agent_user_question "
                        + "GROUP BY session_id, status, deleted HAVING COUNT(*) > 1) d",
                Integer.class));
        Assert.assertEquals("keep-3", jdbc.queryForObject(
                "SELECT question_id FROM ai_agent_user_question WHERE deleted = 0", String.class));
        Assert.assertEquals(Integer.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_user_question "
                        + "WHERE deleted = 1 AND status LIKE 'DELETED_%'",
                Integer.class));
    }

    @Test
    public void migrationCreatesIndexAndConcurrentInsertLeavesOneOpenQuestion() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:user_question_open_unique;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        executeMigration(dataSource);
        assertIndexExists(jdbc);

        SqlSessionFactory factory = mapperFactory(dataSource);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        IUserQuestionDao dao = template.getMapper(IUserQuestionDao.class);
        UserQuestionRepository firstRepository = new UserQuestionRepository(dao);
        UserQuestionRepository secondRepository = new UserQuestionRepository(dao);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> results = new ArrayList<>();
            results.add(executor.submit(() -> insert(start, firstRepository, "uq-1")));
            results.add(executor.submit(() -> insert(start, secondRepository, "uq-2")));
            start.countDown();

            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> result : results) {
                outcomes.add(result.get(10, TimeUnit.SECONDS));
            }
            Assert.assertEquals(1, outcomes.stream().filter(Boolean.TRUE::equals).count());
            IllegalStateException rejected = outcomes.stream()
                    .filter(IllegalStateException.class::isInstance)
                    .map(IllegalStateException.class::cast)
                    .findFirst()
                    .orElse(null);
            Assert.assertNotNull("第二个并发调用必须拿到业务异常", rejected);
            Assert.assertEquals("该会话已有未关闭的提问", rejected.getMessage());
            Assert.assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_agent_user_question "
                            + "WHERE session_id = 'session-1' AND status = 'PENDING' AND deleted = 0",
                    Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Object insert(CountDownLatch start,
                                 UserQuestionRepository repository,
                                 String questionId) throws InterruptedException {
        start.await(5, TimeUnit.SECONDS);
        try {
            return repository.insertIfAbsent(question(questionId));
        } catch (IllegalStateException e) {
            return e;
        }
    }

    private static UserQuestionRecord question(String questionId) {
        return UserQuestionRecord.builder()
                .questionId(questionId)
                .sessionId("session-1")
                .sourceRequestId("req-" + questionId)
                .questions(List.of())
                .status(UserQuestionStatuses.PENDING)
                .build();
    }

    private static SqlSessionFactory mapperFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:/mybatis/mapper/user_question_mapper.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    private static void executeMigration(DataSource dataSource) throws Exception {
        String migration = Files.readString(MIGRATION);
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("DROP TABLE IF EXISTS ai_agent_user_question_bak_open_20261112");
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(
                    migration.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    private static void assertIndexExists(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE INDEX_NAME LIKE 'UK_USER_QUESTION_OPEN%'",
                Integer.class);
        Assert.assertTrue("迁移脚本必须成功创建 uk_user_question_open", count != null && count > 0);
    }

    private static void insertHistoryDuplicate(JdbcTemplate jdbc, long id, String questionId) {
        jdbc.update("INSERT INTO ai_agent_user_question "
                        + "(id, question_id, session_id, source_request_id, questions_json, "
                        + "status, deleted) VALUES (?, ?, 'session-1', ?, '[]', 'PENDING', 0)",
                id, questionId, "req-" + questionId);
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ai_agent_user_question ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "question_id VARCHAR(64) NOT NULL,"
                + "user_id VARCHAR(64),"
                + "session_id VARCHAR(128) NOT NULL,"
                + "source_run_id BIGINT,"
                + "source_request_id VARCHAR(64) NOT NULL,"
                + "tool_invocation_id BIGINT,"
                + "tool_call_id VARCHAR(128),"
                + "questions_json VARCHAR(2000) NOT NULL,"
                + "answers_json VARCHAR(2000),"
                + "status VARCHAR(32) NOT NULL DEFAULT 'PENDING',"
                + "expires_at TIMESTAMP,"
                + "resume_request_id VARCHAR(64),"
                + "resume_context_json VARCHAR(2000),"
                + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "deleted TINYINT NOT NULL DEFAULT 0,"
                + "UNIQUE KEY uk_user_question_id (question_id, deleted),"
                + "UNIQUE KEY uk_user_question_resume (resume_request_id, deleted),"
                + "KEY idx_user_question_session_status (session_id, status, deleted, create_time)"
                + ")");
    }
}
