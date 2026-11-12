package org.wwz.ai.infrastructure.dao.cute;

import lombok.Getter;
import lombok.Setter;
import org.wwz.ai.domain.agent.memory.WorkingMemorySearchMessage;

/** Keeps the SQL term group alongside the original search-row shape. */
@Getter
@Setter
public class WorkingMemoryTermScanHit extends WorkingMemorySearchMessage {
    private Integer termGroup;
}
