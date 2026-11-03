package org.wwz.ai.domain.agent.ledger.replay.projector.impl;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationView;
import org.wwz.ai.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolFileRef;
import org.wwz.ai.domain.agent.runtime.stream.AgentStreamAccumulator;

import java.util.List;
import java.util.Map;

/**
 * 空链接不能被 String.valueOf(null) 伪装成 "null" 链接；否则前端会渲染坏链而不是 artifact_not_found。
 */
public class AbstractToolInvocationProjectorMissingLinkTest {

    private final ExposedProjector projector = new ExposedProjector();

    @Test
    public void nullLinksShouldBeMarkedMissing() {
        List<Map<String, Object>> merged = projector.merge(
                List.of(ToolFileRef.builder().fileName("report.md").build()),
                List.of(ArtifactView.builder().fileName("report.md").build()));

        Assert.assertEquals(1, merged.size());
        Assert.assertEquals(Boolean.TRUE, merged.get(0).get("missing"));
        Assert.assertEquals("artifact_not_found", merged.get(0).get("missingReason"));
    }

    @Test
    public void realLinksShouldNotBeMarkedMissing() {
        List<Map<String, Object>> merged = projector.merge(
                List.of(ToolFileRef.builder().fileName("report.md").build()),
                List.of(ArtifactView.builder()
                        .fileName("report.md")
                        .downloadUrl("https://example.com/report.md")
                        .build()));

        Assert.assertEquals(1, merged.size());
        Assert.assertEquals(Boolean.FALSE, merged.get(0).get("missing"));
        Assert.assertFalse(merged.get(0).containsKey("missingReason"));
    }

    private static final class ExposedProjector extends AbstractToolInvocationProjector {

        @Override
        public boolean supports(String toolName) {
            return true;
        }

        @Override
        public List<ProjectedReplayEvent> project(ToolInvocationView invocation,
                                                  List<ArtifactView> artifacts,
                                                  AgentStreamAccumulator state) {
            return List.of();
        }

        private List<Map<String, Object>> merge(List<ToolFileRef> fileRefs, List<ArtifactView> artifacts) {
            return mergeFileRefs(fileRefs, artifacts);
        }
    }
}
