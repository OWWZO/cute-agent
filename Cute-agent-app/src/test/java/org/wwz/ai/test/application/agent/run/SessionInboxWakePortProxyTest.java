package org.wwz.ai.test.application.agent.run;

import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.wwz.ai.application.agent.run.BackgroundTaskWakeAdapter;
import org.wwz.ai.config.cute.CuteRuntimeAutoConfiguration;
import org.wwz.ai.domain.agent.adapter.port.BackgroundTaskWakePort;

public class SessionInboxWakePortProxyTest {

    @Test
    public void proxyForwardsRequestScopedSettlementAndInboxChanges() {
        BackgroundTaskWakeAdapter adapter = Mockito.mock(BackgroundTaskWakeAdapter.class);
        ObjectProvider<BackgroundTaskWakeAdapter> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(adapter);

        BackgroundTaskWakePort port = new CuteRuntimeAutoConfiguration().backgroundTaskWakePort(provider);
        port.onRunSettled("sess-proxy", "req-proxy");
        port.onInboxChanged("sess-proxy");

        Mockito.verify(adapter).onRunSettled("sess-proxy", "req-proxy");
        Mockito.verify(adapter).onInboxChanged("sess-proxy");
    }
}
