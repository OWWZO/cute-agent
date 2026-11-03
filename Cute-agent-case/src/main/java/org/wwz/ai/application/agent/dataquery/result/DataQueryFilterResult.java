package org.wwz.ai.application.agent.dataquery.result;

import java.util.List;

/**
 * 数据查询条件的展示与协议字段。
 * <p>{@code opt}/{@code optName} 分别是比较运算类型与展示名；{@code name} 是列展示名；
 * {@code operator} 是组合节点（AND / OR）的逻辑连接符，不是比较运算类型。</p>
 */
public record DataQueryFilterResult(
        /** 列名。 */
        String col,
        /** 比较运算类型，例如 {@code =}、{@code >}。 */
        String opt,
        /** 比较值。 */
        String val,
        /** 比较运算展示名。 */
        String optName,
        /** 列展示名。 */
        String name,
        /** 列数据类型。 */
        String dataType,
        /** 组合节点连接符，例如 {@code AND}、{@code OR}。 */
        String operator,
        /** 组合节点下的子条件。 */
        List<DataQueryFilterResult> subFilters) {
}
