package org.wwz.ai.infrastructure.mcp.client;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;

public class McpClientRuntimeLockTest {

    @Test
    public void nullBuilderLockFallsBackToUsableLock() {
        McpClientRuntime runtime = McpClientRuntime.builder()
                .lock(null)
                .build();

        assertNotNull(runtime.getLock());
        assertFalse(Arrays.stream(McpClientRuntime.class.getMethods())
                .anyMatch(method -> method.getName().equals("setLock")));
    }
}
