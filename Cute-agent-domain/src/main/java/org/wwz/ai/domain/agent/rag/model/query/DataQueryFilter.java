package org.wwz.ai.domain.agent.rag.model.query;

import lombok.Data;

import java.util.List;

/** Presentation metadata for a parsed query condition. */
@Data
public class DataQueryFilter {
    /** 列名。 */
    private String col;
    /** 比较运算类型（如 "="、">"），不是逻辑连接符。 */
    private String opt;
    private String val;
    /** 比较运算的展示名。 */
    private String optName;
    /** 列的展示名。 */
    private String name;
    private String dataType;
    /** 逻辑连接符（AND / OR），只出现在组合节点上，用于连接 {@link #subFilters}。 */
    private String operator;
    /** 组合节点下的子条件。 */
    private List<DataQueryFilter> subFilters;
}
