package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * deep_search 单个查询结果。
 */
@Data
public class DeepSearchQueryResult {

    private String query;

    private List<DeepSearchDoc> docs = new ArrayList<>();

    public DeepSearchQueryResult() {
    }

    public DeepSearchQueryResult(String query, List<DeepSearchDoc> docs) {
        this.query = query;
        setDocs(docs);
    }

    /**
     * 统一工厂，避免运行时依赖 Lombok Builder 内部类。
     */
    public static DeepSearchQueryResult of(String query, List<DeepSearchDoc> docs) {
        return new DeepSearchQueryResult(query, docs == null ? new ArrayList<>() : new ArrayList<>(docs));
    }

    /**
     * 文档列表以只读副本返回，避免调用方直接改动结果对象。
     */
    public List<DeepSearchDoc> getDocs() {
        return docs == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(docs));
    }

    public void setDocs(List<DeepSearchDoc> docs) {
        this.docs = docs == null ? new ArrayList<>() : new ArrayList<>(docs);
    }
}
