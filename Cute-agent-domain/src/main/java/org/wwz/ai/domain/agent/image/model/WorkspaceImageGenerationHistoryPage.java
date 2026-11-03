package org.wwz.ai.domain.agent.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 生图历史分页结果。
 * <p>{@code total} 表示匹配条件的总行数（不是 {@code list.size()}），用于客户端计算页数。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageGenerationHistoryPage {
    private int total;
    private List<WorkspaceImageGenerationHistoryBatch> list;
}
