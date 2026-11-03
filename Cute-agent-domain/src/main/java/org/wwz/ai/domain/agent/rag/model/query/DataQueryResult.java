package org.wwz.ai.domain.agent.rag.model.query;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/**
 * Structured answer for one NL2SQL-generated query.
 * <p>结果集字段排除出 toString/equals：避免误日志把整表数据（可能含 PII）打出来，
 * 也避免 equals/hashCode 随行数线性变慢。</p>
 */
@Data
@ToString(exclude = {"dataList", "querySqlList", "nl2sqlResult"})
@EqualsAndHashCode(exclude = {"dataList", "querySqlList", "nl2sqlResult"})
public class DataQueryResult {
    private String question;
    private List<Map<String, Object>> dataList;
    private List<DataQueryColumn> columnList;
    private List<DataQueryFilter> filters;
    private String modelCode;
    private String modelName;
    private Boolean loadSucceed;
    private String errorMessage;
    private List<String> querySqlList;
    private int limit;
    private List<String> dimCols;
    private List<String> measureCols;
    private String nl2sqlResult;
    private String id;
}
