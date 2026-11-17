package org.wwz.ai.domain.agent.ledger.timeline;

import java.util.List;

/**
 * 时间线目录的唯一写入口。
 *
 * <p>实现必须在 Execution Ledger 的现有事务方法内调用，不能从 live SSE 直接写入。</p>
 */
public interface TimelineProjectionWriter {

    void writeLlmStarted(LlmTimelineFact fact);

    void writeLlmFinished(LlmTimelineFact fact);

    void writeToolStarted(ToolTimelineFact fact);

    void writeToolFinished(ToolTimelineFact fact);

    void writeArtifacts(List<ArtifactTimelineFact> facts);

    void writeRunFinal(RunTimelineFact fact);
}
