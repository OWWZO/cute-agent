package org.wwz.ai.test.domain;

import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.common.SandboxRemoteEndpoint;

import static org.junit.Assert.assertEquals;

public class SandboxRemoteEndpointTest {

    @Test(expected = IllegalStateException.class)
    public void blankUrlFailsFast() {
        SandboxRemoteEndpoint.requireHttpBaseUrl(" ");
    }

    @Test(expected = IllegalStateException.class)
    public void nonHttpUrlFailsFast() {
        SandboxRemoteEndpoint.requireHttpBaseUrl("ftp://cute-tool");
    }

    @Test
    public void trailingSlashIsNormalized() {
        assertEquals(
                "http://cute-tool/v1/tool/bash",
                SandboxRemoteEndpoint.toolUrl("http://cute-tool/", "/v1/tool/bash")
        );
    }
}
