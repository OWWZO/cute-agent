package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * file_tool 旧账本行的结构化输出模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileToolOutput implements ToolStructuredOutput {

    private String command;

    private String primaryFileName;

    private String previewUrl;

    private String downloadUrl;

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
        return ToolOutputNames.FILE_TOOL;
    }
}
