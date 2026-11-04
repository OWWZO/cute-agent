package org.wwz.ai.infrastructure.dataquery.jdbc.connection;

import org.junit.Test;
import org.wwz.ai.infrastructure.dataquery.jdbc.JdbcConnectionConfig;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JdbcConnectionLifecycleTest {

    @Test
    public void zeroRetryConfigurationFailsBeforeReturningNullConnection() {
        ConnectionWrapper wrapper = new ConnectionWrapper();
        JdbcConnectionConfig config = new JdbcConnectionConfig();
        config.setKey("pool-1");
        config.setMaxRetryTimes(0);
        wrapper.setJdbcConnectionConfig(config);

        try {
            wrapper.getConnection();
            fail("expected invalid retry configuration to fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("maxRetryTimes"));
        }
    }

    @Test
    public void nullDatasourceConnectionFailsWithContext() {
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> null);
        DatasourceWrapper datasourceWrapper = new DatasourceWrapper();
        datasourceWrapper.setDataSource(dataSource);
        ConnectionWrapper wrapper = new ConnectionWrapper();
        JdbcConnectionConfig config = new JdbcConnectionConfig();
        config.setKey("pool-null");
        config.setMaxRetryTimes(1);
        wrapper.setJdbcConnectionConfig(config);
        wrapper.setDatasourceWrapper(datasourceWrapper);

        try {
            wrapper.getConnection();
            fail("expected null connection to fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("pool-null"));
        }
    }

    @Test
    public void datasourceCloseIsRetriedAfterFirstFailure() {
        AtomicBoolean failFirstClose = new AtomicBoolean(true);
        AtomicInteger closeCount = new AtomicInteger();
        AutoCloseable dataSource = (AutoCloseable) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class, AutoCloseable.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        if (failFirstClose.compareAndSet(true, false)) {
                            throw new IOException("close failed");
                        }
                        closeCount.incrementAndGet();
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
        DatasourceWrapper wrapper = new DatasourceWrapper();
        wrapper.setDataSource((DataSource) dataSource);

        try {
            wrapper.close();
            fail("expected first close to fail");
        } catch (IllegalStateException expected) {
            assertFalse(wrapper.getClosed().get());
        }

        wrapper.close();
        assertEquals(1, closeCount.get());
        assertTrue(wrapper.getClosed().get());
    }
}
