package org.wwz.ai.domain.agent.memory.ltm;

import lombok.Value;

import java.util.Locale;

/**
 * 策展记忆主身份键。
 * <p>id 只做 trim、区分大小写；调用方必须先把用户标识规范化到同一形态，
 * 否则 {@code user("Alice")} 与 {@code user("alice")} 会分裂成两个 owner。</p>
 */
@Value
public class LtmOwner {
    LtmOwnerType type;
    String id;

    public static LtmOwner of(LtmOwnerType type, String id) {
        if (type == null) {
            throw new IllegalArgumentException("owner type required");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("owner id required");
        }
        return new LtmOwner(type, id.trim());
    }

    public static LtmOwner user(String userId) {
        return of(LtmOwnerType.USER, userId);
    }

    /** ERP 身份单独成类：{@code user:<id>} 与 {@code erp:<id>} 不再映射到同一个 owner key。 */
    public static LtmOwner erp(String erp) {
        return of(LtmOwnerType.ERP, erp);
    }

    /** 无用户身份时的会话级兜底，避免所有匿名调用共享同一个全局 owner。 */
    public static LtmOwner session(String sessionId) {
        return of(LtmOwnerType.SESSION, sessionId);
    }

    public String asOwnerKey() {
        return type.name().toLowerCase(Locale.ROOT) + ":" + id;
    }
}
