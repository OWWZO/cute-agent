package org.wwz.ai.domain.agent.adapter.repository;

import org.wwz.ai.domain.agent.runtime.llm.LlmModelBinding;

import java.util.List;

/**
 * 可出站 LLM 模型配置仓储端口。
 * <p>
 * 数据源为管理台维护的 {@code ai_client_api} + {@code ai_client_model}；
 * domain 只消费已拼装好的绑定，不触碰 DAO。
 */
public interface ILlmModelConfigRepository {

    /**
     * 启用且配齐 baseUrl / apiKey 的模型绑定（可直接用于出站）。
     *
     * <p>返回契约：</p>
     * <ul>
     *   <li>永不返回 {@code null}；没有可用模型时返回空列表。空列表表示「没有可用模型」，
     *       不用于表达查询失败；</li>
     *   <li>顺序确定：当前实现按 {@code ai_client_model.create_time DESC, id DESC} 返回，
     *       即新配置优先。{@code LlmModelCatalog.pick} 依赖该顺序取默认模型（空/default 取
     *       第一条非 fallback 绑定）；</li>
     *   <li>过滤由实现完成：缺 modelId/modelName/apiId、API 未启用、或 API 缺
     *       baseUrl/apiKey 的行会被跳过；重复 modelId 不做去重；</li>
     *   <li>返回实现新建的可变列表，调用方不应假定其不可变。</li>
     * </ul>
     *
     * <p>异常契约：实现不吞基础设施异常（如 {@code DataAccessException}），是否降级由调用方决定；
     * 当前调用方 {@code LlmModelCatalog} 捕获后记录 warn 并回退到缓存或空列表。</p>
     */
    List<LlmModelBinding> listUsable();
}
