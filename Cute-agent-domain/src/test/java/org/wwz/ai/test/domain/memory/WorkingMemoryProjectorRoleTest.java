package org.wwz.ai.test.domain.memory;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.memory.WorkingMemoryMessage;
import org.wwz.ai.domain.agent.memory.WorkingMemoryProjector;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.enums.RoleType;

import java.util.List;

/**
 * hydrate 回归：未知/损坏的 role 不能被猜成 USER（否则历史内容会以用户输入身份回灌 prompt）。
 */
public class WorkingMemoryProjectorRoleTest {

    @Test
    public void unknownRoleRowIsSkippedInsteadOfBeingTreatedAsUser() {
        WorkingMemoryProjector projector = new WorkingMemoryProjector();
        List<WorkingMemoryMessage> rows = List.of(
                row(1, "user", "真实提问"),
                row(2, "future_role", "未来版本写入的内容"),
                row(3, "ASSISTANT", "回答"));

        List<Message> messages = projector.hydrate(rows);

        Assert.assertEquals("未知 role 行必须跳过", 2, messages.size());
        Assert.assertEquals(RoleType.USER, messages.get(0).getRole());
        Assert.assertEquals("真实提问", messages.get(0).getContent());
        Assert.assertEquals(RoleType.ASSISTANT, messages.get(1).getRole());
    }

    @Test
    public void blankRoleRowStillSkipped() {
        WorkingMemoryProjector projector = new WorkingMemoryProjector();

        List<Message> messages = projector.hydrate(List.of(row(1, "  ", "x")));

        Assert.assertTrue(messages.isEmpty());
    }

    private static WorkingMemoryMessage row(int seq, String role, String content) {
        return WorkingMemoryMessage.builder()
                .sessionId("sess-1")
                .memoryScope("main")
                .seqNo(seq)
                .role(role)
                .content(content)
                .build();
    }
}
