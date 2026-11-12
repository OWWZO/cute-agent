package org.wwz.ai.domain.agent.rag.model.sql;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.JoinType;

import java.util.List;

@NoArgsConstructor
@Data
/**
 * SQL 查询 FROM 表描述，承载表名、别名及来源类型。
 */
public class FromTable {
    /**
     * from表名
     */
    private String tableName;
    /**
     * from表as别名
     */
    private String tableAlias;
    /**
     * 关联类型
     *
     * @see org.apache.calcite.sql.JoinType
     */
    private JoinType joinType;
    /**
     * 条件类型
     *
     * @see JoinConditionType
     */
    private JoinConditionType joinConditionType;
    /**
     * 关联条件内容
     */
    private String condition;
    /**
     * 关联表。
     * <p>自引用字段必须排除在生成的 toString/equals/hashCode 之外，否则深 JOIN 链
     * 或意外环会在日志/比较时递归溢出；因此相等性刻意忽略整条 rightTable 链，
     * 只由本层字段描述。</p>
     */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private FromTable rightTable;

    private FromTableType fromTableType;
}
