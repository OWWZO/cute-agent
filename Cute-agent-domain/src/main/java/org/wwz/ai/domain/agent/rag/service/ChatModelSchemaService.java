package org.wwz.ai.domain.agent.rag.service;


import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import lombok.RequiredArgsConstructor;
import org.wwz.ai.domain.agent.rag.model.config.DataQueryModelConfig;
import org.wwz.ai.domain.agent.rag.model.schema.DataQuerySchema;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelInfo;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;
import org.wwz.ai.domain.agent.rag.port.IChatModelMetadataRepository;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 问数 schema 元数据服务。
 * <p>
 * 把数据库列信息转换为模型可理解的字段描述，并根据配置维护忽略、默认召回、分析建议和禁止字段集合。
 */
@Service
@RequiredArgsConstructor
public class ChatModelSchemaService {

    private final IChatModelMetadataRepository chatModelMetadataRepository;

    public static String getColumnUuids() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String uuid = UUID.randomUUID().toString();
            list.add(uuid);
        }
        return String.join(",", list);
    }

    private Map<String,String> parseModelColumnAlias(DataQueryModelConfig modelConfig){
        if(StringUtils.isBlank(modelConfig.getColumnAliasMap())){
            return new HashMap<>();
        }
        return JSONObject.parseObject(modelConfig.getColumnAliasMap(), new TypeReference<>() {
        });
    }

    public List<ChatModelSchema> saveModelSchema(String modelCode, DataQueryModelConfig modelConfig, List<DataQueryTableColumn> columnList, Map<String, Set<String>> fewShotMap) {
        // 先解析字段策略，再逐列生成 schema，避免把配置判断散落在列循环内部。
        Set<String> ignoreFields = getIgnoreFields(modelConfig.getIgnoreFields());
        Set<String> defaultRecallFields = getDefaultRecallFields(modelConfig.getDefaultRecallFields());
        Set<String> analyzeSuggestFields = getAnalyzeSuggestFields(modelConfig.getAnalyzeSuggestFields());
        Set<String> analyzeForbidFields = getAnalyzeForbidFields(modelConfig.getAnalyzeForbidFields());
        Set<String> syncValueFields = getSyncValueFields(modelConfig.getSyncValueFields());

        Map<String, String> aliasMap = parseModelColumnAlias(modelConfig);

        List<ChatModelSchema> saveList = new ArrayList<>();
        for (DataQueryTableColumn column : columnList) {
            String columnId = column.getName();
            // ignoreFields 统一按 lower-case + trim 归一后再比较，避免配置大小写/空格差异
            // 让本应排除的敏感列漏进 schema 与向量库。
            if (ignoreFields.contains(normalizeField(columnId))) {
                continue;
            }
            ChatModelSchema schema = new ChatModelSchema();
            schema.setModelCode(modelCode);
            schema.setColumnId(columnId);
            schema.setColumnName(column.getComment());
            schema.setColumnComment(column.getComment());
            schema.setDataType(column.getDataType());
            schema.setVectorUuid(getColumnUuids());
            schema.setSynonyms(aliasMap.getOrDefault(columnId.toLowerCase(),aliasMap.get(columnId.toUpperCase())));
            if (defaultRecallFields.contains(columnId.toUpperCase()) || defaultRecallFields.contains(columnId.toLowerCase())) {
                schema.setDefaultRecall(1);
            }
            if (analyzeSuggestFields.contains(columnId.toUpperCase()) || analyzeSuggestFields.contains(columnId.toLowerCase())) {
                schema.setAnalyzeSuggest(1);
            }
            if (analyzeForbidFields.contains(columnId.toUpperCase()) || analyzeForbidFields.contains(columnId.toLowerCase())) {
                schema.setAnalyzeSuggest(-1);
            }
            Set<String> fewShotSet = fewShotMap.getOrDefault(columnId.toLowerCase(),fewShotMap.get(columnId.toUpperCase()));
            if (CollectionUtils.isEmpty(fewShotSet)) {
                fewShotSet = fewShotMap.get(column.getComment());
            }
            if (CollectionUtils.isNotEmpty(fewShotSet)) {
                schema.setFewShot(String.join(",", fewShotSet));
                if (fewShotSet.size() < 10) {
                    syncValueFields.remove(columnId.toLowerCase());
                    syncValueFields.remove(columnId.toUpperCase());
                }
            }
            saveList.add(schema);
        }
        modelConfig.setSyncValueFields(String.join(",", syncValueFields));
        chatModelMetadataRepository.saveModelSchemas(saveList);
        return saveList;
    }

    public List<ChatModelSchema> queryDefaultRecallFields() {
        return listDistinctSchemas().stream()
                .filter(schema -> schema.getDefaultRecall() == 1)
                .collect(Collectors.toList());
    }

    /** 只加载指定模型的默认召回字段，避免一次拉全表。 */
    public List<ChatModelSchema> queryDefaultRecallFields(Collection<String> modelCodes) {
        if (CollectionUtils.isEmpty(modelCodes)) {
            return List.of();
        }
        List<ChatModelSchema> schemas = new ArrayList<>();
        for (String modelCode : new LinkedHashSet<>(modelCodes)) {
            schemas.addAll(listDistinctSchemas(modelCode));
        }
        return schemas.stream()
                .filter(schema -> schema.getDefaultRecall() == 1)
                .collect(Collectors.toList());
    }

    public List<DataQuerySchema> queryAllSchemas() {
        List<DataQuerySchema> schemaList = new ArrayList<>();
        for (ChatModelInfo model : chatModelMetadataRepository.listDistinctModels()) {
            if (model == null || StringUtils.isBlank(model.getCode())) {
                continue;
            }
            for (ChatModelSchema schema : listDistinctSchemas(model.getCode())) {
                DataQuerySchema dataQuerySchema = new DataQuerySchema();
                BeanUtils.copyProperties(schema, dataQuerySchema);
                schemaList.add(dataQuerySchema);
            }
        }
        return schemaList;
    }

    /**
     * 按模型编码清理历史 schema，避免应用每次启动重复初始化后越积越多。
     */
    public void cleanModelSchema(String modelCode) {
        chatModelMetadataRepository.deleteModelSchemasByCode(modelCode);
    }

    /**
     * 按 modelCode + columnId 去重，只保留最新一条有效 schema。
     */
    public List<ChatModelSchema> listDistinctSchemas() {
        return chatModelMetadataRepository.listDistinctSchemas();
    }

    /** 按模型编码读取字段，过滤下推到持久化层。 */
    public List<ChatModelSchema> listDistinctSchemas(String modelCode) {
        return chatModelMetadataRepository.listDistinctSchemas(modelCode);
    }

    /**
     * 解析忽略字段配置。返回值已 trim + 小写归一，调用方可以直接用
     * {@link #normalizeField} 归一后的列名比较。
     */
    public Set<String> getIgnoreFields(String ignoreFields) {
        if (StringUtils.isBlank(ignoreFields)) {
            return Collections.emptySet();
        }
        return Arrays.stream(ignoreFields.split(","))
                .map(ChatModelSchemaService::normalizeField)
                .filter(field -> !field.isEmpty())
                .collect(Collectors.toSet());
    }

    private static String normalizeField(String field) {
        return StringUtils.trimToEmpty(field).toLowerCase(Locale.ROOT);
    }

    public Set<String> getDefaultRecallFields(String defaultRecallFields) {
        if (StringUtils.isBlank(defaultRecallFields)) {
            return Collections.emptySet();
        }
        return Arrays.stream(defaultRecallFields.split(",")).collect(Collectors.toSet());
    }

    public Set<String> getAnalyzeSuggestFields(String analyzeSuggestFields) {
        if (StringUtils.isBlank(analyzeSuggestFields)) {
            return Collections.emptySet();
        }
        return Arrays.stream(analyzeSuggestFields.split(",")).collect(Collectors.toSet());
    }

    public Set<String> getAnalyzeForbidFields(String analyzeForbidFields) {
        if (StringUtils.isBlank(analyzeForbidFields)) {
            return Collections.emptySet();
        }
        return Arrays.stream(analyzeForbidFields.split(",")).collect(Collectors.toSet());
    }

    public Set<String> getSyncValueFields(String syncValueFields) {
        if (StringUtils.isBlank(syncValueFields)) {
            // 调用方会在返回值上就地改写；getter 统一返回可变集合，避免契约里埋不可变实现的坑。
            return new HashSet<>();
        }
        return Arrays.stream(syncValueFields.split(",")).collect(Collectors.toSet());
    }
}
