package org.wwz.ai.domain.agent.channel.port;

/**
 * IM 渠道绑定的 owner 判定端口。
 * <p>会话授权发生在 case 层，而绑定数据属于渠道基础设施，直接依赖会形成环。
 * 所以这里只留一个最小端口，由 infrastructure 提供实现；未启用渠道时容器里没有实现，
 * 授权服务走 {@link #deniesAll()} 语义——即保持原有「只认会话本人」的行为。</p>
 *
 * <p><b>安全契约（所有实现必须遵守）</b>：</p>
 * <ul>
 *   <li>只有 {@code userId} 是该 binding 的 owner，<b>并且</b> binding 仍处于有效状态
 *       （{@code status=ACTIVE} 且未软删除）时才返回 {@code true}。已解绑/禁用/删除的 binding
 *       仍保留原 {@code ownerUserId}，不能仅凭 owner 字段放行。</li>
 *   <li>入参为 {@code null}/空白、binding 不存在或实现无法判断时，必须 <b>fail-closed</b> 返回
 *       {@code false}，不得抛 NPE 或回退为放行。</li>
 * </ul>
 */
public interface ChannelBindingOwnershipPort {

    /**
     * @return true 表示 {@code userId} 是该 binding 的有效 owner
     */
    boolean isOwner(Long bindingId, String userId);

    /**
     * 未装配渠道模块时使用：任何 binding 都不放行。
     * <p>消费方必须显式通过 {@code ObjectProvider#getIfAvailable(ChannelBindingOwnershipPort::deniesAll)}
     * 取用，不能依赖「容器里没有实现就会自动回退」——缺失 bean 时普通注入会直接启动失败。</p>
     */
    static ChannelBindingOwnershipPort deniesAll() {
        return (bindingId, userId) -> false;
    }
}
