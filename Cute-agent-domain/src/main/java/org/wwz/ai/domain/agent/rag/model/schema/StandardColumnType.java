package org.wwz.ai.domain.agent.rag.model.schema;

/** Shared semantic types used by SQL result presentation and schema inference. */
public enum StandardColumnType {
    VARCHAR,
    DATE,
    NUMBER,
    DECIMAL;

    public static StandardColumnType of(String type) {
        for (StandardColumnType columnType : values()) {
            if (columnType.name().equalsIgnoreCase(type)) {
                return columnType;
            }
        }
        return VARCHAR;
    }
}
