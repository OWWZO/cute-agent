package org.wwz.ai.application.agent.dataquery.result;

/**
 * 数据查询字段元信息。
 */
public record DataQuerySchemaResult(
        String modelCode,
        String columnId,
        String columnName,
        String columnComment,
        String fewShot,
        String dataType,
        String synonyms,
        String vectorUuid,
        /** 默认召回标志：{@code 0}=否，{@code 1}=是。 */
        int defaultRecall,
        /** 智能分析建议策略：{@code 1}=允许，{@code -1}=显式排除，{@code 0}=未配置。 */
        int analyzeSuggest) {
}
