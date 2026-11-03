package org.wwz.ai.application.catalog.model;

/**
 * 普通目录入口创建模型及其 API 的应用命令。
 */
public record CatalogModelCreateCommand(
        String apiId,
        String baseUrl,
        String apiKey,
        String completionsPath,
        String embeddingsPath,
        String modelId,
        String modelName,
        String modelType,
        String modelUsage,

        /** 是否支持思考；null 在创建路径中按 0 处理。 */
        Integer supportsThinking,

        /** 上下文窗口大小；当前实现原样透传，null 可能被持久化为未设置。 */
        Integer contextWindow,

        /** 状态：0 表示停用，1 表示启用；null 或其他非 0 值在创建路径中按 1 处理。 */
        Integer status) {

    @Override
    public String toString() {
        return "CatalogModelCreateCommand[apiId=" + apiId
                + ", baseUrl=" + baseUrl
                + ", apiKey=<redacted>"
                + ", completionsPath=" + completionsPath
                + ", embeddingsPath=" + embeddingsPath
                + ", modelId=" + modelId
                + ", modelName=" + modelName
                + ", modelType=" + modelType
                + ", modelUsage=" + modelUsage
                + ", supportsThinking=" + supportsThinking
                + ", contextWindow=" + contextWindow
                + ", status=" + status + "]";
    }
}
