package org.wwz.ai.domain.agent.memory.ltm;

/**
 * 策展记忆归属类型（用户级隔离）。
 */
public enum LtmOwnerType {
    USER,
    /** ERP 标识：与 userId 属于不同身份来源，owner key 必须区分，避免同值串记忆。 */
    ERP,
    SESSION
}
