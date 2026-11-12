package org.wwz.ai.infrastructure.dao.cute;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** One LIKE term in a batched discovery scan, with its original per-term limit. */
@Getter
@AllArgsConstructor
public class WorkingMemoryTermScanTerm {
    private final String query;
    private final int termGroup;
    private final int termLimit;
    private final int termRank;
}
