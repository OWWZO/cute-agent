package org.wwz.ai.domain.agent.rag.model.column;

import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

/**
 * 列值召回结果。
 */
@EqualsAndHashCode
@ToString
public final class ColumnValueSearchResult {

    private final List<ColumnValueHit> hits;

    public ColumnValueSearchResult(List<ColumnValueHit> hits) {
        this.hits = List.copyOf(hits == null ? List.of() : hits);
    }

    public static ColumnValueSearchResult empty() {
        return new ColumnValueSearchResult(List.of());
    }

    public List<ColumnValueHit> getHits() {
        return hits;
    }
}
