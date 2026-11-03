package org.wwz.ai.domain.agent.rag.model.query;

import lombok.Data;

/** Presentation metadata derived from a parsed query column. */
@Data
public class DataQueryColumn {
    private String col;
    /** 聚合函数名（如 SUM / COUNT / AVG），来自解析出的 SQL 函数名；非聚合列为空。 */
    private String agg;
    /** 排序方向，取 {@code OrderByType} 的枚举名（ASC / DESC）；无排序时为空。 */
    private String order;
    private String guid;
    private String name;
    /** schema 中的逻辑数据类型（如 {@code DECIMAL}）。 */
    private String dataType;
    /** SQL 解析得到的数据类型/列种类；星号列路径会复用 schema 的 dataType。 */
    private String colType;
}
