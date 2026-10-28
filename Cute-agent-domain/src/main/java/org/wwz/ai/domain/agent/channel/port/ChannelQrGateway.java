package org.wwz.ai.domain.agent.channel.port;

/**
 * 扫码协议端口，由 infrastructure 实现（需要 QQ 开放平台客户端与 AES 解密）。
 * <p>实现方按 taskId 保管 bindKey，并在 COMPLETED 时完成「解密 secret → 落库绑定」，
 * 把新绑定 id 返回给状态机。</p>
 */
public interface ChannelQrGateway {

    enum Status {
        PENDING,
        COMPLETED,
        EXPIRED
    }

    /**
     * @param bindingId COMPLETED 时非空
     * @param error     EXPIRED 时给用户看的说明
     */
    record PollOutcome(Status status, Long bindingId, String error) {

        public static PollOutcome pending() {
            return new PollOutcome(Status.PENDING, null, null);
        }

        public static PollOutcome completed(Long bindingId) {
            return new PollOutcome(Status.COMPLETED, bindingId, null);
        }

        public static PollOutcome expired(String error) {
            return new PollOutcome(Status.EXPIRED, null, error);
        }
    }

    /** 创建 QQ 绑定任务并记住 owner，返回 task_id。 */
    String createTask(String ownerUserId);

    /** 轮询一次；COMPLETED 时必须已完成落库并返回 bindingId。 */
    PollOutcome poll(String taskId);

    /** 二维码内容 URL。 */
    String buildQrUrl(String taskId);
}
