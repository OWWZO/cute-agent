package org.wwz.ai.test.domain.planmode;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.cancel.SessionControl;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskItem;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskListStore;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.planmode.TaskUpdateTool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型显式传 null 时，任务字段必须被清空，而不是写入字面量 "null"。
 */
public class TaskUpdateToolNullTest {

    @Test
    public void explicitNullClearsFieldsInsteadOfWritingLiteralNull() {
        SessionTaskListStore store = new SessionTaskListStore("list-null");
        SessionTaskItem item = store.create("任务", "原始描述", "正在处理", null);
        store.update(item.getId(), null, null, null, null, "alice");
        AgentContext context = AgentContext.builder()
                .requestId("req-null")
                .sessionId("sess-null")
                .sessionControl(new SessionControl().withSessionTaskList(store))
                .build();

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("taskId", item.getId());
        input.put("description", null);
        input.put("activeForm", null);
        input.put("owner", null);

        Object result = new TaskUpdateTool().execute(input, context);

        SessionTaskItem after = store.get(item.getId()).orElseThrow();
        Assert.assertEquals(Boolean.FALSE, ((ToolResultPayload) result).getFailed());
        Assert.assertNotEquals("null", after.getDescription());
        Assert.assertEquals("", after.getDescription());
        Assert.assertNull(after.getActiveForm());
        Assert.assertNull(after.getOwner());
    }
}
