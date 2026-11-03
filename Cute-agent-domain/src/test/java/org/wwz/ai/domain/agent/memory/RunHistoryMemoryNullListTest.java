package org.wwz.ai.domain.agent.memory;

import org.junit.Assert;
import org.junit.Test;

/**
 * @Builder.Default 只保护 builder 路径；无参/全参构造和 setter 都可能写入 null，
 * getter 必须始终返回可写列表。
 */
public class RunHistoryMemoryNullListTest {

    @Test
    public void noArgsConstructorShouldExposeWritableLists() {
        RunHistoryMemory memory = new RunHistoryMemory();

        Assert.assertNotNull(memory.getSessionInputFiles());
        Assert.assertNotNull(memory.getReactCycles());
        memory.getSessionInputFiles().add(new FileArtifactMemory());
        memory.getReactCycles().add(new ReactCycleMemory());
    }

    @Test
    public void builderWithExplicitNullShouldFallBackToWritableLists() {
        RunHistoryMemory memory = RunHistoryMemory.builder()
                .sessionInputFiles(null)
                .reactCycles(null)
                .build();

        Assert.assertTrue(memory.getSessionInputFiles().isEmpty());
        Assert.assertTrue(memory.getReactCycles().isEmpty());
        memory.getSessionInputFiles().add(new FileArtifactMemory());
    }

    @Test
    public void setterWithNullShouldFallBackToWritableLists() {
        RunHistoryMemory memory = new RunHistoryMemory();
        memory.setSessionInputFiles(null);
        memory.setReactCycles(null);

        Assert.assertTrue(memory.getSessionInputFiles().isEmpty());
        Assert.assertTrue(memory.getReactCycles().isEmpty());
    }
}
