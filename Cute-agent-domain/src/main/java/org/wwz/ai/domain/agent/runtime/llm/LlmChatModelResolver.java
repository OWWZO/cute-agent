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

    /**
     * 让已缓存的 ChatModel 实例失效；下一次解析按最新配置惰性重建。
     * <p>实现必须线程安全且幂等：管理写路径可能与请求线程并发调用。调用不得阻塞，也不得影响
     * 已经发出给调用方的模型实例——失效只清缓存，不中断在途请求。</p>
     */
    void invalidateAll();
}
