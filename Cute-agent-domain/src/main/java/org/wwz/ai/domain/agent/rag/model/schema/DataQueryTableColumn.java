package org.wwz.ai.domain.agent.rag.model.schema;

import lombok.Builder;
import lombok.Data;

/** A column in a configured analytical model. */
@Data
@Builder
public class DataQueryTableColumn {
    private String name;
    private String dataType;
    private String originDataType;
    private Integer columnLength;
    private Boolean nullable;
    /** 运行期类型由 {@code dataType}/{@code originDataType} 决定，消费方不得假定具体 Java 类型。 */
    private Object defaultValue;
    private String comment;
    private Integer position;
}
