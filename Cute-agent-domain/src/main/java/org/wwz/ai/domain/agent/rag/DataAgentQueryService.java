package org.wwz.ai.domain.agent.rag;

import org.wwz.ai.domain.agent.adapter.port.AgentMessageStream;
import org.wwz.ai.domain.agent.rag.model.column.ColumnValueSearchRequest;
import org.wwz.ai.domain.agent.rag.model.query.ColumnSchemaRecallQuery;
import org.wwz.ai.domain.agent.rag.model.query.DataAgentChatQuery;
import org.wwz.ai.domain.agent.rag.model.query.DataQueryResult;
import org.wwz.ai.domain.agent.rag.model.query.Nl2SqlQuery;
import org.wwz.ai.domain.agent.rag.model.query.SqlExecutionResult;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryModelDescriptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Data Query domain operations for schema recall, NL2SQL, and analytical query execution. */
public interface DataAgentQueryService {

    /** 构造一个挂载全部模型 schema 的基础查询上下文（不是返回 schema 列表）。 */
    Nl2SqlQuery buildQueryWithAllSchema();

    List<Map<String, Object>> recallSchemaColumns(ColumnSchemaRecallQuery query);

    List<Map<String, Object>> recallColumnValues(ColumnValueSearchRequest query) throws IOException;

    void chatQuery(DataAgentChatQuery query, AgentMessageStream stream) throws Exception;

    /**
     * 同步取数。实现吞掉执行异常并返回空列表以便 HTTP 调用方降级，
     * 失败信息仅记录在服务端日志；调用方无法从返回值区分「无数据」与「查询失败」。
     */
    List<DataQueryResult> apiChatQuery(DataAgentChatQuery query);

    SqlExecutionResult testQuery(DataAgentChatQuery query);

    Nl2SqlQuery buildNl2SqlQuery(String query) throws Exception;

    List<DataQueryModelDescriptor> queryAllModelsWithSchema();

    SqlExecutionResult previewData(String modelCode) throws Exception;
}
