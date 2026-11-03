package org.wwz.ai.domain.agent.ledger.model.replay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 回放投影后的 eventData 视图。
 * <p>相等性只基于稳定的消息标识字段，可变 payload 不参与；payload 也不进入 toString，
 * 避免深/递归 map 产生巨量日志。</p>
 */
@Data
@EqualsAndHashCode(of = {"taskId", "messageId", "messageOrder"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectedReplayEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private Integer taskOrder;
    private String messageId;
    private String messageType;
    private Integer messageOrder;
    @ToString.Exclude
    private Map<String, Object> resultMap;
    @ToString.Exclude
    private List<Map<String, Object>> artifactRefs;
    @ToString.Exclude
    private ReplayTiming timing;

    /**
     * 产物引用以只读副本返回，避免下游在事件构建完成后改动回放载荷。
     */
    public List<Map<String, Object>> getArtifactRefs() {
        return artifactRefs == null ? null : Collections.unmodifiableList(new ArrayList<>(artifactRefs));
    }
}
