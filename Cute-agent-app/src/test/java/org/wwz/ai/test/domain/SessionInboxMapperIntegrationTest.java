package org.wwz.ai.test.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxItem;
import org.wwz.ai.domain.agent.runtime.inbox.SessionInboxPayloads;
import org.wwz.ai.infrastructure.dao.cute.ISessionInboxDao;
import org.wwz.ai.infrastructure.inbox.MyBatisSessionInboxPersistence;

import javax.sql.DataSource;
import java.util.List;

public class SessionInboxMapperIntegrationTest {

    @Test
    public void offerIsIdempotentAndAckRequiresClaimOwnership() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:session_inbox_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);

        try (SqlSession session = mapperFactory(dataSource).openSession(true)) {
            MyBatisSessionInboxPersistence inbox =
                    new MyBatisSessionInboxPersistence(session.getMapper(ISessionInboxDao.class));

            SessionInboxItem first = inbox.offer(userItem("client-1", "用户指导"));
            SessionInboxItem duplicate = inbox.offer(userItem("client-1", "重复内容不覆盖"));
            Assert.assertEquals(first.getId(), duplicate.getId());
            Assert.assertEquals("用户指导", SessionInboxPayloads.userText(duplicate.getPayloadJson()));

            List<SessionInboxItem> claimed = inbox.claimPending(
                    "sess-inbox", "run-1", SessionInboxItem.KIND_USER_MESSAGE, 10);
            Assert.assertEquals(1, claimed.size());
            Assert.assertEquals(SessionInboxItem.STATE_CLAIMED, claimed.get(0).getState());

            Assert.assertEquals(0, inbox.ackDelivered(
                    "sess-inbox", List.of(claimed.get(0).getId()), "run-other"));
            Assert.assertEquals(1, inbox.ackDelivered(
                    "sess-inbox", List.of(claimed.get(0).getId()), "run-1"));
            Assert.assertFalse(inbox.hasOutstanding("sess-inbox"));
        }
    }

    @Test
    public void sameRunDoesNotReclaimItsExpiredLease() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:session_inbox_same_run;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);

        try (SqlSession session = mapperFactory(dataSource).openSession(true)) {
            MyBatisSessionInboxPersistence inbox =
                    new MyBatisSessionInboxPersistence(session.getMapper(ISessionInboxDao.class));
            SessionInboxItem first = inbox.offer(userItem("client-expired", "指导"));
            List<SessionInboxItem> claimed = inbox.claimPending(
                    "sess-inbox", "run-1", SessionInboxItem.KIND_USER_MESSAGE, 10);
            Assert.assertEquals(1, claimed.size());
            jdbc.update("UPDATE ai_agent_session_inbox SET lease_until_ms = 0 WHERE id = ?", first.getId());

            Assert.assertTrue("同一 run 不得重复认领自己已投递但未 ACK 的行",
                    inbox.claimPending(
                            "sess-inbox", "run-1", SessionInboxItem.KIND_USER_MESSAGE, 10).isEmpty());
            Assert.assertEquals("租约过期后可由其它 run 接管",
                    1, inbox.claimPending(
                            "sess-inbox", "run-2", SessionInboxItem.KIND_USER_MESSAGE, 10).size());
        }
    }

    @Test
    public void offerRejectsValuesThatWouldBeTruncatedOrBreakEnums() {
        ISessionInboxDao dao = Mockito.mock(ISessionInboxDao.class);
        MyBatisSessionInboxPersistence inbox = new MyBatisSessionInboxPersistence(dao);

        Assert.assertNull(inbox.offer(userItem("x".repeat(161), "指导")));
        Assert.assertNull(inbox.offer(SessionInboxItem.builder()
                .sessionId("sess-inbox")
                .kind(" ")
                .visibility(SessionInboxItem.VISIBILITY_ALL)
                .state(SessionInboxItem.STATE_PENDING)
                .idempotencyKey("client-invalid-kind")
                .payloadJson(SessionInboxPayloads.userMessage("指导"))
                .build()));

        Mockito.verifyNoInteractions(dao);
    }

    @Test
    public void heldUserMessageDoesNotGetClaimedUntilReleased() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:session_inbox_held;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);

        try (SqlSession session = mapperFactory(dataSource).openSession(true)) {
            MyBatisSessionInboxPersistence inbox =
                    new MyBatisSessionInboxPersistence(session.getMapper(ISessionInboxDao.class));
            inbox.offer(userItem("client-held", "等待确认"));
            Assert.assertEquals(1, inbox.holdPendingUserMessages("sess-inbox"));
            Assert.assertTrue(inbox.hasHeldUserMessage("sess-inbox"));
            Assert.assertTrue(inbox.claimPending(
                    "sess-inbox", "run-1", SessionInboxItem.KIND_USER_MESSAGE, 10).isEmpty());

            Assert.assertEquals(1, inbox.releaseHeldUserMessages("sess-inbox", "client-held"));
            List<SessionInboxItem> claimed = inbox.claimPending(
                    "sess-inbox", "run-2", SessionInboxItem.KIND_USER_MESSAGE, 10);
            Assert.assertEquals(1, claimed.size());
            Assert.assertEquals(SessionInboxItem.STATE_CLAIMED, claimed.get(0).getState());
        }
    }

    private static SessionInboxItem userItem(String key, String text) {
        return SessionInboxItem.builder()
                .sessionId("sess-inbox")
                .kind(SessionInboxItem.KIND_USER_MESSAGE)
                .visibility(SessionInboxItem.VISIBILITY_ALL)
                .state(SessionInboxItem.STATE_PENDING)
                .idempotencyKey(key)
                .payloadJson(SessionInboxPayloads.userMessage(text))
                .build();
    }

    private static SqlSessionFactory mapperFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:/mybatis/mapper/session_inbox_mapper.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ai_agent_session_inbox ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "session_id VARCHAR(128) NOT NULL,"
                + "kind VARCHAR(32) NOT NULL,"
                + "visibility VARCHAR(16) NOT NULL,"
                + "state VARCHAR(16) NOT NULL DEFAULT 'PENDING',"
                + "idempotency_key VARCHAR(160) NOT NULL,"
                + "payload_json VARCHAR(2000) NOT NULL,"
                + "source_run_id VARCHAR(64),"
                + "claimed_by_run_id VARCHAR(64),"
                + "lease_until_ms BIGINT,"
                + "created_at_ms BIGINT NOT NULL,"
                + "delivered_at_ms BIGINT,"
                + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "deleted TINYINT NOT NULL DEFAULT 0,"
                + "UNIQUE KEY uk_session_inbox_key (session_id, idempotency_key, deleted)"
                + ")");
    }
}
