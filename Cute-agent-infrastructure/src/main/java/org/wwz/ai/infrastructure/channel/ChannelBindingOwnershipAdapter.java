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
        if (bindingId == null || StringUtils.isBlank(userId)) {
            return false;
        }
        Optional<ChannelBinding> binding = bindingRepository.findById(bindingId);
        return binding.map(value -> StringUtils.equals(value.getOwnerUserId(), userId))
                .orElse(false);
    }
}
