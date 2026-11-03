package org.wwz.ai.domain.agent.rag.model.sql;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 问数过滤条件的比较运算类型。
 */
public enum ComparisonType {

    IN("IN", "包含","",""),
    NOT_IN("NOT IN", "不包含","",""),
    LESS_THAN("<", "小于","%s<%s",""),
    GREATER_THAN(">", "大于","%s>%s",""),
    LESS_THAN_OR_EQUAL("<=", "小于等于","%s<=%s",""),
    GREATER_THAN_OR_EQUAL(">=", "大于等于","%s>=%s",""),
    EQUALS("=", "等于","%s==%s",""),
    NOT_EQUALS("<>", "不等于","%s!=%s",""),
    LIKE("LIKE", "模糊匹配","str:contains(%s,%s)","like '%${value}%'"),
    NOT_LIKE("NOT LIKE", "模糊匹配","","not like '%${value}%'"),
    /** 后缀匹配（SQL token 仍是 LIKE '%value'）；刻意不叫 RLIKE，避免与 SQL 正则运算符混淆。 */
    SUFFIX_LIKE("RLIKE", "模糊左匹配","","like '%${value}'"),
    LLIKE("LLIKE", "模糊右匹配","","like '${value}%'"),
    IS_NULL("IS NULL", "为空","",""),
    IS_NOT_NULL("IS NOT NULL", "不为空","",""),
    BETWEEN("BETWEEN", "区间","","");


    public static final Set<ComparisonType> NONE_PARAM_TYPE =
            Collections.unmodifiableSet(EnumSet.of(IS_NULL, IS_NOT_NULL));

    public static final Set<ComparisonType> ONE_PARAM_TYPE =
            Collections.unmodifiableSet(EnumSet.of(LESS_THAN, GREATER_THAN, LESS_THAN_OR_EQUAL,
                    GREATER_THAN_OR_EQUAL, EQUALS, NOT_EQUALS, LIKE, SUFFIX_LIKE, LLIKE));

    public static final Set<ComparisonType> MORE_PARAM_TYPE =
            Collections.unmodifiableSet(EnumSet.of(IN, NOT_IN, BETWEEN));

    @Getter
    private final String comparison;

    @Getter
    private final String comparisonName;
    @Getter
    private final String relationalAlgebra;
    @Getter
    private final String relationalAlgebraSql;

    ComparisonType(String comparison, String comparisonName,String relationalAlgebra,String relationalAlgebraSql) {
        this.comparison = comparison;
        this.comparisonName = comparisonName;
        this.relationalAlgebra = relationalAlgebra;
        this.relationalAlgebraSql = relationalAlgebraSql;
    }


    /**
     * 按枚举名或 SQL 比较符（{@link #comparison}）解析，大小写不敏感。
     * <p>同时接受两者是因为解析链传进来的是 SQL token（如 "RLIKE"），
     * 而重命名后的常量名（如 {@code SUFFIX_LIKE}）不再与其一致。</p>
     */
    public static ComparisonType of(String value) {
        for (ComparisonType type : values()) {
            if (StringUtils.equalsIgnoreCase(value, type.name())
                    || StringUtils.equalsIgnoreCase(value, type.comparison)) {
                return type;
            }
        }

        throw new IllegalArgumentException("不支持的操作类型:" + value);
    }
}
