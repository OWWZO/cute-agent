package org.wwz.ai.domain.agent.rag.model.query;

import java.util.List;

/** Query context for recalling model schema columns. */
public record ColumnSchemaRecallQuery(
        String query,
        Integer limit,
        Float scoreThreshold,
        Long timeoutMillis,
        List<String> modelCodes) {
    public ColumnSchemaRecallQuery {
        // 与 modelCodes 对齐做 null 归一化，调用方无需再各自判空。
        query = query == null ? "" : query;
        modelCodes = modelCodes == null ? List.of() : List.copyOf(modelCodes);
    }
}
