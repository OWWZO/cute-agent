package org.wwz.ai.domain.agent.runtime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchStage;

import java.util.List;

/**
 * DeepSearch 工具响应模型，承载搜索阶段结果、章节总结和最终摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeepSearchrResponse {

    /** {@link #messageType} 的允许取值，与回放阶段常量共用同一来源。 */
    public static final String MESSAGE_TYPE_EXTEND = DeepSearchStage.STAGE_EXTEND;
    public static final String MESSAGE_TYPE_SEARCH = DeepSearchStage.STAGE_SEARCH;
    public static final String MESSAGE_TYPE_CHAPTER_SUMMARY = DeepSearchStage.STAGE_CHAPTER_SUMMARY;
    public static final String MESSAGE_TYPE_REPORT = DeepSearchStage.STAGE_REPORT;

    private String requestId;
    private String query;
    private String answer;
    private SearchResult searchResult;
    private Boolean isFinal;
    private Boolean searchFinish; // 搜索结果是否结束
    /** 阶段类型，取值见 {@code MESSAGE_TYPE_*}。 */
    private String messageType;
    private String toolCallId;
    private String chapterId;
    private String chapterTitle;
    private String chapterContent;
    private Integer chapterOrder;
    private String chapterSummary;
    private Boolean chapterStreaming;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResult {
        private List<String> query;
        private List<List<SearchDoc>> docs;
        private List<SearchChapter> chapters;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchChapter {
        private String chapterId;
        private String chapterTitle;
        private String chapterContent;
        private Integer chapterOrder;
        private List<String> queries;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchDoc {
        private String doc_type;
        private String content;
        private String title;
        private String link;
    }
}
