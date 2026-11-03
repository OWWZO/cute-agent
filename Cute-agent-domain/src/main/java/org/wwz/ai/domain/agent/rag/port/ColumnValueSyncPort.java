package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelInfo;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;

import java.util.List;

/**
 * Column-value synchronization capability. SQL and index protocol details stay in Infrastructure.
 */
public interface ColumnValueSyncPort {

    /**
     * Rebuilds the column-value documents for the supplied model schemas.
     * <p>A null {@code modelInfo} or null/empty {@code schemas} is a no-op; an empty list never means
     * "clear all values". Failures are not swallowed: the current implementation propagates them as
     * unchecked exceptions ({@link IllegalStateException} for query/index failures,
     * {@link IllegalArgumentException} for unsupported model types), so the index may be partially
     * updated after a failure.</p>
     *
     * @param modelInfo model metadata used to resolve the source table/SQL; null is a no-op
     * @param schemas   columns to sync; null/empty is a no-op
     */
    void syncColumnValues(ChatModelInfo modelInfo, List<ChatModelSchema> schemas);
}
