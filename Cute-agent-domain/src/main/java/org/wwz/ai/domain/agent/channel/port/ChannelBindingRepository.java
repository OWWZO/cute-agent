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

    Optional<ChannelBinding> findById(Long bindingId);

    Optional<ChannelBinding> findByOwner(String ownerUserId, String platform);

    Optional<ChannelBinding> findByAppId(String platform, String appId);

    /**
     * 按状态查询的默认入口；等价于 {@code listByStatus(status, DEFAULT_STATUS_LIST_LIMIT)}。
     * <p>状态桶可能包含大量 ERROR / RETRY 绑定，调用方不应假设返回全量。</p>
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

    /** 只更新状态相关字段（status / lastError / connectedAt），不碰凭证。 */
    void updateStatus(Long bindingId, String status, String lastError, java.time.LocalDateTime connectedAt);

    /** 解绑：软删除 + 置 DISABLED。 */
    void disable(Long bindingId);
}
