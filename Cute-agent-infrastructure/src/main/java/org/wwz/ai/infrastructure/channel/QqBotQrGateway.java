package org.wwz.ai.infrastructure.channel;

import lombok.extern.slf4j.Slf4j;
import org.wwz.ai.domain.agent.channel.port.ChannelQrGateway;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotCrypto;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotOnboardClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫码协议实现：创建任务、轮询、成功时解密 secret 并落库。
 * <p>bindKey 是解密 secret 的唯一凭证，只存内存，任务结束后立即丢弃。</p>
 */
@Slf4j
public class QqBotQrGateway implements ChannelQrGateway {

    private final QqBotOnboardClient onboardClient;
    private final ChannelBindingRepository bindingRepository;
    /** taskId → 本地生成的 AES key，只在任务存活期间保留。 */
    private final Map<String, String> bindKeyByTaskId = new ConcurrentHashMap<>();
    private final Map<String, String> ownerByTaskId = new ConcurrentHashMap<>();

    public QqBotQrGateway(QqBotOnboardClient onboardClient,
                          ChannelBindingRepository bindingRepository) {
        this.onboardClient = onboardClient;
        this.bindingRepository = bindingRepository;
    }

    @Override
    public String createTask(String ownerUserId) {
        String bindKey = QqBotCrypto.generateBindKey();
        String taskId = onboardClient.createBindTask(bindKey);
        bindKeyByTaskId.put(taskId, bindKey);
        ownerByTaskId.put(taskId, ownerUserId);
        log.info("[channel] qr task created taskId={} owner={}", taskId, ownerUserId);
        return taskId;
    }

    @Override
    public ChannelQrGateway.PollOutcome poll(String taskId) {
        QqBotOnboardClient.PollResult result = onboardClient.pollBindResult(taskId);
        switch (result.status()) {
            case COMPLETED -> {
                String bindKey = bindKeyByTaskId.remove(taskId);
                String owner = ownerByTaskId.remove(taskId);
                if (bindKey == null || owner == null) {
                    return ChannelQrGateway.PollOutcome.expired("扫码任务已失效，请重新生成");
                }
                String secret = QqBotCrypto.decryptSecret(result.encryptedSecret(), bindKey);
                ChannelBinding binding = saveBinding(owner, result.botAppId(), secret, result.userOpenid());
                return ChannelQrGateway.PollOutcome.completed(binding.getId());
            }
            case EXPIRED -> {
                bindKeyByTaskId.remove(taskId);
                ownerByTaskId.remove(taskId);
                return ChannelQrGateway.PollOutcome.expired("二维码已过期，请重新生成");
            }
            default -> {
                return ChannelQrGateway.PollOutcome.pending();
            }
        }
    }

    @Override
    public String buildQrUrl(String taskId) {
        return onboardClient.buildQrUrl(taskId);
    }

    private ChannelBinding saveBinding(String ownerUserId, String appId, String secret, String botOpenid) {
        ChannelBinding existing = bindingRepository
                .findByOwner(ownerUserId, ChannelBinding.PLATFORM_QQBOT)
                .orElse(null);
        if (existing != null) {
            // 并发扫两次：复用已有行，不重复插入（唯一键也会挡住）。
            return existing;
        }
        ChannelBinding binding = ChannelBinding.builder()
                .ownerUserId(ownerUserId)
                .platform(ChannelBinding.PLATFORM_QQBOT)
                .appId(appId)
                .appSecret(secret)
                .botOpenid(botOpenid)
                .status(ChannelBinding.STATUS_PENDING)
                .build();
        bindingRepository.insert(binding);
        // 刻意不打印 secret，也不打印 openid 全量。
        log.info("[channel] binding created bindingId={} appId={}", binding.getId(), appId);
        return binding;
    }
}
