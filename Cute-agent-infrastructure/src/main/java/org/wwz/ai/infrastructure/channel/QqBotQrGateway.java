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
    /** taskId → 已完成的绑定快照，用于重复/并发轮询返回同一 bindingId。 */
    private final Map<String, CompletedTask> completedByTaskId = new ConcurrentHashMap<>();

    private record CompletedTask(String ownerUserId, Long bindingId) {
    }

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
    public ChannelQrGateway.PollOutcome poll(String ownerUserId, String taskId) {
        if (ownerUserId == null || ownerUserId.isBlank() || taskId == null || taskId.isBlank()) {
            return ChannelQrGateway.PollOutcome.expired("扫码任务已失效，请重新生成");
        }
        // 归属判定在调用远程之前完成：只有任务发起者能推进状态。
        String knownOwner = ownerByTaskId.get(taskId);
        CompletedTask completed = completedByTaskId.get(taskId);
        if (knownOwner == null && completed == null) {
            return ChannelQrGateway.PollOutcome.expired("扫码任务已失效，请重新生成");
        }
        if (completed != null) {
            return ownerUserId.equals(completed.ownerUserId())
                    ? ChannelQrGateway.PollOutcome.completed(completed.bindingId())
                    : ChannelQrGateway.PollOutcome.expired("扫码任务已失效，请重新生成");
        }
        if (!ownerUserId.equals(knownOwner)) {
            return ChannelQrGateway.PollOutcome.expired("扫码任务已失效，请重新生成");
        }

        QqBotOnboardClient.PollResult result = onboardClient.pollBindResult(taskId);
        switch (result.status()) {
            case COMPLETED -> {
                // 并发 COMPLETED：同一任务只允许一次落库，其余轮询复用同一个 bindingId。
                synchronized (this) {
                    CompletedTask done = completedByTaskId.get(taskId);
                    if (done != null) {
                        return ownerUserId.equals(done.ownerUserId())
                                ? ChannelQrGateway.PollOutcome.completed(done.bindingId())
                                : ChannelQrGateway.PollOutcome.expired("扫码任务已失效，请重新生成");
                    }
                    String bindKey = bindKeyByTaskId.remove(taskId);
                    String owner = ownerByTaskId.remove(taskId);
                    if (bindKey == null || owner == null || !ownerUserId.equals(owner)) {
                        return ChannelQrGateway.PollOutcome.expired("扫码任务已失效，请重新生成");
                    }
                    String secret = QqBotCrypto.decryptSecret(result.encryptedSecret(), bindKey);
                    ChannelBinding binding = saveBinding(owner, result.botAppId(), secret, result.userOpenid());
                    completedByTaskId.put(taskId, new CompletedTask(owner, binding.getId()));
                    return ChannelQrGateway.PollOutcome.completed(binding.getId());
                }
            }
            case EXPIRED -> {
                bindKeyByTaskId.remove(taskId);
                ownerByTaskId.remove(taskId);
                completedByTaskId.remove(taskId);
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
