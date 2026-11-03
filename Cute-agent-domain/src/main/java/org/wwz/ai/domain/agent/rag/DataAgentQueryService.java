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

    /** 构造一个挂载全部模型 schema 的基础查询上下文（不是返回 schema 列表）。 */
    Nl2SqlQuery buildQueryWithAllSchema();

    /**
     * 向量召回模型字段。
     * <p>载荷结构由向量库 payload 决定，无法在编译期收窄，稳定键只有两个：
     * {@code score}（Float，召回得分）与 {@code _id}（String，向量点 ID）；
     * 其余键为 Qdrant payload 原样透传（当前适配器统一转为 String 值）。
     * 失败时返回空列表，不抛异常。</p>
     */
    List<Map<String, Object>> recallSchemaColumns(ColumnSchemaRecallQuery query);

    /**
     * ES 召回列值样例。
     * <p>载荷结构由索引 {@code _source} 决定，无法在编译期收窄，稳定键只有
     * {@code score}（Float，相关度；上游未返回时该键缺失）；
     * 其余键为 {@code _source} 字段原样透传，保持 ES 返回的原始类型。
     * 失败时返回空列表，不抛异常。</p>
     */
    List<Map<String, Object>> recallColumnValues(ColumnValueSearchRequest query);

    void chatQuery(DataAgentChatQuery query, AgentMessageStream stream) throws Exception;

    /**
     * 同步取数。实现吞掉执行异常并返回空列表以便 HTTP 调用方降级，
     * 失败信息仅记录在服务端日志；调用方无法从返回值区分「无数据」与「查询失败」。
     */
    List<DataQueryResult> apiChatQuery(DataAgentChatQuery query);

    SqlExecutionResult testQuery(DataAgentChatQuery query);

    Nl2SqlQuery buildNl2SqlQuery(String query);

    List<DataQueryModelDescriptor> queryAllModelsWithSchema();

    SqlExecutionResult previewData(String modelCode);
}
