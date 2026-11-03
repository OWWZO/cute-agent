package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.vector.VectorFilter;
import org.wwz.ai.domain.agent.rag.model.vector.VectorPoint;

import java.util.List;

/**
 * 向量集合与点数据管理端口。
 * <p>存储端失败以 {@link VectorPortException}（unchecked）抛出，调用方可按需捕获做重试/降级。</p>
 *
 * <h2>空值安全契约</h2>
 * <p>向量存储普遍把「空过滤条件」解释为「匹配全部」。因此批量/删除类操作必须显式拒绝
 * 空输入，实现方不得把 {@code null}/空集合静默降级成 match-all：</p>
 * <ul>
 *   <li>{@link #upsert}：{@code points} 为 {@code null} 或空时抛 {@link IllegalArgumentException}。</li>
 *   <li>{@link #deleteByIds}：{@code ids} 为 {@code null} 或空时抛 {@link IllegalArgumentException}，
 *       绝不能退化为按过滤删除。</li>
 *   <li>{@link #deleteByFilter}：{@code filter} 为 {@code null}、或过滤条件为空时
 *       抛 {@link IllegalArgumentException}；需要清空集合的调用方必须走
 *       {@link #recreateCollection} 等显式入口。</li>
 * </ul>
 */
public interface VectorIndexAdminPort {

    boolean collectionExists(String collectionName);

    /**
     * 创建集合；集合已存在时幂等返回（不报错、不清空既有数据）。
     * <p>本端口不提供 check-then-create 的原子性；并发初始化的调用方必须自行串行化。</p>
     */
    void createCollection(String collectionName, int dimension);

    void recreateCollection(String collectionName, int dimension);

    /** 批量写入；{@code points} 为 {@code null} 或空时抛 {@link IllegalArgumentException}。 */
    void upsert(String collectionName, List<VectorPoint> points);

    /** 按 id 删除；{@code ids} 为 {@code null} 或空时抛 {@link IllegalArgumentException}。 */
    void deleteByIds(String collectionName, List<String> ids);

    /**
     * 按过滤条件删除。实现应在返回前完成删除请求提交，不得在本地异步排队；
     * 删除是否立即可被后续查询观察到仍取决于存储端一致性。
     * <p>{@code filter} 为 {@code null} 或过滤条件为空时必须抛 {@link IllegalArgumentException}，
     * 禁止把空过滤当成全量删除。</p>
     */
    void deleteByFilter(String collectionName, VectorFilter filter);
}
