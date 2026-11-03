package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelInfo;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;

import java.util.List;

/**
 * 问数模型元数据仓储端口。
 * 负责隔离模型与字段元数据的持久化细节。
 */
public interface IChatModelMetadataRepository {

    /**
     * 每个 modelCode 只返回一条；重复行按 id 倒序取最新一条。
     */
    List<ChatModelInfo> listDistinctModels();

    /** 直接插入，不做 upsert；调用方负责先清理同名旧行。 */
    void saveModelInfo(ChatModelInfo modelInfo);

    void deleteModelInfoByCode(String modelCode);

    /** 每个列标识只返回一条；重复行按 id 倒序取最新一条。 */
    List<ChatModelSchema> listDistinctSchemas();

    /** 逐个插入，不做 upsert；入参为 null/空时为空操作。 */
    void saveModelSchemas(List<ChatModelSchema> schemaList);

    void deleteModelSchemasByCode(String modelCode);
}
