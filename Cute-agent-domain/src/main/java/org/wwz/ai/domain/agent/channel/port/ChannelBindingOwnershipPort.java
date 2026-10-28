package org.wwz.ai.domain.agent.channel.port;

/**
 * IM 渠道绑定的 owner 判定端口。
 * <p>会话授权发生在 case 层，而绑定数据属于渠道基础设施，直接依赖会形成环。
 * 所以这里只留一个最小端口，由 infrastructure 提供实现；未启用渠道时容器里没有实现，
 * 授权服务走 {@link #deniesAll()} 语义——即保持原有「只认会话本人」的行为。</p>
 */
public interface ChannelBindingOwnershipPort {

    /**
     * @return true 表示 {@code userId} 是该 binding 的 owner
     */
    boolean isOwner(Long bindingId, String userId);

    /** 未装配渠道模块时使用：任何 binding 都不放行。 */
    static ChannelBindingOwnershipPort deniesAll() {
        return (bindingId, userId) -> false;
    }
}
