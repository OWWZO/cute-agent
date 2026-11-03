package org.wwz.ai.infrastructure.dao.po;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * data_analysis 输出表 PO。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ToolOutputDataAnalysisPO extends AbstractToolOutputPO {

    private String task;

    @ToString.Exclude
    private String summary;

    @ToString.Exclude
    private String content;
}
