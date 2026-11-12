package org.wwz.ai.test.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.wwz.ai.domain.agent.memory.ltm.CuratedMemoryEntry;
import org.wwz.ai.domain.agent.memory.ltm.CuratedMemoryScope;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwner;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwnerResolver;
import org.wwz.ai.infrastructure.dao.cute.ILtmCuratedEntryDao;
import org.wwz.ai.infrastructure.memory.MyBatisCuratedMemoryStore;
import org.wwz.ai.types.agent.user.UserRequestContext;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LtmOwnerMigrationIntegrationTest {

    private static final String ERP_ID = "reactor";
    private static final String REAL_USER_ID = "bd6d94ad-8078-45fc-a01b-368d6c51a9f9";
    private static final String SESSION_USER_ID = "visitor_989d57177cf341959e9ef18b2bcdb6c2";

    @After
    public void tearDown() {
        UserRequestContext.clear();
    }

    @Before
    public void setUp() {
        UserRequestContext.clear();
    }

    @Test
    public void migrationMakesLegacyErpRowsReadableAndReverseUpdateRestoresThem() throws Exception {
        UserRequestContext.clear();
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:ltm_owner_migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        seedLegacyRows(jdbc);

        SqlSessionFactory factory = mapperFactory(dataSource);
        try (SqlSession session = factory.openSession(true)) {
            MyBatisCuratedMemoryStore store = new MyBatisCuratedMemoryStore(session.getMapper(ILtmCuratedEntryDao.class));
            LtmOwner erpOwner = LtmOwnerResolver.resolve(null, ERP_ID, null);
            Assert.assertEquals("ERP", erpOwner.getType().name());
            Assert.assertTrue("迁移前旧 USER 行不应被新 ERP owner 读到",
                    store.listActive(erpOwner, CuratedMemoryScope.CURATED).isEmpty());

            jdbc.update("CREATE TABLE ai_agent_ltm_curated_entry_bak_ltm_20261112 AS "
                    + "SELECT * FROM ai_agent_ltm_curated_entry");
            Assert.assertEquals("迁移前受影响行数", 3, countAffectedRows(jdbc));
            Assert.assertEquals("迁移影响行数", 3, jdbc.update(migrationStatement(false)));
            session.clearCache();
            Assert.assertEquals("重复执行应幂等", 0, jdbc.update(migrationStatement(false)));
            List<CuratedMemoryEntry> migrated = store.listActive(erpOwner, CuratedMemoryScope.CURATED);
            Assert.assertEquals(1, migrated.size());
            Assert.assertEquals("erp-history-active", migrated.get(0).getContent());
            Assert.assertEquals(1, store.listActive(erpOwner, CuratedMemoryScope.USER).size());
            String snapshot = store.formatSnapshot(erpOwner);
            Assert.assertTrue(snapshot.contains("erp-history-active"));
            Assert.assertTrue(snapshot.contains("erp-profile-active"));
            Assert.assertFalse(snapshot.contains("erp-history-deleted"));

            Assert.assertEquals("真实用户行不应迁移", 1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_agent_ltm_curated_entry "
                    + "WHERE owner_type='USER' AND owner_id=? AND scope='user' AND status='ACTIVE' AND deleted=0",
                    Integer.class, REAL_USER_ID).intValue());
            Assert.assertEquals("迁移后关系判据下不应残留 USER 行", 0, countAffectedRows(jdbc));

            Assert.assertEquals("反向 UPDATE 影响行数", 3, jdbc.update(migrationStatement(true)));
            session.clearCache();
            Assert.assertTrue("回滚后旧 USER 行不再被新 ERP owner 读到",
                    store.listActive(erpOwner, CuratedMemoryScope.CURATED).isEmpty());
            Assert.assertEquals("回滚后受影响行数", 3, countAffectedRows(jdbc));
        }
    }

    @Test
    public void migrationSqlRejectsKnownUserIdsAndUnprovenSessionIdentities() throws Exception {
        String sql = migrationScript();
        Assert.assertTrue(sql.contains("s.deleted = 0"));
        Assert.assertTrue(sql.contains("s.user_id <> c.owner_id"));
        Assert.assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM user_account owner_account"));
        Assert.assertTrue(sql.contains("session_account.user_id = s.user_id"));
        Assert.assertTrue(sql.contains("ai_agent_dialogue_run"));
        Assert.assertTrue(sql.contains("c.owner_type = 'USER'"));
        Assert.assertTrue(sql.contains("SET owner_type = 'ERP'"));
        Assert.assertFalse("表内没有 owner_key 列，不应按任务书的旧列名改库", sql.contains("owner_key"));
        Assert.assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS ai_agent_ltm_curated_entry_bak_ltm_20261112"));
        Assert.assertTrue(sql.contains("SET owner_type = 'USER'"));
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ai_agent_ltm_curated_entry ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "stream_id BIGINT NULL,"
                + "owner_type VARCHAR(16) NOT NULL,"
                + "owner_id VARCHAR(64) NOT NULL,"
                + "scope VARCHAR(32) NOT NULL,"
                + "content TEXT NOT NULL,"
                + "status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',"
                + "source_session_id VARCHAR(128),"
                + "source_request_id VARCHAR(64),"
                + "write_origin VARCHAR(32),"
                + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE ai_agent_dialogue_session ("
                + "session_id VARCHAR(128) PRIMARY KEY,"
                + "user_id VARCHAR(64) NOT NULL,"
                + "deleted TINYINT NOT NULL DEFAULT 0,"
                + "UNIQUE KEY uk_dialogue_session_id (session_id, deleted))");
        jdbc.execute("CREATE TABLE user_account ("
                + "user_id VARCHAR(64) PRIMARY KEY,"
                + "deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE ai_agent_dialogue_run ("
                + "session_id VARCHAR(128) NOT NULL,"
                + "request_id VARCHAR(64) NOT NULL,"
                + "user_id VARCHAR(64) NULL)");
    }

    private static void seedLegacyRows(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO ai_agent_dialogue_session (session_id, user_id) VALUES (?, ?), (?, ?)",
                "session-erp", SESSION_USER_ID,
                "session-real-user", REAL_USER_ID);
        jdbc.update("INSERT INTO user_account (user_id) VALUES (?), (?)", SESSION_USER_ID, REAL_USER_ID);
        insertLegacyRow(jdbc, ERP_ID, "curated", "erp-history-active", "session-erp", "req-erp-active", "ACTIVE", 0);
        insertLegacyRow(jdbc, ERP_ID, "user", "erp-profile-active", "session-erp", "req-erp-profile", "ACTIVE", 0);
        insertLegacyRow(jdbc, ERP_ID, "curated", "erp-history-deleted", "session-erp", "req-erp-deleted", "DELETED", 1);
        insertLegacyRow(jdbc, REAL_USER_ID, "user", "real-user-profile", "session-real-user", "req-real-user", "ACTIVE", 0);
        jdbc.update("INSERT INTO ai_agent_dialogue_run (session_id, request_id, user_id) "
                        + "VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?), (?, ?, ?)",
                "session-erp", "req-erp-active", SESSION_USER_ID,
                "session-erp", "req-erp-profile", SESSION_USER_ID,
                "session-erp", "req-erp-deleted", SESSION_USER_ID,
                "session-real-user", "req-real-user", REAL_USER_ID);
        insertLegacyRow(jdbc, "no-session", "curated", "unprovable-owner", null, null, "ACTIVE", 0);
    }

    private static void insertLegacyRow(JdbcTemplate jdbc,
                                        String ownerId,
                                        String scope,
                                        String content,
                                        String sourceSessionId,
                                        String sourceRequestId,
                                        String status,
                                        int deleted) {
        jdbc.update("INSERT INTO ai_agent_ltm_curated_entry "
                        + "(owner_type, owner_id, scope, content, status, source_session_id, source_request_id, deleted) "
                        + "VALUES ('USER', ?, ?, ?, ?, ?, ?, ?)",
                ownerId, scope, content, status, sourceSessionId, sourceRequestId, deleted);
    }

    private static int countAffectedRows(JdbcTemplate jdbc) {
        return jdbc.queryForObject(migrationScriptBody(), Integer.class);
    }

    private static String migrationStatement(boolean reverse) throws Exception {
        String sql = migrationScript();
        if (reverse) {
            Matcher matcher = Pattern.compile(
                    "(?ms)^-- UPDATE ai_agent_ltm_curated_entry c.*?;").matcher(sql);
            Assert.assertTrue("迁移脚本必须提供按备份表逐行恢复的反向 UPDATE", matcher.find());
            return matcher.group(0).replaceAll("(?m)^-- ?", "").trim();
        }
        Matcher matcher = Pattern.compile(
                "(?ms)^UPDATE ai_agent_ltm_curated_entry c\\s*SET owner_type = 'ERP'.*?;").matcher(sql);
        Assert.assertTrue("未找到正式迁移 UPDATE", matcher.find());
        return matcher.group(0);
    }

    private static String migrationScriptBody() {
        try {
            String sql = migrationScript();
            Matcher matcher = Pattern.compile(
                    "(?ms)^SELECT COUNT\\(\\*\\) AS affected_rows\\s*$(.*?);").matcher(sql);
            Assert.assertTrue("未找到受影响行数统计 SQL", matcher.find());
            return matcher.group(0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String migrationScript() throws Exception {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path migration = current.resolve("db/migrations/20261112_ltm_curated_erp_owner_migration.sql");
            if (Files.isRegularFile(migration)) {
                return Files.readString(migration);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到 LTM owner 迁移脚本");
    }

    private static SqlSessionFactory mapperFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:/mybatis/mapper/ltm_curated_entry_mapper.xml"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(CuratedMemoryScope.class,
                org.wwz.ai.infrastructure.memory.CuratedMemoryScopeTypeHandler.class);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }
}
