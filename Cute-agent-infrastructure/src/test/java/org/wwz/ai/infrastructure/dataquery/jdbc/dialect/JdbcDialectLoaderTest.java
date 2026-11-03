package org.wwz.ai.infrastructure.dataquery.jdbc.dialect;

import org.junit.Test;
import org.wwz.ai.infrastructure.dataquery.jdbc.JdbcBizException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JdbcDialectLoaderTest {

    @Test
    public void unsupportedUrlDoesNotExposeCredentialsInErrorMessage() {
        try {
            JdbcDialectLoader.load("jdbc:unknown://host.example/db?user=alice&password=secret");
            fail("未知 JDBC URL 必须失败");
        } catch (JdbcBizException expected) {
            assertTrue(expected.getMessage().contains("jdbc:unknown://<redacted>"));
            assertFalse(expected.getMessage().contains("host.example"));
            assertFalse(expected.getMessage().contains("alice"));
            assertFalse(expected.getMessage().contains("secret"));
        }
    }
}
