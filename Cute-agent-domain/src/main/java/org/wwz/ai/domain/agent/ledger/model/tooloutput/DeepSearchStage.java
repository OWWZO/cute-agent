package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * deep_search 单阶段快照。
 */
@Data
@NoArgsConstructor
public class DeepSearchStage {

    public static final String STAGE_EXTEND = "extend";
    public static final String STAGE_SEARCH = "search";
    public static final String STAGE_CHAPTER_SUMMARY = "chapter_summary";
    public static final String STAGE_REPORT = "report";

    /** 阶段判别值，取值见 {@code STAGE_*}；未登记的值会被回放投影忽略。 */
    private String stage;

    private List<String> queries = new ArrayList<>();

    private List<DeepSearchQueryResult> results = new ArrayList<>();

    private String answer;

    private String chapterId;

    private String chapterTitle;

    private String chapterContent;

    private Integer chapterOrder;

    private String chapterSummary;

    /** 该阶段是否为最终事件；历史旧数据为空时由映射器按阶段类型推断。 */
    private Boolean isFinal;

    /** 搜索结果是否已收口，与实时 DeepSearchResponse 使用同一字段语义。 */
    private Boolean searchFinish;

    /** 章节总结仍在流式生成时为 true。 */
    private Boolean chapterStreaming;

    /**
     * extend 阶段工厂。
     */
    public static DeepSearchStage extend(List<String> queries) {
        DeepSearchStage stage = new DeepSearchStage();
        stage.setStage(STAGE_EXTEND);
        stage.setQueries(queries == null ? new ArrayList<>() : new ArrayList<>(queries));
        stage.setIsFinal(false);
        stage.setSearchFinish(false);
        return stage;
    }

    /**
     * search 阶段工厂。
     */
    public static DeepSearchStage search(List<DeepSearchQueryResult> results) {
        DeepSearchStage stage = new DeepSearchStage();
        stage.setStage(STAGE_SEARCH);
        stage.setResults(results == null ? new ArrayList<>() : new ArrayList<>(results));
        stage.setIsFinal(false);
        stage.setSearchFinish(true);
        return stage;
    }

    /**
     * chapter_summary 阶段工厂。
     */
    public static DeepSearchStage chapterSummary(String chapterId,
                                                  String chapterTitle,
                                                  String chapterContent,
                                                  Integer chapterOrder,
                                                 String chapterSummary,
                                                 List<String> queries,
                                                 List<DeepSearchQueryResult> results) {
        DeepSearchStage stage = new DeepSearchStage();
        stage.setStage(STAGE_CHAPTER_SUMMARY);
        stage.setChapterId(chapterId);
        stage.setChapterTitle(chapterTitle);
        stage.setChapterContent(chapterContent);
        stage.setChapterOrder(chapterOrder);
        stage.setChapterSummary(chapterSummary);
        stage.setQueries(queries == null ? new ArrayList<>() : new ArrayList<>(queries));
        stage.setResults(results == null ? new ArrayList<>() : new ArrayList<>(results));
        stage.setIsFinal(false);
        stage.setSearchFinish(true);
        return stage;
    }

    /**
     * report 阶段工厂。
     */
    public static DeepSearchStage report(String answer) {
        DeepSearchStage stage = new DeepSearchStage();
        stage.setStage(STAGE_REPORT);
        stage.setAnswer(answer);
        stage.setIsFinal(true);
        stage.setSearchFinish(true);
        return stage;
    }
}
