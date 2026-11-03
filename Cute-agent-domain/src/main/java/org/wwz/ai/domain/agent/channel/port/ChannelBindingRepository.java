package org.wwz.ai.domain.agent.channel.port;

import java.util.List;
import java.util.Optional;

import org.wwz.ai.domain.agent.channel.model.ChannelBinding;

/**
 * 绑定的持久化端口。实现在 infrastructure 的 channel/dao 之上，case 只依赖这个接口。
 * <p>{@code (platform, appId, deleted)} 与 {@code (ownerUserId, platform, deleted)} 在 DB 上有唯一索引，
 * 因此 {@link #findByOwner}/{@link #findByAppId} 最多返回一行。</p>
 */
public interface ChannelBindingRepository {

    /** 按状态查询的默认安全上限。 */
    int DEFAULT_STATUS_LIST_LIMIT = 500;

    /**
     * 按主键查询。不返回软删除（{@code deleted = 1}）的行；{@code bindingId} 为 null 时返回 empty。
     */
    Optional<ChannelBinding> findById(Long bindingId);

    /**
     * 按 owner + 平台查询。不返回软删除（{@code deleted = 1}）的行。
     */
    Optional<ChannelBinding> findByOwner(String ownerUserId, String platform);

    /**
     * 按平台 + appId 查询。不返回软删除（{@code deleted = 1}）的行。
     */
    Optional<ChannelBinding> findByAppId(String platform, String appId);

    /**
     * 按状态查询的默认入口；等价于 {@code listByStatus(status, DEFAULT_STATUS_LIST_LIMIT)}。
     * <p>状态桶可能包含大量 ERROR / RETRY 绑定，调用方不应假设返回全量。
     * 不返回软删除（{@code deleted = 1}）的行。</p>
     */
    default List<ChannelBinding> listByStatus(String status) {
        return listByStatus(status, DEFAULT_STATUS_LIST_LIMIT);
    }

    /**
     * 按状态查询最多 {@code limit} 行（{@code limit <= 0} 取默认上限）。
     * <p>实现必须把 LIMIT 下推到 SQL，不得先全表加载再内存截断。</p>
     */
    List<ChannelBinding> listByStatus(String status, int limit);

    /** 全局绑定数，用于上限校验。 */
    long countAll();

    /** 插入并回填自增主键。 */
    void insert(ChannelBinding binding);

    /**
     * 原子地「校验全局上限 + 插入」：达到 {@code limit} 时不写入并返回 false。
     * <p>{@link #countAll()} 与 {@link #insert(ChannelBinding)} 是典型的 check-then-act，
     * 并发扫码会让两个请求同时看到「未达上限」。需要执行上限约束的调用方必须走本方法，
     * 不能自行先 count 再 insert。</p>
     * <p>默认实现以仓储实例为 monitor，只保证单实例内串行；多实例部署的实现必须用
     * 数据库锁/条件插入覆盖本方法，否则跨实例仍然超限。</p>
     */
    default boolean insertIfBelowLimit(ChannelBinding binding, long limit) {
        synchronized (this) {
            if (countAll() >= limit) {
                return false;
            }
            insert(binding);
            return true;
        }
    }

    /**
     * 只更新状态相关字段（status / lastError / connectedAt），不碰凭证。
     * <p>三个字段都是直接覆盖写入：{@code lastError}/{@code connectedAt} 传 null 会清空对应列，
     * 而不是保持原值。软删除（{@code deleted = 1}）的行不会被更新。</p>
     */
    void updateStatus(Long bindingId, String status, String lastError, java.time.LocalDateTime connectedAt);

    /** 解绑：软删除 + 置 DISABLED。 */
    void disable(Long bindingId);
}
