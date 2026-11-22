package org.wwz.ai.application.agent.review;

import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.ConversationMessageView;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.DialogueSessionView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.SessionKind;
import org.wwz.ai.domain.agent.runtime.llm.LLMSettings;
import org.wwz.ai.domain.agent.runtime.llm.LlmModelCatalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TaskReviewApplicationServiceTest {

    @Test
    public void preflightMarksNonTerminalRunAsBlocked() {
        Fixture fixture = new Fixture(ExecutionLedgerConstants.STATUS_RUNNING);

        Map<String, Object> result = fixture.service.preflight(
                "user-1",
                "session-1",
                List.of("req-1")
        );

        Assert.assertEquals(false, result.get("canReview"));
        Assert.assertEquals(1, ((List<?>) result.get("blockers")).size());
    }

    @Test
    public void exportBundleContainsSanitizedRun() {
        Fixture fixture = new Fixture(ExecutionLedgerConstants.STATUS_SUCCESS);

        JSONObject bundle = fixture.service.exportBundle(
                "user-1",
                "session-1",
                List.of("req-1")
        );

        Assert.assertEquals(1, bundle.getJSONArray("runs").size());
        Assert.assertEquals(
                "req-1",
                bundle.getJSONArray("runs")
                        .getJSONObject(0)
                        .getJSONObject("trace")
                        .getJSONObject("run")
                        .getString("requestId")
        );
        Assert.assertEquals(
                1,
                bundle.getJSONArray("runs")
                        .getJSONObject(0)
                        .getJSONObject("trace")
                        .getJSONArray("conversation")
                        .size()
        );
        Assert.assertEquals(
                "req-1",
                bundle.getJSONArray("runs")
                        .getJSONObject(0)
                        .getJSONObject("trace")
                        .getJSONArray("conversation")
                        .getJSONObject(0)
                        .getString("requestId")
        );
    }

    @Test
    public void modelConfigUsesCurrentSessionModel() {
        Fixture fixture = new Fixture(ExecutionLedgerConstants.STATUS_SUCCESS);

        Map<String, Object> config = fixture.service.modelConfig(
                "user-1",
                "session-1"
        );

        Assert.assertEquals("test-model", config.get("modelRef"));
        Assert.assertEquals("group/deepseek", config.get("model"));
        Assert.assertEquals("http://127.0.0.1:3425/v1", config.get("baseUrl"));
        Assert.assertEquals("test-key", config.get("apiKey"));
    }

    @Test
    public void exportTreeBundleIncludesAllSessionsInTheTree() {
        Fixture fixture = new Fixture(ExecutionLedgerConstants.STATUS_SUCCESS);

        JSONObject bundle = fixture.service.exportTreeBundle("user-1", "session-1");

        Assert.assertEquals("tree", bundle.getString("scope"));
        Assert.assertEquals("session-1", bundle.getString("rootSessionId"));
        Assert.assertEquals(3, bundle.getJSONArray("sessions").size());
        Assert.assertEquals(3, bundle.getJSONArray("runs").size());
        Assert.assertEquals(
                "session-1",
                bundle.getJSONArray("runs").getJSONObject(0).getString("sessionId")
        );
        Assert.assertEquals(
                "sub-agent",
                bundle.getJSONArray("runs").getJSONObject(1).getString("sessionId")
        );
        Assert.assertEquals(
                "sub-sub-agent",
                bundle.getJSONArray("runs").getJSONObject(2).getString("sessionId")
        );
    }

    @Test
    public void exportTreeBundleRejectsSessionTreeWithNoTerminalRun() {
        Fixture fixture = new Fixture(ExecutionLedgerConstants.STATUS_RUNNING);

        try {
            fixture.service.exportTreeBundle("user-1", "session-1");
            Assert.fail("应当拒绝未结束的 Run");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("req-1"));
        }
    }

    @Test
    public void exportTreeBundleRejectsOversizedSessionTree() {
        Fixture fixture = new Fixture(
                ExecutionLedgerConstants.STATUS_SUCCESS,
                TaskReviewApplicationService.MAX_SESSION_TREE_SIZE + 1
        );

        try {
            fixture.service.exportTreeBundle("user-1", "session-1");
            Assert.fail("应当拒绝超过节点上限的会话树");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("会话树"));
        }
    }

    private static final class Fixture {
        private final TaskReviewApplicationService service;

        private Fixture(int status) {
            this(status, 3);
        }

        private Fixture(int status, int sessionCount) {
            ConversationSessionAuthorizationService authorization =
                    Mockito.mock(ConversationSessionAuthorizationService.class);
            ExecutionLedgerQueryService ledger =
                    Mockito.mock(ExecutionLedgerQueryService.class);
            LlmModelCatalog llmModelCatalog =
                    Mockito.mock(LlmModelCatalog.class);
            DialogueRunView run = DialogueRunView.builder()
                    .id(1L)
                    .requestId("req-1")
                    .sessionId("session-1")
                    .userId("user-1")
                    .status(status)
                    .entryAgent("react")
                    .build();
            ExecutionRunDetail detail = ExecutionRunDetail.builder()
                    .run(run)
                    .conversation(List.of(
                            ConversationMessageView.builder()
                                    .messageId("previous")
                                    .requestId("req-0")
                                    .role("assistant")
                                    .content("上一轮")
                                    .build(),
                            ConversationMessageView.builder()
                                    .messageId("current")
                                    .requestId("req-1")
                                    .role("assistant")
                                    .content("当前轮")
                                    .build(),
                            ConversationMessageView.builder()
                                    .messageId("next")
                                    .requestId("req-2")
                                    .role("assistant")
                                    .content("下一轮")
                                    .build()
                    ))
                    .build();
            List<DialogueSessionView> sessions = new java.util.ArrayList<>();
            for (int index = 0; index < sessionCount; index++) {
                String sessionId = switch (index) {
                    case 0 -> "session-1";
                    case 1 -> "sub-agent";
                    default -> "sub-sub-agent";
                };
                String parentSessionId = switch (index) {
                    case 0 -> null;
                    case 1 -> "session-1";
                    default -> "sub-agent";
                };
                sessions.add(DialogueSessionView.builder()
                        .sessionId(sessionId)
                        .parentSessionId(parentSessionId)
                        .rootSessionId("session-1")
                        .sessionKind(index == 0 ? SessionKind.CONVERSATION.code() : SessionKind.SUBAGENT.code())
                        .title("测试会话 " + index)
                        .model("test-model")
                        .build());
            }
            DialogueSessionView session = sessions.get(0);
            Mockito.when(ledger.querySession("user-1", "session-1")).thenReturn(session);
            Mockito.when(ledger.querySessionTree(
                    Mockito.eq("user-1"),
                    Mockito.eq("session-1"),
                    Mockito.anyInt()
            )).thenReturn(sessions);
            Mockito.when(ledger.queryRunSummary("user-1", "req-1")).thenReturn(run);
            Mockito.when(ledger.queryRunDetail("req-1")).thenReturn(detail);
            for (DialogueSessionView treeSession : sessions) {
                String requestId = "req-" + treeSession.getSessionId();
                DialogueRunView treeRun = DialogueRunView.builder()
                        .id(1L)
                        .requestId(requestId)
                        .sessionId(treeSession.getSessionId())
                        .userId("user-1")
                        .status(status)
                        .entryAgent("react")
                        .build();
                ExecutionRunDetail treeDetail = ExecutionRunDetail.builder()
                        .run(treeRun)
                        .conversation(List.of())
                        .build();
                Mockito.when(ledger.queryRunSummary("user-1", requestId)).thenReturn(treeRun);
                Mockito.when(ledger.queryRunDetail(requestId)).thenReturn(treeDetail);
                Mockito.when(ledger.querySessionRuns(
                        treeSession.getSessionId(),
                        0,
                        TaskReviewApplicationService.MAX_REQUEST_IDS + 1
                )).thenReturn(List.of(treeRun));
            }
            Mockito.when(llmModelCatalog.resolve("test-model")).thenReturn(
                    Optional.of(LLMSettings.builder()
                            .model("group/deepseek")
                            .baseUrl("http://127.0.0.1:3425/v1")
                            .apiKey("test-key")
                            .interfaceUrl("/chat/completions")
                            .build())
            );
            this.service = new TaskReviewApplicationService(
                    authorization,
                    ledger,
                    new TaskReviewTraceExportService(),
                    llmModelCatalog
            );
        }
    }
}
