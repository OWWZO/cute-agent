package org.wwz.ai.test.domain.tasklist;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTaskRegistry;

public class RuntimeBackgroundTaskOwnershipTest {

    @Test
    public void agentCanControlOwnDirectAndNestedDescendantsOnly() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask child = task(registry, "child", "root");
        RuntimeBackgroundTask grandchild = task(registry, "grandchild", "child");
        RuntimeBackgroundTask sibling = task(registry, "sibling", "root");

        Assert.assertTrue(registry.callerOwnsTask(child, "root"));
        Assert.assertFalse(registry.callerOwnsTask(sibling, "child"));
        Assert.assertFalse(registry.callerOwnsTask(child, "grandchild"));
        Assert.assertEquals("child", registry.findOwningParentAgentId(grandchild));
        Assert.assertEquals(2, registry.listOwnedBy("root").size());
        Assert.assertTrue(registry.listOwnedBy("root").stream()
                .anyMatch(task -> child.getId().equals(task.getId())));
        Assert.assertTrue(registry.listOwnedBy("root").stream()
                .anyMatch(task -> sibling.getId().equals(task.getId())));
    }

    @Test
    public void stopScopeIncludesTargetAndNestedRunningTasks() {
        RuntimeBackgroundTaskRegistry registry = new RuntimeBackgroundTaskRegistry();
        RuntimeBackgroundTask child = task(registry, "child", "root");
        RuntimeBackgroundTask grandchild = task(registry, "grandchild", "child");
        task(registry, "sibling", "root");

        Assert.assertEquals(2, registry.listRunningSubtree("child").size());
        Assert.assertTrue(registry.listRunningSubtree("child").stream()
                .anyMatch(task -> child.getId().equals(task.getId())));
        Assert.assertTrue(registry.listRunningSubtree("child").stream()
                .anyMatch(task -> grandchild.getId().equals(task.getId())));
    }

    private static RuntimeBackgroundTask task(RuntimeBackgroundTaskRegistry registry,
                                              String agentId,
                                              String parentAgentId) {
        RuntimeBackgroundTask task = registry.registerLocalAgent(agentId, "general-purpose", "prompt");
        task.setAgentId(agentId);
        task.setOwnerAgentId(parentAgentId);
        task.setParentAgentId(parentAgentId);
        task.setRootSessionId("root");
        task.setPhase(RuntimeBackgroundTask.PHASE_RUNNING);
        return task;
    }
}
