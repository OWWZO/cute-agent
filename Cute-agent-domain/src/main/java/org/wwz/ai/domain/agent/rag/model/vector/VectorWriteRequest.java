package org.wwz.ai.domain.agent.rag.model.vector;

import java.util.List;
import java.util.Map;

/**
 * Domain-level vector write input. Legacy request mapping stays outside Domain.
 * <p>集合本身做了防御性复制，但 payload 值仍按引用保存；嵌套的可变对象不在此处深拷贝，
 * 调用方在写入后不得再修改 payload 内部的 List/Map/数组。</p>
 */
public final class VectorWriteRequest {

    private final String collectionName;
    private final List<Item> items;

    public VectorWriteRequest(String collectionName, List<Item> items) {
        this.collectionName = collectionName;
        this.items = List.copyOf(items == null ? List.of() : items);
    }

    public String getCollectionName() {
        return collectionName;
    }

    public List<Item> getItems() {
        return items;
    }

    public static final class Item {

        private final String embeddingText;
        private final String id;
        private final Map<String, Object> payload;

        public Item(String embeddingText, String id, Map<String, Object> payload) {
            this.embeddingText = embeddingText;
            this.id = id;
            this.payload = Map.copyOf(payload == null ? Map.of() : payload);
        }

        public String getEmbeddingText() {
            return embeddingText;
        }

        public String getId() {
            return id;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }
    }
}
