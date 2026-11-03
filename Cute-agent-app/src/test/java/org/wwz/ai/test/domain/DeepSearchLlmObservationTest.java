package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.BaseAgent;
import org.wwz.ai.domain.agent.runtime.dto.DeepSearchResponse;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.DeepSearchStructuredResultBuilder;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchToolOutput;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * deep_search 的主智能体 observation 回归。
 */
public class DeepSearchLlmObservationTest {

    @Test
    public void shouldSeparateStructuredOutputAndCompactObservation() {
        DeepSearchStructuredResultBuilder builder = new DeepSearchStructuredResultBuilder("新能源车出口趋势");
        builder.recordEvent(DeepSearchResponse.builder()
                .messageType("extend")
                .searchResult(DeepSearchResponse.SearchResult.builder()
                        .query(List.of("中国新能源车出口数据", "欧洲市场需求变化"))
                        .build())
                .build());
        builder.recordEvent(DeepSearchResponse.builder()
                .messageType("search")
                .searchResult(DeepSearchResponse.SearchResult.builder()
                        .query(List.of("中国新能源车出口数据", "欧洲市场需求变化"))
                        .docs(List.of(
                                List.of(doc("海关总署：出口量创新高", "https://example.com/customs", repeat("出口量持续增长，", 30))),
                                List.of(doc("欧洲汽车协会：需求回暖", "https://example.com/eu", repeat("欧洲市场正在恢复，", 20)))
                        ))
                        .build())
                .build());
        builder.recordEvent(DeepSearchResponse.builder()
                .messageType("chapter_summary")
                .chapterId("C1")
                .chapterTitle("中国新能源车出口数据")
                .chapterOrder(1)
                .chapterSummary("出口量持续增长。")
                .searchResult(DeepSearchResponse.SearchResult.builder()
                        .query(List.of("中国新能源车出口数据"))
                        .docs(List.of(List.of(doc("海关总署：出口量创新高", "https://example.com/customs", "出口量持续增长"))))
                        .build())
                .build());
        builder.recordFinalAnswer("新能源车出口趋势", "综合多个来源，新能源车出口继续增长，欧洲需求回暖。");

        ToolResultPayload payload = builder.buildPayload("fallback");
        DeepSearchToolOutput structuredOutput = (DeepSearchToolOutput) payload.getStructuredOutput();
        // buildPayload 只产出强类型 llmData；llmObservation 由 ToolExecutionPipeline
        // 交给 ToolObservationFormatter 的 deep_search 投影器生成（输出 Markdown 散文，
        // 不是 JSON）。这里断言的是 buildPayload 自己的产出契约，即 llmData 对象。
        Assert.assertNull("llmObservation 应交由中央投影生成", payload.getLlmObservation());
        JSONObject llmObservation = (JSONObject) JSON.toJSON(payload.getLlmData());

        Assert.assertNotNull(structuredOutput);
        Assert.assertEquals("deep_search", structuredOutput.getToolName());
        Assert.assertEquals("新能源车出口趋势", structuredOutput.getQuery());
        Assert.assertEquals(4, structuredOutput.getStages().size());
        Assert.assertEquals(1, structuredOutput.getChapters().size());
        Assert.assertTrue(structuredOutput.getStages().stream().anyMatch(stage -> "search".equals(stage.getStage())));
        Assert.assertTrue(structuredOutput.getStages().stream().anyMatch(stage -> "chapter_summary".equals(stage.getStage())));
        Assert.assertFalse(llmObservation.containsKey("stages"));
        Assert.assertEquals("deep_search", llmObservation.getString("tool"));
        Assert.assertEquals("新能源车出口趋势", llmObservation.getString("query"));
        Assert.assertEquals(2, llmObservation.getJSONArray("subQueries").size());
        Assert.assertEquals(2, llmObservation.getJSONArray("results").size());
        Assert.assertEquals(1, llmObservation.getJSONArray("chapters").size());
        Assert.assertFalse(payload.getFailed());
        Assert.assertTrue(llmObservation.toJSONString().contains("海关总署：出口量创新高"));
        Assert.assertTrue(llmObservation.toJSONString().contains("https://example.com/customs"));
    }

    @Test
    public void shouldKeepFullAnswerAndTruncateSourceDocsInObservation() {
        DeepSearchStructuredResultBuilder builder = new DeepSearchStructuredResultBuilder("AI 芯片供应链");
        String fullAnswer = repeat("总结", 150);
        builder.recordEvent(DeepSearchResponse.builder()
                .messageType("extend")
                .searchResult(DeepSearchResponse.SearchResult.builder()
                        .query(List.of("AI 芯片供应链"))
                        .build())
                .build());
        builder.recordEvent(DeepSearchResponse.builder()
                .messageType("search")
                .searchResult(DeepSearchResponse.SearchResult.builder()
                        .query(List.of("AI 芯片供应链"))
                        .docs(List.of(List.of(
                                doc("文档1", "https://example.com/1", repeat("内容1", 120)),
                                doc("文档2", "https://example.com/2", repeat("内容2", 120)),
                                doc("文档3", "https://example.com/3", repeat("内容3", 120)),
                                doc("文档4", "https://example.com/4", repeat("内容4", 120))
                        )))
                        .build())
                .build());
        builder.recordFinalAnswer("AI 芯片供应链", fullAnswer);

        ToolResultPayload payload = builder.buildPayload("fallback");
        // 同 shouldSeparateStructuredOutputAndCompactObservation：断言 buildPayload 的 llmData 契约。
        JSONObject llmObservation = (JSONObject) JSON.toJSON(payload.getLlmData());
        JSONArray results = llmObservation.getJSONArray("results");
        JSONArray docs = results.getJSONObject(0).getJSONArray("docs");

        Assert.assertEquals(3, docs.size());
        Assert.assertTrue(docs.getJSONObject(0).getString("summary").length() <= 183);
        // 总结文章全量返回，不再截断
        Assert.assertEquals(fullAnswer, llmObservation.getString("answerSummary"));
    }

    @Test
    public void shouldFallbackToTextObservationWhenDeepSearchFails() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ledger = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        AgentContext context = ExecutionLedgerFixtureFactory.newAgentContext("req-deep-search-failure-001", "session-deep-search-001", ledger.recorder);
        ExecutionLedgerFixtureFactory.activateRun(context, ledger.recorder, ExecutionLedgerConstants.ENTRY_AGENT_REACT);
        ExecutionLedgerFixtureFactory.createLlmInvocation(
                context,
                ledger.recorder,
                "react",
                1,
                ExecutionLedgerConstants.CALL_KIND_ASK_TOOL
        );

        context.getToolCollection().addTool(new FailedDeepSearchTool());

        TestAgent agent = new TestAgent(context);
        agent.availableTools = context.getToolCollection();
        String observation = agent.executeTool(ExecutionLedgerFixtureFactory.newToolCall(
                "deep-search-call-001",
                "deep_search",
                "{\"query\":\"AI 芯片供应链\"}"
        ));

        ExecutionRunDetail detail = ledger.queryService.queryRunDetail(context.getRequestId());
        Assert.assertNotNull(detail);
        Assert.assertEquals(1, detail.getToolInvocations().size());
        ToolInvocationView invocation = detail.getToolInvocations().get(0);
        Assert.assertEquals("deep_search执行超时，已终止本次搜索，请基于当前已获取的信息继续处理。", observation);
        Assert.assertEquals(observation, readObservation(invocation));
        Assert.assertNull(invocation.getStructuredOutput());
    }

    private String readObservation(ToolInvocationView view) {
        try {
            Method method = view.getClass().getMethod("getLlmObservation");
            Object value = method.invoke(view);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            throw new IllegalStateException("读取 deep_search observation 失败", e);
        }
    }

    private DeepSearchResponse.SearchDoc doc(String title, String link, String content) {
        return DeepSearchResponse.SearchDoc.builder()
                .title(title)
                .link(link)
                .content(content)
                .build();
    }

    private String repeat(String part, int count) {
        StringBuilder builder = new StringBuilder();
        for (int idx = 0; idx < count; idx++) {
            builder.append(part);
        }
        return builder.toString();
    }

    private static final class TestAgent extends BaseAgent {
        private TestAgent(AgentContext context) {
            setContext(context);
        }

        @Override
        public String step() {
            return "";
        }
    }

    private static final class FailedDeepSearchTool implements BaseTool {

        @Override
        public String getName() {
            return "deep_search";
        }

        @Override
        public String getDescription() {
            return "失败 deep_search 测试桩";
        }

        @Override
        public Map<String, Object> toParams() {
            return Map.of();
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            return ToolResultPayload.text("deep_search执行超时，已终止本次搜索，请基于当前已获取的信息继续处理。");
        }
    }
}
