package org.wwz.ai.infrastructure.dataquery.jdbc.dialect.h2;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class H2DialectFactoryTest {

    @Test
    public void nullAndBlankUrlsAreNotAccepted() {
        H2DialectFactory factory = new H2DialectFactory();
        assertFalse(factory.acceptsURL(null));
        assertFalse(factory.acceptsURL(" "));
        assertTrue(factory.acceptsURL("jdbc:h2:mem:test"));
    }
}
