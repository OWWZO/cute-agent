package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelInfo;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;

import java.util.List;

/**
 * 问数模型元数据仓储端口。
 * 负责隔离模型与字段元数据的持久化细节。
 */
public interface IChatModelMetadataRepository {

    /** 全表去重查询的安全上限：超过时由实现截断并记录日志。 */
    int MAX_DISTINCT_SCHEMA_ROWS = 5_000;

    /**
     * 每个 modelCode 只返回一条；重复行按 id 倒序取最新一条。
     */
    List<ChatModelInfo> listDistinctModels();

    /** 直接插入，不做 upsert；调用方负责先清理同名旧行。 */
    void saveModelInfo(ChatModelInfo modelInfo);

    void deleteModelInfoByCode(String modelCode);

    /**
     * 每个列标识只返回一条；重复行按 id 倒序取最新一条。
     * <p>最多读取 {@link #MAX_DISTINCT_SCHEMA_ROWS} 行；只剩全量场景时调用方应优先使用
     * {@link #listDistinctSchemas(String)} 按 modelCode 收敛。</p>
     */
    List<ChatModelSchema> listDistinctSchemas();

    /**
     * 按 {@code modelCode} 去重读取字段；重复行按 id 倒序取最新一条。
     * <p>过滤下推到 SQL，单模型最多读取 {@link #MAX_DISTINCT_SCHEMA_ROWS} 行。
     * {@code modelCode} 为空白时返回空列表。</p>
     */
    List<ChatModelSchema> listDistinctSchemas(String modelCode);

    /** 逐个插入，不做 upsert；入参为 null/空时为空操作。 */
    void saveModelSchemas(List<ChatModelSchema> schemaList);

    void deleteModelSchemasByCode(String modelCode);
}
