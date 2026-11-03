package org.wwz.ai.application.agent.dataquery.result;

import java.util.List;

/**
 * 数据查询模型元信息。
 */
public record DataQueryModelResult(
        String modelCode,
        String modelName,
        String usePrompt,
        String businessPrompt,
        /**
         * 模型类型；当前生产与消费逻辑支持 {@code table} 与 {@code sql}（忽略大小写）。
         */
        String type,
        String content,
        List<DataQuerySchemaResult> schemaList) {
}
