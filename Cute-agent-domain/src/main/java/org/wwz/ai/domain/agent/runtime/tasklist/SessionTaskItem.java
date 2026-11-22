package org.wwz.ai.domain.agent.runtime.tasklist;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话 Todo 任务项。
 * 与后台运行任务（TaskStop）是不同概念。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTaskItem {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";

    private String id;
    private String subject;
    private String description;
    /** spinner 进行时文案 */
    private String activeForm;
    private String status;

    /**
     * 生成展示用快照。
     */
    public Map<String, Object> toDetailMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("subject", subject);
        map.put("status", status);
        map.put("description", description);
        map.put("activeForm", activeForm);
        return map;
    }
}
