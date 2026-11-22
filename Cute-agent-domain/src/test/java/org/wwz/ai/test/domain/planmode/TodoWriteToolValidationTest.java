package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskItem;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TodoWriteTool;

import java.util.List;
import java.util.Map;

/**
 * TodoWrite 是整表替换：任何非法条目都必须在替换前被拒绝，否则一次坏输入会清空现有清单。
 */
public class TodoWriteToolValidationTest {

    private final TodoWriteTool tool = new TodoWriteTool();

    @Test
    public void emptyTodosExplicitlyClearTheList() {
        SessionTaskListStore store = storeWithExistingTask();

        ToolResultPayload payload = (ToolResultPayload) tool.execute(
                Map.of("todos", List.of()), context(store));

        Assert.assertEquals(Boolean.FALSE, payload.getFailed());
        Assert.assertTrue(store.list().isEmpty());
    }

    @Test
    public void nonObjectEntriesAreRejectedInsteadOfSilentlyDropped() {
        SessionTaskListStore store = storeWithExistingTask();

        assertRejected(store, Map.of("todos", List.of("任务 A", "任务 B")), "必须是对象");

        assertListIntact(store);
    }

    @Test
    public void missingContentOrSubjectIsRejected() {
        SessionTaskListStore store = storeWithExistingTask();

        assertRejected(store, Map.of("todos", List.of(Map.of("status", "pending"))), "缺少 content 或 subject");

        assertListIntact(store);
    }

    @Test
    public void invalidStatusIsRejectedBeforeReplaceAll() {
        SessionTaskListStore store = storeWithExistingTask();

        assertRejected(store, Map.of("todos", List.of(
                Map.of("content", "新任务", "status", "blocked"))), "status 非法");

        assertListIntact(store);
    }

    @Test
    public void validWriteStillReplacesList() {
        SessionTaskListStore store = storeWithExistingTask();
        AgentContext context = context(store);

        Object result = tool.execute(Map.of("todos", List.of(
                Map.of("content", "新任务 A", "status", "pending"),
                Map.of("subject", "新任务 B", "status", "in_progress"))), context);

        Assert.assertEquals(Boolean.FALSE, ((ToolResultPayload) result).getFailed());
        List<SessionTaskItem> items = store.list();
        Assert.assertEquals(2, items.size());
        Assert.assertEquals("新任务 A", items.get(0).getSubject());
        Assert.assertEquals(SessionTaskItem.STATUS_IN_PROGRESS, items.get(1).getStatus());
    }

    @Test
    public void schemaAllowsEmptyListAndRequiresContentOrSubject() {
        Map<String, Object> properties = (Map<String, Object>) tool.toParams().get("properties");
        Map<String, Object> todos = (Map<String, Object>) properties.get("todos");

        Assert.assertNull(todos.get("minItems"));
        Map<String, Object> item = (Map<String, Object>) todos.get("items");
        Assert.assertNotNull("content / subject 至少一个必填", item.get("anyOf"));
    }

    private void assertRejected(SessionTaskListStore store, Map<String, Object> input, String messagePart) {
        ToolResultPayload payload = (ToolResultPayload) tool.execute(input, context(store));
        Assert.assertEquals(Boolean.TRUE, payload.getFailed());
        Assert.assertTrue(String.valueOf(payload.getErrorMsg()).contains(messagePart));
    }

    private static void assertListIntact(SessionTaskListStore store) {
        List<SessionTaskItem> items = store.list();
        Assert.assertEquals(1, items.size());
        Assert.assertEquals("旧任务", items.get(0).getSubject());
    }

    private static SessionTaskListStore storeWithExistingTask() {
        SessionTaskListStore store = new SessionTaskListStore("list-todo");
        store.replaceAll(List.of(Map.of("content", "旧任务", "status", "pending")));
        return store;
    }

    private static AgentContext context(SessionTaskListStore store) {
        return AgentContext.builder()
                .requestId("req-todo")
                .sessionId("sess-todo")
                .sessionControl(new SessionControl().withSessionTaskList(store))
                .build();
    }
}
