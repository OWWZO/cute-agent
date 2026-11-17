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

    private static final class Fixture {
        private final TaskReviewApplicationService service;

        private Fixture(int status) {
            ConversationSessionAuthorizationService authorization =
                    Mockito.mock(ConversationSessionAuthorizationService.class);
            ExecutionLedgerQueryService ledger =
                    Mockito.mock(ExecutionLedgerQueryService.class);
            LlmModelCatalog llmModelCatalog =
                    Mockito.mock(LlmModelCatalog.class);
            DialogueSessionView session = DialogueSessionView.builder()
                    .sessionId("session-1")
                    .title("测试会话")
                    .model("test-model")
                    .build();
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
            Mockito.when(ledger.querySession("user-1", "session-1")).thenReturn(session);
            Mockito.when(ledger.queryRunSummary("user-1", "req-1")).thenReturn(run);
            Mockito.when(ledger.queryRunDetail("req-1")).thenReturn(detail);
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
