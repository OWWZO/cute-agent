package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspacePaths;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceRuntimeOptions;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceService;

import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作区血缘上溯：子会话与父共享工作区（模式 2 靠 parent_session_id 查回来）。
 */
public class WorkspaceRootLineageTest {

    private static WorkspaceService newService() {
        return new WorkspaceService(
                WorkspaceRuntimeOptions.builder()
                        .enabled(true)
                        .rootTemplate("${user.dir}/cute-tool/skilloutput/{sessionId}")
                        .build(),
                null, null, null);
    }

    @Test
    public void rootSessionResolvesToItself() {
        IExecutionLedgerReadRepository ledger = mock(IExecutionLedgerReadRepository.class);
        when(ledger.querySessionEntity("session-root")).thenReturn(
                DialogueSession.builder()
                        .sessionId("session-root")
                        .rootSessionId("session-root")
                        .build());
        WorkspaceService service = newService();
        service.setExecutionLedgerReadRepository(ledger);

        Assert.assertTrue(service.resolveSessionWorkspaceRoot("session-root")
                .endsWith(Path.of("skilloutput", "session-root")));
    }

    @Test
    public void childSessionResolvesToParentWorkspace() {
        IExecutionLedgerReadRepository ledger = mock(IExecutionLedgerReadRepository.class);
        when(ledger.querySessionEntity("sub_agent-1")).thenReturn(
                DialogueSession.builder()
                        .sessionId("sub_agent-1")
                        .parentSessionId("session-parent")
                        .rootSessionId("session-parent")
                        .build());
        WorkspaceService service = newService();
        service.setExecutionLedgerReadRepository(ledger);

        Assert.assertTrue("子会话必须落到父的工作区，否则读不到父写的文件",
                service.resolveSessionWorkspaceRoot("sub_agent-1")
                        .endsWith(Path.of("skilloutput", "session-parent")));
    }

    @Test
    public void lineageWalksUpWhenRootSessionIdMissing() {
        IExecutionLedgerReadRepository ledger = mock(IExecutionLedgerReadRepository.class);
        // 存量行 root_session_id 尚未回填：靠 parent_session_id 逐层上溯
        when(ledger.querySessionEntity("sub_agent-2")).thenReturn(
                DialogueSession.builder()
                        .sessionId("sub_agent-2")
                        .parentSessionId("session-mid")
                        .build());
        when(ledger.querySessionEntity("session-mid")).thenReturn(
                DialogueSession.builder()
                        .sessionId("session-mid")
                        .parentSessionId("session-root")
                        .build());
        when(ledger.querySessionEntity("session-root")).thenReturn(
                DialogueSession.builder().sessionId("session-root").build());
        WorkspaceService service = newService();
        service.setExecutionLedgerReadRepository(ledger);

        Assert.assertTrue(service.resolveSessionWorkspaceRoot("sub_agent-2")
                .endsWith(Path.of("skilloutput", "session-root")));
    }

    @Test
    public void missingParentFallsBackToOwnDirectory() {
        IExecutionLedgerReadRepository ledger = mock(IExecutionLedgerReadRepository.class);
        when(ledger.querySessionEntity("sub_orphan")).thenReturn(null);
        WorkspaceService service = newService();
        service.setExecutionLedgerReadRepository(ledger);

        Assert.assertTrue("父不存在时子会话至少要能工作",
                service.resolveSessionWorkspaceRoot("sub_orphan")
                        .endsWith(Path.of("skilloutput", "sub_orphan")));
    }

    @Test
    public void lineageCycleDoesNotHang() {
        IExecutionLedgerReadRepository ledger = mock(IExecutionLedgerReadRepository.class);
        when(ledger.querySessionEntity("a")).thenReturn(
                DialogueSession.builder().sessionId("a").parentSessionId("b").build());
        when(ledger.querySessionEntity("b")).thenReturn(
                DialogueSession.builder().sessionId("b").parentSessionId("a").build());
        WorkspaceService service = newService();
        service.setExecutionLedgerReadRepository(ledger);

        // 循环时保守回退到自身，不抛异常、不死循环
        Assert.assertTrue(service.resolveSessionWorkspaceRoot("a")
                .endsWith(Path.of("skilloutput", "a")));
    }

    @Test
    public void withoutLedgerRepositoryFallsBackToOwnDirectory() {
        WorkspaceService service = newService();
        Assert.assertTrue(service.resolveSessionWorkspaceRoot("sub_agent-3")
                .endsWith(Path.of("skilloutput", "sub_agent-3")));
        Assert.assertTrue(WorkspacePaths.resolveRepoRoot().toFile().isDirectory());
    }
}
