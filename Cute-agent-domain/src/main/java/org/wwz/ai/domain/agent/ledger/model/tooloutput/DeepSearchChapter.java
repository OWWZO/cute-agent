package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * deep_search 章节研究结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeepSearchChapter {

    private String chapterId;

    private String title;

    private String content;

    private Integer order;

    private List<String> queries = new ArrayList<>();

    private List<DeepSearchDoc> docs = new ArrayList<>();

    private String summary;

    private String status;

    /** 只读视图，避免调用方通过 getter 改动章节内部的查询列表。 */
    public List<String> getQueries() {
        return queries == null ? List.of() : Collections.unmodifiableList(queries);
    }

    /** 只读视图，避免调用方通过 getter 改动章节内部的文档列表。 */
    public List<DeepSearchDoc> getDocs() {
        return docs == null ? List.of() : Collections.unmodifiableList(docs);
    }

    public static DeepSearchChapter of(String chapterId,
                                       String title,
                                       String content,
                                       Integer order,
                                       List<String> queries,
                                       List<DeepSearchDoc> docs,
                                       String summary,
                                       String status) {
        return new DeepSearchChapter(
                chapterId,
                title,
                content,
                order,
                queries == null ? new ArrayList<>() : new ArrayList<>(queries),
                docs == null ? new ArrayList<>() : new ArrayList<>(docs),
                summary,
                status
        );
    }
}
