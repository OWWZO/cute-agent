package org.wwz.ai.domain.agent.rag.model.sql;

import lombok.Data;
import org.wwz.ai.domain.agent.rag.model.schema.StandardColumnType;

import java.util.List;

/**
 * 问数模型字段描述，连接自然语言列语义与物理列信息。
 */
@Data
public class ModelColumn {

    private String columnKey;

    private String columnName;

    private String columnAlias;

    private String comment;

    /** 归一化后的标准列类型，用于 schema 输出与数值类型判断。 */
    private StandardColumnType dataType;

    private Integer position;

    /**
     * 小数位
     */
    private Integer decimalPlaces;

    private String dateFormat;

    /** 解析期列形态：Calcite {@code SqlKind} 名称（如 IDENTIFIER / LITERAL），供 Nl2SqlQueryService 判类型。 */
    private String columnKind;

    private boolean aggregator;

    private boolean simpleIdentify;

    private List<String> identifyNameList;

    private String functionName;
    private List<String> functionArgList;

    private boolean star;

    private String tableAlias;

    private String tableName;
     /**
      * 计算字段属性(0非计算字段，1计算字段，2分组字段)
      */
     private Integer calculateMember;
     /**
      * 计算列json配置
      */
     private String calculateExpression;
}
