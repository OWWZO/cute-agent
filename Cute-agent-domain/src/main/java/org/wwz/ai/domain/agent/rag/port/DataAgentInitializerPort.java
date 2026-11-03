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

    /**
     * 准备向量索引。
     *
     * @param forceRefresh       false 时幂等创建（已存在则复用）；true 时重建并丢弃既有向量
     * @param embeddingDimension embedding 输出维度，调用方必须传正数且与所配置 embedding 模型一致；
     *                           端口本身不校验，直接透传给存储端建集合
     * @throws Exception 实现不可用或建索引失败时抛出
     */
    void initializeVectorIndex(boolean forceRefresh, int embeddingDimension) throws Exception;

    /**
     * 准备列值索引。
     *
     * @param forceRefresh false 时幂等初始化（索引已存在则复用）；true 时删除并重建，丢弃既有列值数据
     * @throws Exception 实现不可用或建索引失败时抛出
     */
    void initializeColumnValueIndex(boolean forceRefresh) throws Exception;
}
