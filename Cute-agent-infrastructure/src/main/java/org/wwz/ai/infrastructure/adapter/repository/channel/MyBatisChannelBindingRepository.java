package org.wwz.ai.infrastructure.adapter.repository.channel;

import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;
import org.wwz.ai.infrastructure.channel.dao.IAiAgentChannelBindingDao;
import org.wwz.ai.infrastructure.channel.dao.po.AiAgentChannelBindingPO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 绑定仓储的 MyBatis 实现。
 */
public class MyBatisChannelBindingRepository implements ChannelBindingRepository {

    private final IAiAgentChannelBindingDao dao;

    public MyBatisChannelBindingRepository(IAiAgentChannelBindingDao dao) {
        this.dao = dao;
    }

    @Override
    public Optional<ChannelBinding> findById(Long bindingId) {
        if (bindingId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(toDomain(dao.queryById(bindingId)));
    }

    @Override
    public Optional<ChannelBinding> findByOwner(String ownerUserId, String platform) {
        return Optional.ofNullable(toDomain(dao.queryByOwner(ownerUserId, platform)));
    }

    @Override
    public Optional<ChannelBinding> findByAppId(String platform, String appId) {
        return Optional.ofNullable(toDomain(dao.queryByAppId(platform, appId)));
    }

    @Override
    public List<ChannelBinding> listByStatus(String status) {
        return dao.queryByStatus(status).stream().map(this::toDomain).toList();
    }

    @Override
    public long countAll() {
        return dao.countAll();
    }

    @Override
    public void insert(ChannelBinding binding) {
        AiAgentChannelBindingPO po = AiAgentChannelBindingPO.builder()
                .ownerUserId(binding.getOwnerUserId())
                .platform(binding.getPlatform())
                .appId(binding.getAppId())
                .appSecret(binding.getAppSecret())
                .botOpenid(binding.getBotOpenid())
                .status(binding.getStatus())
                .lastError(binding.getLastError())
                .connectedAt(binding.getConnectedAt())
                .build();
        dao.insert(po);
        binding.setId(po.getId());
    }

    @Override
    public void updateStatus(Long bindingId, String status, String lastError, LocalDateTime connectedAt) {
        dao.updateStatus(bindingId, status, lastError, connectedAt);
    }

    @Override
    public void disable(Long bindingId) {
        dao.disable(bindingId);
    }

    private ChannelBinding toDomain(AiAgentChannelBindingPO po) {
        if (po == null) {
            return null;
        }
        return ChannelBinding.builder()
                .id(po.getId())
                .ownerUserId(po.getOwnerUserId())
                .platform(po.getPlatform())
                .appId(po.getAppId())
                .appSecret(po.getAppSecret())
                .botOpenid(po.getBotOpenid())
                .status(po.getStatus())
                .lastError(po.getLastError())
                .connectedAt(po.getConnectedAt())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .deleted(po.getDeleted())
                .build();
    }
}
