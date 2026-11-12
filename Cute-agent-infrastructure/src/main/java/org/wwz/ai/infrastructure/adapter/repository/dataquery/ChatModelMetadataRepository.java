package org.wwz.ai.infrastructure.adapter.repository.dataquery;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelInfo;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;
import org.wwz.ai.domain.agent.rag.port.IChatModelMetadataRepository;
import org.wwz.ai.infrastructure.dao.cute.chatmodel.ChatModelInfoMapper;
import org.wwz.ai.infrastructure.dao.cute.chatmodel.ChatModelSchemaMapper;
import org.wwz.ai.infrastructure.dao.po.cute.chatmodel.ChatModelInfoPO;
import org.wwz.ai.infrastructure.dao.po.cute.chatmodel.ChatModelSchemaPO;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问数模型元数据仓储实现。
 * 问数模型元数据仓储适配器，负责 PO 与 RAG Domain Model 的映射。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ChatModelMetadataRepository implements IChatModelMetadataRepository {

    private final ChatModelInfoMapper chatModelInfoMapper;
    private final ChatModelSchemaMapper chatModelSchemaMapper;

    @Override
    public List<ChatModelInfo> listDistinctModels() {
        List<ChatModelInfoPO> modelList = chatModelInfoMapper.selectList(
                Wrappers.<ChatModelInfoPO>lambdaQuery()
                        .orderByDesc(ChatModelInfoPO::getId)
        );
        Map<String, ChatModelInfoPO> modelMap = new LinkedHashMap<>();
        for (ChatModelInfoPO modelInfo : modelList) {
            modelMap.putIfAbsent(modelInfo.getCode(), modelInfo);
        }
        List<ChatModelInfo> result = new ArrayList<>();
        for (ChatModelInfoPO modelInfo : modelMap.values()) {
            result.add(toDomain(modelInfo));
        }
        return result;
    }

    @Override
    public void saveModelInfo(ChatModelInfo modelInfo) {
        chatModelInfoMapper.insert(toPO(modelInfo));
    }

    @Override
    public void deleteModelInfoByCode(String modelCode) {
        chatModelInfoMapper.deletePhysicalByCode(modelCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByModelCode(String modelCode) {
        chatModelInfoMapper.deletePhysicalByCode(modelCode);
        chatModelSchemaMapper.deletePhysicalByModelCode(modelCode);
    }

    @Override
    public List<ChatModelSchema> listDistinctSchemas() {
        List<ChatModelSchemaPO> schemaList = chatModelSchemaMapper.selectList(
                Wrappers.<ChatModelSchemaPO>lambdaQuery()
                        .orderByDesc(ChatModelSchemaPO::getId)
                        .last("LIMIT " + MAX_DISTINCT_SCHEMA_ROWS)
        );
        if (schemaList != null && schemaList.size() >= MAX_DISTINCT_SCHEMA_ROWS) {
            log.warn("chatModelSchema.distinct_query reached limit={}；请改用 modelCode 维度查询",
                    MAX_DISTINCT_SCHEMA_ROWS);
        }
        return distinctSchemas(schemaList);
    }

    @Override
    public List<ChatModelSchema> listDistinctSchemas(String modelCode) {
        if (StringUtils.isBlank(modelCode)) {
            return List.of();
        }
        List<ChatModelSchemaPO> schemaList = chatModelSchemaMapper.selectList(
                Wrappers.<ChatModelSchemaPO>lambdaQuery()
                        .eq(ChatModelSchemaPO::getModelCode, modelCode.trim())
                        .orderByDesc(ChatModelSchemaPO::getId)
                        .last("LIMIT " + MAX_DISTINCT_SCHEMA_ROWS)
        );
        if (schemaList != null && schemaList.size() >= MAX_DISTINCT_SCHEMA_ROWS) {
            log.warn("chatModelSchema.distinct_query reached limit modelCode={} limit={}",
                    modelCode, MAX_DISTINCT_SCHEMA_ROWS);
        }
        return distinctSchemas(schemaList);
    }

    @Override
    public List<ChatModelSchema> listDistinctSchemas(Collection<String> modelCodes) {
        Set<String> codes = normalizedCodes(modelCodes);
        if (codes.isEmpty()) {
            return List.of();
        }
        // 单条 IN SQL 的 LIMIT 是全局上限；调用方需控制 codes 数量，避免多模型合计触顶。
        List<ChatModelSchemaPO> schemaList = chatModelSchemaMapper.selectList(
                Wrappers.<ChatModelSchemaPO>lambdaQuery()
                        .in(ChatModelSchemaPO::getModelCode, codes)
                        .orderByDesc(ChatModelSchemaPO::getId)
                        .last("LIMIT " + MAX_DISTINCT_SCHEMA_ROWS)
        );
        if (schemaList != null && schemaList.size() >= MAX_DISTINCT_SCHEMA_ROWS) {
            log.warn("chatModelSchema.distinct_query reached batch limit={} modelCount={}",
                    MAX_DISTINCT_SCHEMA_ROWS, codes.size());
        }
        return inCodeOrder(distinctSchemas(schemaList), codes);
    }

    private Set<String> normalizedCodes(Collection<String> modelCodes) {
        Set<String> codes = new LinkedHashSet<>();
        if (modelCodes == null) {
            return codes;
        }
        for (String modelCode : modelCodes) {
            if (StringUtils.isNotBlank(modelCode)) {
                codes.add(modelCode.trim());
            }
        }
        return codes;
    }

    private List<ChatModelSchema> inCodeOrder(List<ChatModelSchema> schemas, Collection<String> modelCodes) {
        Map<String, List<ChatModelSchema>> byModelCode = new LinkedHashMap<>();
        for (ChatModelSchema schema : schemas) {
            byModelCode.computeIfAbsent(schema.getModelCode(), ignored -> new ArrayList<>()).add(schema);
        }
        List<ChatModelSchema> result = new ArrayList<>();
        for (String modelCode : modelCodes) {
            List<ChatModelSchema> group = byModelCode.remove(modelCode);
            if (group != null) {
                result.addAll(group);
            }
        }
        return result;
    }

    private List<ChatModelSchema> distinctSchemas(List<ChatModelSchemaPO> schemaList) {
        Map<String, ChatModelSchemaPO> schemaMap = new LinkedHashMap<>();
        if (schemaList != null) {
            for (ChatModelSchemaPO schema : schemaList) {
                String key = schema.getModelCode() + "::" + schema.getColumnId();
                schemaMap.putIfAbsent(key, schema);
            }
        }
        List<ChatModelSchema> result = new ArrayList<>();
        for (ChatModelSchemaPO schema : schemaMap.values()) {
            result.add(toDomain(schema));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveModelSchemas(List<ChatModelSchema> schemaList) {
        if (CollectionUtils.isEmpty(schemaList)) {
            return;
        }
        List<ChatModelSchemaPO> poList = schemaList.stream().map(this::toPO).toList();
        chatModelSchemaMapper.insertBatch(poList);
    }

    @Override
    public void deleteModelSchemasByCode(String modelCode) {
        chatModelSchemaMapper.deletePhysicalByModelCode(modelCode);
    }

    private ChatModelInfo toDomain(ChatModelInfoPO po) {
        ChatModelInfo model = new ChatModelInfo();
        model.setId(po.getId());
        model.setCode(po.getCode());
        model.setType(po.getType());
        model.setContent(po.getContent());
        model.setName(po.getName());
        model.setUsePrompt(po.getUsePrompt());
        model.setBusinessPrompt(po.getBusinessPrompt());
        model.setYn(po.getYn());
        return model;
    }

    private ChatModelInfoPO toPO(ChatModelInfo model) {
        return ChatModelInfoPO.builder()
                .id(model.getId())
                .code(model.getCode())
                .type(model.getType())
                .content(model.getContent())
                .name(model.getName())
                .usePrompt(model.getUsePrompt())
                .businessPrompt(model.getBusinessPrompt())
                .yn(model.getYn())
                .build();
    }

    private ChatModelSchema toDomain(ChatModelSchemaPO po) {
        ChatModelSchema schema = new ChatModelSchema();
        schema.setId(po.getId());
        schema.setModelCode(po.getModelCode());
        schema.setColumnId(po.getColumnId());
        schema.setColumnName(po.getColumnName());
        schema.setColumnComment(po.getColumnComment());
        schema.setFewShot(po.getFewShot());
        schema.setDataType(po.getDataType());
        schema.setSynonyms(po.getSynonyms());
        schema.setVectorUuid(po.getVectorUuid());
        schema.setDefaultRecall(po.getDefaultRecall() == null ? 0 : po.getDefaultRecall());
        schema.setAnalyzeSuggest(po.getAnalyzeSuggest() == null ? 0 : po.getAnalyzeSuggest());
        schema.setYn(po.getYn());
        return schema;
    }

    private ChatModelSchemaPO toPO(ChatModelSchema schema) {
        return ChatModelSchemaPO.builder()
                .id(schema.getId())
                .modelCode(schema.getModelCode())
                .columnId(schema.getColumnId())
                .columnName(schema.getColumnName())
                .columnComment(schema.getColumnComment())
                .fewShot(schema.getFewShot())
                .dataType(schema.getDataType())
                .synonyms(schema.getSynonyms())
                .vectorUuid(schema.getVectorUuid())
                .defaultRecall(schema.getDefaultRecall())
                .analyzeSuggest(schema.getAnalyzeSuggest())
                .yn(schema.getYn())
                .build();
    }
}
