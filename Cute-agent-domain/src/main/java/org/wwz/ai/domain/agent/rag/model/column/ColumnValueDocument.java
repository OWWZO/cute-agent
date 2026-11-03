package org.wwz.ai.domain.agent.rag.model.column;

import java.util.Map;

/**
 * 列值索引文档。
 * <p>构造时只做浅拷贝：Map 结构不可变，但其中的可变值对象（List/Map/数组等）
 * 仍与调用方共享，调用方须按不可变数据处理。</p>
 */
public final class ColumnValueDocument {

    private final String id;
    private final Map<String, Object> fields;

    public ColumnValueDocument(String id, Map<String, Object> fields) {
        this.id = id;
        this.fields = Map.copyOf(fields == null ? Map.of() : fields);
    }

    public String getId() {
        return id;
    }

    public Map<String, Object> getFields() {
        return fields;
    }
}
