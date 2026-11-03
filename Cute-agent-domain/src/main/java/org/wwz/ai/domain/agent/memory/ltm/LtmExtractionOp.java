package org.wwz.ai.domain.agent.memory.ltm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构化记忆抽取操作（flush / background review 共用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LtmExtractionOp {

    public static final String ACTION_ADD = "add";
    public static final String ACTION_REPLACE = "replace";
    public static final String ACTION_REMOVE = "remove";
    public static final String TARGET_USER = "user";
    public static final String TARGET_CURATED = "curated";

    /** 取值见 {@code ACTION_*}。 */
    private String action;
    /** 取值见 {@code TARGET_*}。 */
    private String target;
    private String content;
    private String oldText;
}
