package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * multimodal_agent 终态结构化输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultimodalAgentToolOutput implements ToolStructuredOutput {

    private String summary;

    private String markdownContent;

    @Builder.Default
    private List<ToolFileRef> fileRefs = new ArrayList<>();

    /** 只读视图，并保证 null 被归一为空列表。 */
    public List<ToolFileRef> getFileRefs() {
        return fileRefs == null ? List.of() : Collections.unmodifiableList(fileRefs);
    }

    public void setFileRefs(List<ToolFileRef> fileRefs) {
        this.fileRefs = fileRefs == null ? new ArrayList<>() : new ArrayList<>(fileRefs);
    }

    @Override
    public String getToolName() {
        return ToolOutputNames.MULTIMODAL_AGENT;
    }
}
