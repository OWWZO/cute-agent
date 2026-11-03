package org.wwz.ai.domain.agent.memory.ltm;

/**
 * 策展记忆归属类型。
 * <p>常量名通过 {@code name()} 持久化到 {@code ltm_curated_entry.owner_type} 并参与查询，
 * 同时用于构造 owner key（{@link LtmOwner#asOwnerKey()}），禁止重命名或删除已有常量。</p>
 */
public enum LtmOwnerType {
    /** 用户级隔离：同一 userId 跨会话共享。 */
    USER,
    /** ERP 标识：与 userId 属于不同身份来源，owner key 必须区分，避免同值串记忆。 */
    ERP,
    /** 会话级隔离：userId 与 ERP 都无法解析时的兜底，记忆不跨会话共享。 */
    SESSION
}
