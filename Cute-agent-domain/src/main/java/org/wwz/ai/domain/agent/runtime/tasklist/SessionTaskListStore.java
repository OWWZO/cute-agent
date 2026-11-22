package org.wwz.ai.domain.agent.runtime.tasklist;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级 Todo 任务列表。
 * 可选挂载 {@link TasklistPersistencePort} 实现跨 run 持久化。
 */
public class SessionTaskListStore {

    private final String listId;
    private final Map<String, SessionTaskItem> tasks = new ConcurrentHashMap<>();
    private final TasklistPersistencePort persistence;

    public SessionTaskListStore(String listId) {
        this(listId, null);
    }

    public SessionTaskListStore(String listId, TasklistPersistencePort persistence) {
        this.listId = StringUtils.defaultIfBlank(listId, "default");
        this.persistence = persistence;
        hydrate();
    }

    public String getListId() {
        return listId;
    }

    private void hydrate() {
        if (persistence == null) {
            return;
        }
        try {
            List<SessionTaskItem> loaded = persistence.loadTodos(listId);
            if (loaded != null) {
                for (SessionTaskItem item : loaded) {
                    if (item == null || StringUtils.isBlank(item.getId())) {
                        continue;
                    }
                    tasks.put(item.getId(), item);
                }
            }
        } catch (Exception ignored) {
            // best-effort：hydrate 失败保持空列表
        }
    }

    public List<SessionTaskItem> list() {
        List<SessionTaskItem> items = new ArrayList<>(tasks.values());
        items.sort(Comparator.comparingInt(item -> {
            try {
                return Integer.parseInt(item.getId());
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }));
        return items;
    }

    public synchronized List<SessionTaskItem> replaceAll(List<Map<String, Object>> todoMaps) {
        List<SessionTaskItem> oldSnapshot = list();
        List<Map<String, Object>> rawItems = todoMaps == null ? List.of() : todoMaps;

        // 先校验并构造完整的新列表，任何非法 status 都在清空旧状态之前抛出；
        // 否则一次被拒绝的写入会先清空内存列表，而持久化列表仍保留旧值。
        List<SessionTaskItem> newItems = new ArrayList<>();
        int nextId = 0;
        for (Map<String, Object> raw : rawItems) {
            if (raw == null) {
                continue;
            }
            String status = normalizeStatus(String.valueOf(
                    firstNonNull(raw.get("status"), SessionTaskItem.STATUS_PENDING)));
            String content = firstNonBlank(
                    str(raw.get("content")),
                    str(raw.get("subject")),
                    str(raw.get("description")));
            if (StringUtils.isBlank(content)) {
                continue;
            }
            String activeForm = StringUtils.trimToNull(str(raw.get("activeForm")));
            String description = firstNonBlank(str(raw.get("description")), content);
            String subject = firstNonBlank(str(raw.get("subject")), content);
            newItems.add(SessionTaskItem.builder()
                    .id(String.valueOf(++nextId))
                    .subject(subject.trim())
                    .description(description.trim())
                    .activeForm(activeForm)
                    .status(status)
                    .build());
        }

        tasks.clear();
        for (SessionTaskItem item : newItems) {
            tasks.put(item.getId(), item);
        }
        persistReplace(list());
        return oldSnapshot;
    }

    public List<Map<String, Object>> toClientTaskList() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionTaskItem item : list()) {
            result.add(item.toDetailMap());
        }
        return result;
    }

    public Map<String, Object> toClientSnapshot() {
        // 只读一次共享 map 并按同一份列表计算所有计数，避免并发增删时 total/计数器与 tasks 对不上。
        List<SessionTaskItem> items = list();
        List<Map<String, Object>> taskMaps = new ArrayList<>(items.size());
        long pending = 0;
        long inProgress = 0;
        long completed = 0;
        for (SessionTaskItem item : items) {
            taskMaps.add(item.toDetailMap());
            if (SessionTaskItem.STATUS_PENDING.equals(item.getStatus())) {
                pending++;
            } else if (SessionTaskItem.STATUS_IN_PROGRESS.equals(item.getStatus())) {
                inProgress++;
            } else if (SessionTaskItem.STATUS_COMPLETED.equals(item.getStatus())) {
                completed++;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listId", listId);
        body.put("tasks", taskMaps);
        body.put("total", items.size());
        body.put("pending", pending);
        body.put("inProgress", inProgress);
        body.put("completed", completed);
        return body;
    }

    private void persistReplace(List<SessionTaskItem> items) {
        if (persistence == null) {
            return;
        }
        try {
            persistence.replaceTodos(listId, items == null ? List.of() : items);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /**
     * replaceAll 之前的状态预校验：非法值必须在清空旧列表前被拒绝。
     * <p>与 {@link #normalizeStatus(String)} 共用同一套别名规则，避免工具侧和存储侧判断漂移。</p>
     */
    public static boolean isSupportedStatus(String status) {
        if (StringUtils.isBlank(status)) {
            return false;
        }
        try {
            normalizeStatus(status);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizeStatus(String status) {
        String normalized = status.trim().toLowerCase().replace('-', '_');
        return switch (normalized) {
            case SessionTaskItem.STATUS_PENDING,
                 SessionTaskItem.STATUS_IN_PROGRESS,
                 SessionTaskItem.STATUS_COMPLETED -> normalized;
            case "inprogress" -> SessionTaskItem.STATUS_IN_PROGRESS;
            case "done" -> SessionTaskItem.STATUS_COMPLETED;
            default -> throw new IllegalArgumentException(
                    "非法 status: " + status + "，允许: pending | in_progress | completed");
        };
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return "";
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }
}
