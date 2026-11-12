package org.wwz.ai.test.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionRecord;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionStatuses;
import org.wwz.ai.infrastructure.adapter.repository.UserQuestionRepository;
import org.wwz.ai.infrastructure.dao.cute.IUserQuestionDao;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 用户提问写入约束：H2 不支持 MySQL 表达式索引，测试用等价的生成列唯一索引验证语义。
 */
public class UserQuestionMapperIntegrationTest {

    @Test
    public void concurrentInsertLeavesOneOpenQuestion() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:user_question_open_unique;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        createOpenOnlyUniqueIndex(jdbc);
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
            Assert.assertTrue(rejected.getCause() instanceof DuplicateKeyException);
            Assert.assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_agent_user_question "
                            + "WHERE session_id = 'session-1' AND status = 'PENDING' AND deleted = 0",
                    Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void sameSessionAllowsMultipleAnsweredQuestions() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:user_question_multiple_answered;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        createOpenOnlyUniqueIndex(jdbc);
        assertIndexExists(jdbc);

        SqlSessionFactory factory = mapperFactory(dataSource);
        IUserQuestionDao dao = new SqlSessionTemplate(factory).getMapper(IUserQuestionDao.class);
        UserQuestionRepository repository = new UserQuestionRepository(dao);

        Assert.assertTrue(repository.insertIfAbsent(question("answered-1", UserQuestionStatuses.ANSWERED)));
        Assert.assertTrue(repository.insertIfAbsent(question("answered-2", UserQuestionStatuses.ANSWERED)));
        Assert.assertTrue(repository.insertIfAbsent(question("answered-3", UserQuestionStatuses.ANSWERED)));
        Assert.assertEquals(Integer.valueOf(3), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_user_question "
                        + "WHERE session_id = 'session-1' AND status = 'ANSWERED' AND deleted = 0",
                Integer.class));
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
        return question(questionId, UserQuestionStatuses.PENDING);
    }

    private static UserQuestionRecord question(String questionId, String status) {
        return UserQuestionRecord.builder()
                .questionId(questionId)
                .sessionId("session-1")
                .sourceRequestId("req-" + questionId)
                .questions(List.of())
                .status(status)
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

    private static void createOpenOnlyUniqueIndex(JdbcTemplate jdbc) {
        jdbc.execute("ALTER TABLE ai_agent_user_question "
                + "ADD COLUMN open_session_id VARCHAR(128) GENERATED ALWAYS AS ("
                + "CASE WHEN deleted = 0 "
                + "AND status IN ('PENDING', 'RESUME_PENDING', 'RESUMING') "
                + "THEN session_id ELSE NULL END)");
        jdbc.execute("CREATE UNIQUE INDEX uk_user_question_open_only "
                + "ON ai_agent_user_question (open_session_id)");
    }

    private static void assertIndexExists(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE INDEX_NAME = 'UK_USER_QUESTION_OPEN_ONLY'",
                Integer.class);
        Assert.assertTrue("测试必须安装 uk_user_question_open_only", count != null && count > 0);
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
