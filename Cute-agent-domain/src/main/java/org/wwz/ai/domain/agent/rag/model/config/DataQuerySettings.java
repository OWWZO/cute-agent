package org.wwz.ai.domain.agent.rag.model.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Data Query business settings. JDBC credentials and search-client endpoints stay in Infrastructure. */
@Data
@Component
@ConfigurationProperties(prefix = "autobots.data-agent")
public class DataQuerySettings {

    public static final String SCHEMA_COLLECTION_NAME = "cute_model_schema";

    private String agentUrl;
    private Boolean forceRefresh = false;
    private List<DataQueryModelConfig> modelList;
    private VectorRecallSettings qdrantConfig = new VectorRecallSettings();
    private ColumnValueRecallSettings esConfig = new ColumnValueRecallSettings();
    private DataQueryDatabaseSettings dbConfig = new DataQueryDatabaseSettings();

    /** 默认提示词只需应用一次；避免每次读取都遍历并改写绑定对象。 */
    private volatile boolean businessPromptDefaultsApplied;

    public void setModelList(List<DataQueryModelConfig> modelList) {
        this.modelList = modelList;
        this.businessPromptDefaultsApplied = false;
    }

    public List<DataQueryModelConfig> getModelList() {
        applyBusinessPromptDefaults();
        return modelList;
    }

    private synchronized void applyBusinessPromptDefaults() {
        if (businessPromptDefaultsApplied) {
            return;
        }
        if (modelList != null) {
            for (DataQueryModelConfig model : modelList) {
                if (model != null && (model.getBusinessPrompt() == null || model.getBusinessPrompt().isBlank())) {
                    String prompt = DataQueryModelPrompts.businessPromptFor(model.getId());
                    if (prompt != null) {
                        model.setBusinessPrompt(prompt);
                    }
                }
            }
        }
        businessPromptDefaultsApplied = true;
    }

    public VectorRecallSettings getQdrantConfig() {
        if (qdrantConfig == null) {
            qdrantConfig = new VectorRecallSettings();
        }
        return qdrantConfig;
    }

    public ColumnValueRecallSettings getEsConfig() {
        if (esConfig == null) {
            esConfig = new ColumnValueRecallSettings();
        }
        return esConfig;
    }

    public DataQueryDatabaseSettings getDbConfig() {
        if (dbConfig == null) {
            dbConfig = new DataQueryDatabaseSettings();
        }
        return dbConfig;
    }
}
