package org.wwz.ai.domain.agent.runtime.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 流事件的运行时聚合状态。
 * <p>负责 task/order/planner/result state，不负责 HTTP 包装或账本持久化。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamAccumulator {

    @Builder.Default
    private Map<String, Integer> orderMapping = new HashMap<>();

    private Boolean initPlan;
    private String taskId;

    @Builder.Default
    private AtomicInteger taskOrder = new AtomicInteger(1);

    @Builder.Default
    private List<String> streamTaskMessageType = new ArrayList<>(List.of(
            "html", "markdown", "knowledge", "deep_search", "tool_thought",
            "llm_reasoning", "tool_call", "tool_call_delta", "data_analysis"));

    @Builder.Default
    private Map<String, Object> resultMap = new HashMap<>();

    /**
     * 单次回放内的附属状态查询缓存（如 HITL 让步记录）。
     * <p>只在一次 projectHistory 生命周期内复用，不参与序列化/比较；
     * 键必须包含记录类型与 sessionId，避免同一回放内不同类型记录互相污染。</p>
     */
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final Map<String, Object> replayLookupCache = new HashMap<>();

    public static final String PLANNER_ROUND_ID_KEY = "plannerRoundId";

    public synchronized Integer getAndIncrOrder(String key) {
        Integer order = orderMapping.get(key);
        if (Objects.isNull(order)) {
            orderMapping.put(key, 1);
            return 1;
        }
        orderMapping.put(key, order + 1);
        return order + 1;
    }

    /**
     * 标记计划已初始化。
     *
     * @return 首次调用返回 true，之后返回 false
     */
    public synchronized boolean markPlanInitialized() {
        if (Objects.isNull(initPlan) || Boolean.FALSE.equals(initPlan)) {
            initPlan = true;
            return true;
        }
        return false;
    }

    public synchronized String getTaskId() {
        if (taskId == null || taskId.isEmpty()) {
            taskId = UUID.randomUUID().toString();
        }
        return taskId;
    }

    public synchronized String renewTaskId() {
        taskOrder.set(1);
        taskId = UUID.randomUUID().toString();
        return taskId;
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Object> getResultMapTask() {
        Object tasks = resultMap.get("tasks");
        return tasks instanceof List<?> ? (List<Object>) tasks : null;
    }

    public synchronized void setResultMapTask(List<Object> task) {
        List<Object> tasks = getResultMapTask();
        if (tasks == null) {
            tasks = new ArrayList<>();
            resultMap.put("tasks", tasks);
        }
        tasks.add(task);
    }

    @SuppressWarnings("unchecked")
    public synchronized void setResultMapSubTask(Object subTask) {
        List<Object> tasks = getResultMapTask();
        if (tasks == null) {
            tasks = new ArrayList<>();
            resultMap.put("tasks", tasks);
        }
        if (tasks.isEmpty()) {
            tasks.add(new ArrayList<>());
        }
        Object last = tasks.get(tasks.size() - 1);
        if (last instanceof List<?> existing) {
            try {
                ((List<Object>) existing).add(subTask);
                return;
            } catch (UnsupportedOperationException ignored) {
                // 外部传入不可变列表时替换成可变副本，不能把异常抛给流式链路。
            }
            List<Object> mutable = new ArrayList<>(existing);
            mutable.add(subTask);
            tasks.set(tasks.size() - 1, mutable);
            return;
        }
        // 最后一个 task 不是列表（例如 setResultMapTask 放了非列表载荷）时重建子任务容器。
        List<Object> subTasks = new ArrayList<>();
        subTasks.add(subTask);
        tasks.set(tasks.size() - 1, subTasks);
    }

    public synchronized String getPlannerRoundId() {
        Object plannerRoundId = resultMap.get(PLANNER_ROUND_ID_KEY);
        return plannerRoundId == null ? null : String.valueOf(plannerRoundId);
    }

    public synchronized void setPlannerRoundId(String plannerRoundId) {
        if (plannerRoundId == null || plannerRoundId.isBlank()) {
            resultMap.remove(PLANNER_ROUND_ID_KEY);
            return;
        }
        resultMap.put(PLANNER_ROUND_ID_KEY, plannerRoundId);
    }

    public synchronized Object getReplayLookup(String key) {
        return replayLookupCache.get(key);
    }

    public synchronized void putReplayLookup(String key, Object value) {
        replayLookupCache.put(key, value);
    }
}
