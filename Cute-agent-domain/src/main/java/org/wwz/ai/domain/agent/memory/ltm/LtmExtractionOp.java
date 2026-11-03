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
    /** add | replace | remove */
    private String action;
    /** user | curated */
    private String target;
    /** add/replace 必填；remove 可为空。 */
    private String content;
    /** 仅 replace/remove 有意义，add 时忽略；replace 时应能唯一定位旧内容。 */
    private String oldText;
}
