package org.wwz.ai.domain.agent.channel.port;

import java.util.Optional;

import org.wwz.ai.domain.agent.channel.model.ChannelIdentity;

/**
 * 平台身份持久化端口。
 * <p>所有方法的 {@code bindingId} / {@code platform} / {@code platformUserId} 均为必填，
 * 调用方不得传 null。实现必须在参数非法时显式拒绝（抛 {@link IllegalArgumentException}），
 * 不得拼出 null 查询键去误匹配记录；{@link #find} 的空结果只表示「参数合法但身份不存在」。</p>
 */
public interface ChannelIdentityRepository {

    Optional<ChannelIdentity> find(Long bindingId, String platform, String platformUserId);

    /**
     * 已存在则刷新 {@code lastSeenAt}（{@code displayName} 非空时一并更新）并返回；
     * 不存在则插入新行。
     * <p>userId 的生成规则统一由 {@link org.wwz.ai.domain.agent.channel.policy.ChannelUserId#of(Long, String)}
     * 定义（{@code ch_{bindingId}_{sha256(platformUserId) 前 16 位}}），实现必须复用它，
     * 不得自行拼装，否则同一平台身份会推导出不同 userId。</p>
     * <p>该方法是写路径：已有身份也会触发一次 touch 更新，调用方需自行考虑写放大与事务/行锁开销。</p>
     */
    ChannelIdentity findOrCreate(Long bindingId, String platform, String platformUserId, String displayName);
}
