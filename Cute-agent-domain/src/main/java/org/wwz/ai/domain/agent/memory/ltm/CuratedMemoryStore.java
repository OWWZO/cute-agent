package org.wwz.ai.domain.agent.memory.ltm;

import java.util.List;

/**
 * 用户级有界策展记忆端口。禁止向量索引；仅全量快照注入。
 */
public interface CuratedMemoryStore {

    /** 单 scope 单次快照的默认安全上限。 */
    int DEFAULT_LIST_LIMIT = 500;

    CuratedMemoryWriteResult add(LtmOwner owner,
                                 CuratedMemoryScope scope,
                                 String content,
                                 String sourceSessionId,
                                 String sourceRequestId,
                                 String writeOrigin);

    CuratedMemoryWriteResult replace(LtmOwner owner,
                                     CuratedMemoryScope scope,
                                     String oldText,
                                     String content,
                                     String sourceSessionId,
                                     String sourceRequestId,
                                     String writeOrigin);

    CuratedMemoryWriteResult remove(LtmOwner owner,
                                    CuratedMemoryScope scope,
                                    String oldText,
                                    String sourceSessionId,
                                    String sourceRequestId,
                                    String writeOrigin);

    /** 等价于 {@code listActive(owner, scope, DEFAULT_LIST_LIMIT)}。 */
    default List<CuratedMemoryEntry> listActive(LtmOwner owner, CuratedMemoryScope scope) {
        return listActive(owner, scope, DEFAULT_LIST_LIMIT);
    }

    /**
     * 读取某个 scope 下最多 {@code limit} 条活跃条目（{@code limit <= 0} 取默认上限）。
     * <p>实现必须把 LIMIT 下推到查询；策展记忆本身受字符上限约束，分页主要用于防止
     * 大量短条目导致的热路径全量扫描。</p>
     */
    List<CuratedMemoryEntry> listActive(LtmOwner owner, CuratedMemoryScope scope, int limit);

    /**
     * 会话开始冻结注入用快照文本。
     */
    String formatSnapshot(LtmOwner owner);

    int usedChars(LtmOwner owner, CuratedMemoryScope scope);

    int charLimit(CuratedMemoryScope scope);
}
