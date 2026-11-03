package org.wwz.ai.domain.agent.runtime.llm;

/**
 * LLM model cache invalidation seam.
 * <p>
 * The concrete model resolver belongs to Infrastructure. The catalog only needs
 * to invalidate provider instances after an admin configuration change.
 * <p>与 {@code catalog.port.ICatalogModelRuntimePort} 的关系：后者是「读取可选模型 +
 * 失效」的组合端口，由 catalog 适配器实现并供 case 层调用；本接口只表达「让底层
 * ChatModel 实例缓存失效」这一最小动作，由 {@code LlmModelCatalog} 内部依赖。
 * 两个接口不合并，是为了避免 catalog 适配器反向依赖 runtime 的模型目录实现。</p>
 */
public interface LlmChatModelResolver {

    void invalidateAll();
}
