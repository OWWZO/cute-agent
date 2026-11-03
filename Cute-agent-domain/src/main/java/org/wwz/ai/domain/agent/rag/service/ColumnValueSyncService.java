package org.wwz.ai.domain.agent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelInfo;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;
import org.wwz.ai.domain.agent.rag.port.ColumnValueIndexAdminPort;
import org.wwz.ai.domain.agent.rag.port.ColumnValueSyncPort;

import java.util.List;

/**
 * Domain-facing column-value index facade kept for startup and metadata compatibility callers.
 */
@Slf4j
@Service
public class ColumnValueSyncService {

    private final ColumnValueIndexAdminPort columnValueIndexAdminPort;
    private final ColumnValueSyncPort columnValueSyncPort;

    @Autowired
    public ColumnValueSyncService(ColumnValueIndexAdminPort columnValueIndexAdminPort,
                                  ColumnValueSyncPort columnValueSyncPort) {
        this.columnValueIndexAdminPort = columnValueIndexAdminPort;
        this.columnValueSyncPort = columnValueSyncPort;
    }

    public void initColumnValueIndex() {
        requireIndexPort();
        if (!columnValueIndexAdminPort.isAvailable()) {
            throw new IllegalStateException("column-value index is unavailable");
        }
        columnValueIndexAdminPort.initializeIndex();
    }

    public void recreateColumnValueIndex() {
        requireIndexPort();
        if (!columnValueIndexAdminPort.isAvailable()) {
            throw new IllegalStateException("column-value index is unavailable");
        }
        columnValueIndexAdminPort.recreateIndex();
    }

    public void syncColumnValueBatch(ChatModelInfo modelInfo, List<ChatModelSchema> schemas) {
        if (schemas == null || schemas.isEmpty()) {
            return;
        }
        String modelCode = modelInfo == null ? null : modelInfo.getCode();
        if (columnValueSyncPort == null) {
            log.warn("column-value 同步端口缺失，跳过同步: modelCode={}", modelCode);
            return;
        }
        if (columnValueIndexAdminPort == null || !columnValueIndexAdminPort.isAvailable()) {
            log.warn("column-value 索引不可用，跳过同步: modelCode={}", modelCode);
            return;
        }
        columnValueSyncPort.syncColumnValues(modelInfo, List.copyOf(schemas));
    }

    public void syncColumnValue(ChatModelInfo modelInfo, ChatModelSchema schema) {
        if (schema != null) {
            syncColumnValueBatch(modelInfo, List.of(schema));
        }
    }

    private void requireIndexPort() {
        if (columnValueIndexAdminPort == null) {
            throw new IllegalStateException("column-value index port is unavailable");
        }
    }
}
