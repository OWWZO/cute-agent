package org.wwz.ai.domain.agent.runtime.tool.common;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchChapter;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchDoc;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchQueryResult;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchStage;
import org.wwz.ai.domain.agent.runtime.dto.DeepSearchResponse;

import java.util.List;
import java.util.Map;

public class DeepSearchStageEventMapperTest {

    @Test
    public void chapterStageCarriesStreamingAndProcessStateIntoResponseAndMap() {
        DeepSearchStage stage = DeepSearchStage.chapterSummary(
                "C1",
                "章节一",
                "正文",
                1,
                "章节总结",
                List.of("query-1"),
                List.of(DeepSearchQueryResult.of(
                        "query-1",
                        List.of(DeepSearchDoc.of("标题", "https://example.com", "摘要", "web")))
                )
        );
        stage.setChapterStreaming(true);
        DeepSearchChapter chapter = DeepSearchChapter.of(
                "C1", "章节一", "正文", 1, List.of("query-1"),
                List.of(), "章节总结", "completed");

        DeepSearchResponse response = DeepSearchStageEventMapper.toResponse(
                "总问题", List.of(chapter), stage);
        Map<String, Object> resultMap = DeepSearchStageEventMapper.toResultMap(response);

        Assert.assertFalse(response.getIsFinal());
        Assert.assertTrue(response.getSearchFinish());
        Assert.assertTrue(response.getChapterStreaming());
        Assert.assertEquals(Boolean.FALSE, resultMap.get("isFinal"));
        Assert.assertEquals(Boolean.TRUE, resultMap.get("chapterStreaming"));
        Assert.assertEquals("章节总结", resultMap.get("answer"));
    }

    @Test
    public void reportStageIsTheOnlyFinalStage() {
        DeepSearchResponse response = DeepSearchStageEventMapper.toResponse(
                "总问题", List.of(), DeepSearchStage.report("完整报告"));

        Assert.assertTrue(response.getIsFinal());
        Assert.assertTrue(response.getSearchFinish());
        Assert.assertEquals("完整报告", response.getAnswer());
    }

    @Test
    public void reportFileInfoUsesTheSameResultMapShapeAsHistory() {
        DeepSearchResponse response = DeepSearchStageEventMapper.toResponse(
                "总问题", List.of(), DeepSearchStage.report("完整报告"));
        response.setFileInfo(List.of(Map.of(
                "fileName", "研究报告.md",
                "ossUrl", "https://files.example.com/report.md",
                "domainUrl", "https://preview.example.com/report.md"
        )));

        Map<String, Object> resultMap = DeepSearchStageEventMapper.toResultMap(response);

        Assert.assertTrue(resultMap.get("fileInfo") instanceof List<?>);
        Assert.assertEquals(
                "研究报告.md",
                ((Map<?, ?>) ((List<?>) resultMap.get("fileInfo")).get(0)).get("fileName")
        );
    }
}
