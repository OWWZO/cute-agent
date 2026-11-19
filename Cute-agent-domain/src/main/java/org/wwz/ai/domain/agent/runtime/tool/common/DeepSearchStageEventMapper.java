package org.wwz.ai.domain.agent.runtime.tool.common;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchChapter;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchDoc;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchQueryResult;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.DeepSearchStage;
import org.wwz.ai.domain.agent.runtime.dto.DeepSearchResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSearch 阶段事实到实时响应/历史结果的唯一转换入口。
 */
public final class DeepSearchStageEventMapper {

    private DeepSearchStageEventMapper() {
    }

    public static DeepSearchResponse toResponse(String query,
                                                List<DeepSearchChapter> chapters,
                                                DeepSearchStage stage) {
        if (stage == null || StringUtils.isBlank(stage.getStage())) {
            return null;
        }
        DeepSearchResponse.DeepSearchResponseBuilder builder = DeepSearchResponse.builder()
                .query(query)
                .messageType(stage.getStage())
                .isFinal(effectiveIsFinal(stage))
                .searchFinish(effectiveSearchFinish(stage))
                .chapterStreaming(stage.getChapterStreaming());
        switch (stage.getStage()) {
            case DeepSearchStage.STAGE_EXTEND -> builder.searchResult(
                    DeepSearchResponse.SearchResult.builder()
                            .query(nonNullList(stage.getQueries()))
                            .docs(List.of())
                            .chapters(toSearchChapters(chapters))
                            .build());
            case DeepSearchStage.STAGE_SEARCH -> builder.searchResult(
                    buildSearchResult(List.of(), stage.getResults(), chapters));
            case DeepSearchStage.STAGE_CHAPTER_SUMMARY -> {
                builder.chapterId(StringUtils.defaultString(stage.getChapterId()))
                        .chapterTitle(StringUtils.defaultString(stage.getChapterTitle()))
                        .chapterContent(StringUtils.defaultString(
                                stage.getChapterContent(), stage.getChapterTitle()))
                        .chapterOrder(stage.getChapterOrder())
                        .chapterSummary(StringUtils.defaultString(stage.getChapterSummary()))
                        .answer(StringUtils.defaultString(stage.getChapterSummary()))
                        .searchResult(buildSearchResult(stage.getQueries(), stage.getResults(), List.of()));
            }
            case DeepSearchStage.STAGE_REPORT ->
                    builder.answer(StringUtils.defaultString(stage.getAnswer()));
            default -> {
                return null;
            }
        }
        return builder.build();
    }

    public static Map<String, Object> toResultMap(DeepSearchResponse response) {
        if (response == null || StringUtils.isBlank(response.getMessageType())) {
            return Map.of();
        }
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("messageType", response.getMessageType());
        resultMap.put("isFinal", Boolean.TRUE.equals(response.getIsFinal()));
        resultMap.put("searchFinish", Boolean.TRUE.equals(response.getSearchFinish()));
        resultMap.put("query", response.getQuery());
        if (response.getSearchResult() != null) {
            resultMap.put("searchResult", toSearchResultMap(response.getSearchResult()));
        }
        if (response.getChapterId() != null) {
            resultMap.put("chapterId", response.getChapterId());
        }
        if (response.getChapterTitle() != null) {
            resultMap.put("chapterTitle", response.getChapterTitle());
        }
        if (response.getChapterContent() != null) {
            resultMap.put("chapterContent", response.getChapterContent());
        }
        if (response.getChapterOrder() != null) {
            resultMap.put("chapterOrder", response.getChapterOrder());
        }
        if (response.getChapterSummary() != null) {
            resultMap.put("chapterSummary", response.getChapterSummary());
        }
        if (response.getAnswer() != null) {
            resultMap.put("answer", response.getAnswer());
        }
        if (response.getChapterStreaming() != null) {
            resultMap.put("chapterStreaming", response.getChapterStreaming());
        }
        if (response.getFileInfo() != null && !response.getFileInfo().isEmpty()) {
            resultMap.put("fileInfo", response.getFileInfo());
        }
        return resultMap;
    }

    private static DeepSearchResponse.SearchResult buildSearchResult(
            List<String> seedQueries,
            List<DeepSearchQueryResult> results,
            List<DeepSearchChapter> chapters) {
        Map<String, List<DeepSearchResponse.SearchDoc>> buckets = new LinkedHashMap<>();
        for (String query : nonNullList(seedQueries)) {
            buckets.computeIfAbsent(StringUtils.defaultString(query), ignored -> new ArrayList<>());
        }
        for (DeepSearchQueryResult result : nonNullList(results)) {
            if (result == null) {
                continue;
            }
            String query = StringUtils.defaultString(result.getQuery());
            buckets.computeIfAbsent(query, ignored -> new ArrayList<>())
                    .addAll(toSearchDocs(result.getDocs()));
        }
        List<String> queries = new ArrayList<>(buckets.keySet());
        List<List<DeepSearchResponse.SearchDoc>> docs = new ArrayList<>(buckets.values());
        return DeepSearchResponse.SearchResult.builder()
                .query(queries)
                .docs(docs)
                .chapters(toSearchChapters(chapters))
                .build();
    }

    private static List<DeepSearchResponse.SearchChapter> toSearchChapters(
            List<DeepSearchChapter> chapters) {
        List<DeepSearchResponse.SearchChapter> result = new ArrayList<>();
        for (DeepSearchChapter chapter : nonNullList(chapters)) {
            if (chapter == null) {
                continue;
            }
            result.add(DeepSearchResponse.SearchChapter.builder()
                    .chapterId(chapter.getChapterId())
                    .chapterTitle(chapter.getTitle())
                    .chapterContent(chapter.getContent())
                    .chapterOrder(chapter.getOrder())
                    .queries(new ArrayList<>(chapter.getQueries()))
                    .build());
        }
        return result;
    }

    private static List<DeepSearchResponse.SearchDoc> toSearchDocs(List<DeepSearchDoc> docs) {
        List<DeepSearchResponse.SearchDoc> result = new ArrayList<>();
        for (DeepSearchDoc doc : nonNullList(docs)) {
            if (doc == null) {
                continue;
            }
            result.add(DeepSearchResponse.SearchDoc.builder()
                    .docType(doc.getDocType())
                    .title(StringUtils.defaultString(doc.getTitle()))
                    .link(StringUtils.defaultString(doc.getLink()))
                    .content(StringUtils.defaultString(doc.getSummary()))
                    .build());
        }
        return result;
    }

    private static Map<String, Object> toSearchResultMap(
            DeepSearchResponse.SearchResult searchResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", nonNullList(searchResult.getQuery()));
        List<List<Map<String, Object>>> docs = new ArrayList<>();
        for (List<DeepSearchResponse.SearchDoc> docList : nonNullList(searchResult.getDocs())) {
            List<Map<String, Object>> mappedDocs = new ArrayList<>();
            for (DeepSearchResponse.SearchDoc doc : nonNullList(docList)) {
                if (doc == null) {
                    continue;
                }
                Map<String, Object> mappedDoc = new LinkedHashMap<>();
                mappedDoc.put("title", StringUtils.defaultString(doc.getTitle()));
                mappedDoc.put("link", StringUtils.defaultString(doc.getLink()));
                mappedDoc.put("content", StringUtils.defaultString(doc.getContent()));
                if (doc.getDocType() != null) {
                    mappedDoc.put("doc_type", doc.getDocType());
                }
                mappedDocs.add(mappedDoc);
            }
            docs.add(mappedDocs);
        }
        result.put("docs", docs);
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (DeepSearchResponse.SearchChapter chapter : nonNullList(searchResult.getChapters())) {
            if (chapter == null) {
                continue;
            }
            Map<String, Object> mappedChapter = new LinkedHashMap<>();
            mappedChapter.put("chapterId", chapter.getChapterId());
            mappedChapter.put("chapterTitle", chapter.getChapterTitle());
            mappedChapter.put("chapterContent", chapter.getChapterContent());
            mappedChapter.put("chapterOrder", chapter.getChapterOrder());
            mappedChapter.put("queries", nonNullList(chapter.getQueries()));
            chapters.add(mappedChapter);
        }
        result.put("chapters", chapters);
        return result;
    }

    private static boolean effectiveIsFinal(DeepSearchStage stage) {
        return stage.getIsFinal() != null
                ? stage.getIsFinal()
                : DeepSearchStage.STAGE_REPORT.equals(stage.getStage());
    }

    private static boolean effectiveSearchFinish(DeepSearchStage stage) {
        if (stage.getSearchFinish() != null) {
            return stage.getSearchFinish();
        }
        return !DeepSearchStage.STAGE_EXTEND.equals(stage.getStage());
    }

    private static <T> List<T> nonNullList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
