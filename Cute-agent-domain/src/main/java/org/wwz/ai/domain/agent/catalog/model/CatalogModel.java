package org.wwz.ai.domain.agent.catalog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录用户可选择的模型目录项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogModel {

    /** 模型唯一标识（对应 {@code ai_client_model.model_id}，已 trim）；同一目录内按此值去重。 */
    private String modelId;
    /** 上游模型名，直接透传给模型服务；可能为 null，展示时由调用方兜底。 */
    private String modelName;
    /** 模型类型，取值如 {@code openai} / {@code deepseek} / {@code claude}；未配置时为 null。 */
    private String modelType;
    /** 是否支持深度思考：{@code 1}=支持，{@code 0}=不支持；null 表示未配置。 */
    private Integer supportsThinking;
    /** 上下文窗口大小，单位 token；null 或 {@code <= 0} 表示未配置，运行时回退 yml/默认值。 */
    private Integer contextWindow;
    /** 目录状态：{@code 1}=启用，{@code 0}=停用；当前目录查询固定填 {@code 1}。 */
    private Integer status;
}
