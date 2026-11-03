package org.wwz.ai.domain.agent.rag.port;

/**
 * Optional index preparation required by DataAgent before model metadata is refreshed.
 * <p><b>幂等与并发：</b>{@code forceRefresh=false} 的重复调用应复用已存在的索引而不报错；
 * {@code forceRefresh=true} 会重建索引并丢弃既有数据。端口本身不声明并发安全，
 * 调用方必须保证启动初始化与手动刷新不会并发执行。</p>
 * <p><b>可用性：</b>实现不可用时通过抛出异常表达；调用方据此关闭对应能力，
 * 不要依赖本端口返回 void 来判断索引已就绪。</p>
 */
public interface DataAgentInitializerPort {

    void initializeVectorIndex(boolean forceRefresh, int embeddingDimension) throws Exception;

    void initializeColumnValueIndex(boolean forceRefresh) throws Exception;
}
