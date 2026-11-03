package org.wwz.ai.application.agent.channel.binding;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.types.agent.config.ChannelProperties;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;
import org.wwz.ai.domain.agent.channel.port.ChannelConnectionPort;

import java.util.List;
import java.util.Optional;

/**
 * 绑定用例：查询、解绑、重试、上限校验。
 * <p>所有方法都以「当前登录用户」为第一参数——身份只从 {@code UserRequestContext} 取，
 * 不信任请求体。</p>
 */
@Slf4j
public class ChannelBindingApplicationService {

    private final ChannelBindingRepository bindingRepository;
    private final ChannelConnectionPort connectionPort;
    private final ChannelProperties properties;

    public ChannelBindingApplicationService(ChannelBindingRepository bindingRepository,
                                            ChannelConnectionPort connectionPort,
                                            ChannelProperties properties) {
        this.bindingRepository = bindingRepository;
        this.connectionPort = connectionPort;
        this.properties = properties;
    }

    public Optional<ChannelBinding> mine(String ownerUserId) {
        return bindingRepository.findByOwner(ownerUserId, ChannelBinding.PLATFORM_QQBOT);
    }

    public long globalBindingCount() {
        return bindingRepository.countAll();
    }

    public int maxBindings() {
        return properties.getMaxBindings();
    }

    /** 未绑定且未达全局上限才允许开始扫码。 */
    public void assertCanCreate(String ownerUserId) {
        if (mine(ownerUserId).isPresent()) {
            throw new IllegalStateException("已绑定，请先解绑");
        }
        if (bindingRepository.countAll() >= properties.getMaxBindings()) {
            throw new IllegalStateException("已达绑定上限（" + properties.getMaxBindings() + "），请联系管理员");
        }
    }

    /**
     * 校验绑定存在且属于 {@code ownerUserId}，供 retry / unbind / sessions 复用。
     * <p>本方法不校验绑定状态；需要状态前置条件的调用方必须在返回后自行检查。</p>
     */
    public ChannelBinding requireOwned(Long bindingId, String ownerUserId) {
        ChannelBinding binding = bindingRepository.findById(bindingId)
                .orElseThrow(() -> new IllegalStateException("绑定不存在"));
        if (!StringUtils.equals(binding.getOwnerUserId(), ownerUserId)) {
            throw new IllegalStateException("无权操作该绑定");
        }
        return binding;
    }

    /** ERROR → PENDING，然后异步重连。 */
    public void retry(Long bindingId, String ownerUserId) {
        ChannelBinding binding = requireOwned(bindingId, ownerUserId);
        if (!binding.canRetry()) {
            throw new IllegalStateException("当前状态不可重试：" + binding.getStatus());
        }
        bindingRepository.updateStatus(bindingId, ChannelBinding.STATUS_PENDING, null, null);
        binding.setStatus(ChannelBinding.STATUS_PENDING);
        connectionPort.startConnection(binding);
    }

    /**
     * 解绑：先停连接再置 DISABLED。
     * <p>不删身份表与历史会话——owner 仍要在 Web UI 看到这些对话。</p>
     */
    public String unbind(Long bindingId, String ownerUserId) {
        requireOwned(bindingId, ownerUserId);
        connectionPort.stopConnection(bindingId);
        bindingRepository.disable(bindingId);
        log.info("channel binding disabled bindingId={} owner={}", bindingId, ownerUserId);
        return "历史会话仍可在 Web UI 查看，但 QQ 侧无法再接续之前的对话（openid 随 bot 变化）";
    }

    /** 启动恢复：把所有 ACTIVE 绑定重新拉起来。 */
    public List<ChannelBinding> listActive() {
        return bindingRepository.listByStatus(ChannelBinding.STATUS_ACTIVE);
    }
}
