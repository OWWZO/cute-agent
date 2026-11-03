package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest;

import java.io.IOException;

/**
 * RemoteHttpPort 默认实现的契约回归：不支持详细响应的适配器必须快速失败，
 * 不能返回伪造的 200 成功响应。
 */
public class RemoteHttpPortContractTest {

    @Test
    public void defaultExecuteDetailedFailsFastInsteadOfFabricatingSuccess() throws IOException {
        RemoteHttpPort executeOnlyPort = request -> "<html>ok</html>";
        RemoteHttpRequest request = RemoteHttpRequest.builder()
                .method("GET")
                .url("https://example.com/page")
                .build();

        try {
            executeOnlyPort.executeDetailed(request);
            Assert.fail("默认实现不应伪造 200 响应");
        } catch (UnsupportedOperationException expected) {
            Assert.assertTrue(expected.getMessage().contains("executeDetailed"));
        }
    }
}
