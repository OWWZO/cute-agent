package org.wwz.ai.test.infrastructure;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.wwz.ai.domain.agent.memory.SessionWorkingMemoryService;
import org.wwz.ai.domain.agent.memory.WorkingMemoryMessage;
import org.wwz.ai.domain.agent.memory.WorkingMemorySearchMessage;
import org.wwz.ai.domain.agent.memory.WorkingMemoryTurn;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.infrastructure.cute.service.impl.SessionWorkingMemoryServiceImpl;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryMessageDao;
import org.wwz.ai.infrastructure.dao.cute.WorkingMemoryTermScanHit;
import org.wwz.ai.infrastructure.dao.cute.WorkingMemoryTermScanTerm;
import org.wwz.ai.infrastructure.dao.cute.IWorkingMemoryTurnDao;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 压缩投影替换必须原子：新 turn / 新消息写入失败时，旧 READY turn 不能被永久标 INVALID。
 */
public class SessionWorkingMemoryReplaceAtomicityTest {

    @Test
    public void failedReplacementRollsBackReadyInvalidationAndPropagates() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wm_replace_atomic;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO ai_agent_working_memory_turn "
                + "(id, session_id, memory_scope, request_id, turn_seq, entry_agent, status, schema_version, "
                + "message_count, token_estimate, deleted) VALUES (1, 'session-1', 'main', 'old-request', 1, "
                + "'react', 1, 1, 1, 10, 0)");

        SessionWorkingMemoryServiceImpl target = new SessionWorkingMemoryServiceImpl(
                new JdbcTurnDao(jdbc), new FailingMessageDao());
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(SessionWorkingMemoryService.class);
        proxyFactory.addAdvice(interceptor);
        SessionWorkingMemoryService service = (SessionWorkingMemoryService) proxyFactory.getProxy();

        try {
            service.replaceReadyProjection("session-1", "main", "compact-1",
                    List.of(Message.userMessage("compacted", null)));
            Assert.fail("投影写入失败必须向调用方抛出异常");
        } catch (RuntimeException expected) {
            Assert.assertTrue(expected.getMessage().contains("simulated message insert failure"));
        }

        Integer status = jdbc.queryForObject(
                "SELECT status FROM ai_agent_working_memory_turn WHERE id = 1", Integer.class);
        Assert.assertEquals(Integer.valueOf(WorkingMemoryTurn.STATUS_READY), status);
        Assert.assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_working_memory_turn WHERE id <> 1", Integer.class));
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ai_agent_working_memory_turn ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(128), memory_scope VARCHAR(64), "
                + "request_id VARCHAR(128), run_id BIGINT, turn_seq INT, entry_agent VARCHAR(32), status INT, "
                + "schema_version INT, message_count INT, token_estimate INT, started_at TIMESTAMP, "
                + "finished_at TIMESTAMP, create_time TIMESTAMP, update_time TIMESTAMP, deleted INT)");
        jdbc.execute("CREATE TABLE ai_agent_working_memory_message ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(128), memory_scope VARCHAR(64), "
                + "turn_id BIGINT, request_id VARCHAR(128), seq_no INT, deleted INT)");
    }

    private static final class JdbcTurnDao implements IWorkingMemoryTurnDao {
        private final JdbcTemplate jdbc;

        private JdbcTurnDao(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public int insertTurn(WorkingMemoryTurn turn) {
            jdbc.update("INSERT INTO ai_agent_working_memory_turn "
                    + "(session_id, memory_scope, request_id, run_id, turn_seq, entry_agent, status, "
                    + "schema_version, message_count, token_estimate, started_at, finished_at, deleted) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                    turn.getSessionId(), turn.getMemoryScope(), turn.getRequestId(), turn.getRunId(),
                    turn.getTurnSeq(), turn.getEntryAgent(), turn.getStatus(), turn.getSchemaVersion(),
                    turn.getMessageCount(), turn.getTokenEstimate(), turn.getStartedAt(), turn.getFinishedAt());
            turn.setId(jdbc.queryForObject("SELECT MAX(id) FROM ai_agent_working_memory_turn", Long.class));
            return 1;
        }

        @Override
        public Integer selectMaxTurnSeq(String sessionId, String memoryScope) {
            return jdbc.queryForObject("SELECT MAX(turn_seq) FROM ai_agent_working_memory_turn "
                    + "WHERE session_id = ? AND memory_scope = ? AND deleted = 0",
                    Integer.class, sessionId, memoryScope);
        }

        @Override
        public WorkingMemoryTurn selectByRequestId(String requestId) {
            return null;
        }

        @Override
        public List<WorkingMemoryTurn> selectReadyBySessionIdAndScope(String sessionId, String memoryScope) {
            return List.of();
        }

        @Override
        public int markReadyInvalidBySessionIdAndScope(String sessionId, String memoryScope) {
            return jdbc.update("UPDATE ai_agent_working_memory_turn SET status = 2 "
                    + "WHERE session_id = ? AND memory_scope = ? AND status = 1 AND deleted = 0",
                    sessionId, memoryScope);
        }
    }

    private static final class FailingMessageDao implements IWorkingMemoryMessageDao {
        @Override
        public int batchInsertMessages(List<WorkingMemoryMessage> records) {
            throw new IllegalStateException("simulated message insert failure");
        }

        @Override
        public List<WorkingMemoryMessage> selectBySessionIdOrdered(String sessionId) {
            return List.of();
        }

        @Override
        public List<WorkingMemoryMessage> selectByTurnIds(List<Long> turnIds) {
            return List.of();
        }

        @Override
        public List<WorkingMemorySearchMessage> searchFullTextBySession(String sessionId, String query,
                                                                       int limit, List<String> roles) {
            return List.of();
        }

        @Override
        public List<WorkingMemorySearchMessage> searchFullTextByUserId(String userId, String query,
                                                                      int limit, List<String> roles) {
            return List.of();
        }

        @Override
        public List<WorkingMemorySearchMessage> scanBySession(String sessionId, String query,
                                                             int limit, List<String> roles) {
            return List.of();
        }

        @Override
        public List<WorkingMemorySearchMessage> scanByUserId(String userId, String query,
                                                            int limit, List<String> roles) {
            return List.of();
        }

        @Override
        public List<WorkingMemoryTermScanHit> scanTerms(String userId, String sessionId,
                                                        List<WorkingMemoryTermScanTerm> terms, List<String> roles) {
            return List.of();
        }

        @Override
        public WorkingMemorySearchMessage selectScrollAnchor(String sessionId, long messageId) {
            return null;
        }

        @Override
        public List<WorkingMemorySearchMessage> selectScrollBefore(String sessionId, long messageId,
                                                                  int limit, List<String> roles) {
            return List.of();
        }

        @Override
        public List<WorkingMemorySearchMessage> selectScrollAfter(String sessionId, long messageId,
                                                                 int limit, List<String> roles) {
            return List.of();
        }

        @Override
        public List<WorkingMemorySearchMessage> selectHistoryBySession(String sessionId) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> selectRecentSessions(String userId, int limit) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> selectSessionSummaries(List<String> sessionIds) {
            return List.of();
        }

        @Override
        public Map<String, Object> selectSessionSummary(String sessionId) {
            return Map.of();
        }
    }
}
