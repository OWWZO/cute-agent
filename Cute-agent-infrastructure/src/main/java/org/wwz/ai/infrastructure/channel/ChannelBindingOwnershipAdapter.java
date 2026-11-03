package org.wwz.ai.infrastructure.channel;

import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingOwnershipPort;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;

import java.util.Optional;

/**
 * 把渠道绑定表接到 case 层的授权端口上。
 * <p>只有 owner 能读自己 bot 下的会话；绑定不存在或已解绑时一律拒绝。</p>
 */
public class ChannelBindingOwnershipAdapter implements ChannelBindingOwnershipPort {

    private final ChannelBindingRepository bindingRepository;

    public ChannelBindingOwnershipAdapter(ChannelBindingRepository bindingRepository) {
        this.bindingRepository = bindingRepository;
    }

    @Override
    public boolean isOwner(Long bindingId, String userId) {
        // 安全判定端口：任何不完整/异常输入都 fail-closed。
        if (bindingId == null || StringUtils.isBlank(userId)) {
            return false;
        }
        Optional<ChannelBinding> binding = bindingRepository.findById(bindingId);
        return binding.map(value -> isActiveOwner(value, userId))
                .orElse(false);
    }

    /**
     * owner 匹配还不够：已解绑（DISABLED）/已删除的 binding 仍保留原 ownerUserId，
     * 只看 owner 会让被撤销的 bot 继续授权。只有 ACTIVE 且未软删除才放行。
     */
    private boolean isActiveOwner(ChannelBinding binding, String userId) {
        if (binding == null
                || !binding.isActive()
                || Integer.valueOf(1).equals(binding.getDeleted())) {
            return false;
        }
        return StringUtils.equals(binding.getOwnerUserId(), userId);
    }
}
