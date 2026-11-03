package org.wwz.ai.domain.agent.rag;

import org.wwz.ai.domain.agent.adapter.port.AgentMessageStream;
import org.wwz.ai.domain.agent.rag.model.column.ColumnValueSearchRequest;
import org.wwz.ai.domain.agent.rag.model.query.ColumnSchemaRecallQuery;
import org.wwz.ai.domain.agent.rag.model.query.DataAgentChatQuery;
import org.wwz.ai.domain.agent.rag.model.query.DataQueryResult;
import org.wwz.ai.domain.agent.rag.model.query.Nl2SqlQuery;
import org.wwz.ai.domain.agent.rag.model.query.SqlExecutionResult;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryModelDescriptor;

import java.util.List;
import java.util.Map;

/** Data Query domain operations for schema recall, NL2SQL, and analytical query execution. */
public interface DataAgentQueryService {

    Nl2SqlQuery queryAllSchema();

    List<Map<String, Object>> recallSchemaColumns(ColumnSchemaRecallQuery query);

    List<Map<String, Object>> recallColumnValues(ColumnValueSearchRequest query);

    void chatQuery(DataAgentChatQuery query, AgentMessageStream stream) throws Exception;

    List<DataQueryResult> apiChatQuery(DataAgentChatQuery query);

    SqlExecutionResult testQuery(DataAgentChatQuery query);

    Nl2SqlQuery buildNl2SqlQuery(String query);

    List<DataQueryModelDescriptor> queryAllModelsWithSchema();

    SqlExecutionResult previewData(String modelCode);
}
