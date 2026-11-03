package org.wwz.ai.domain.agent.rag.sql;

import com.clickhouse.data.ClickHouseAggregateFunction;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.commons.collections4.CollectionUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Calcite SQL 聚合函数访问器，用于判断查询是否包含聚合表达式。
 */
public class HasAggVisitor extends SqlBasicVisitor<Boolean> {

    public static final String COUNT_DISTINCT = "count_distinct";

    private boolean isAgg = false;

    /** 只读聚合函数名集合，避免调用方修改后影响全局判定。 */
    private static final Set<String> AGG_NAME_SET;

    static {
        Set<String> names = new HashSet<>();
        for (SqlKind sqlKind : SqlKind.AGGREGATE) {
            names.add(sqlKind.name().toLowerCase());
        }
        for (ClickHouseAggregateFunction ckAgg : ClickHouseAggregateFunction.class.getEnumConstants()) {
            names.add(ckAgg.name().toLowerCase());
            if(CollectionUtils.isNotEmpty(ckAgg.getAliases())){
                names.addAll(ckAgg.getAliases());
            }
        }
        names.add(COUNT_DISTINCT);
        names.add("stddev");
        names.add("percentile");
        names.add("percentile_approx");
        AGG_NAME_SET = Collections.unmodifiableSet(names);
    }

    public boolean isAgg() {
        return isAgg;
    }

    @Override
    public Boolean visit(SqlCall call) {
        if (AGG_NAME_SET.contains(call.getOperator().getName().toLowerCase())) {
            isAgg = true;
        }
        return super.visit(call);
    }
}
