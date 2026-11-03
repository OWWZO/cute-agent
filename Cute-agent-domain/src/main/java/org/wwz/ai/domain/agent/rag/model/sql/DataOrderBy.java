package org.wwz.ai.domain.agent.rag.model.sql;

import lombok.Data;

/**
 * 查询结果排序描述，记录排序列和升降序方向。
 */
@Data
public class DataOrderBy {
    private String columnName;
    private OrderByType orderType;
}
