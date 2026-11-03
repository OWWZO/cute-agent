package org.wwz.ai.domain.agent.rag.model.query;

import lombok.Data;

/** Presentation metadata derived from a parsed query column. */
@Data
public class DataQueryColumn {
    private String col;
    private String agg;
    private String order;
    private String guid;
    private String name;
    /** schema 中的逻辑数据类型（如 {@code DECIMAL}）。 */
    private String dataType;
    /** SQL 解析得到的数据类型/列种类；星号列路径会复用 schema 的 dataType。 */
    private String colType;
}
