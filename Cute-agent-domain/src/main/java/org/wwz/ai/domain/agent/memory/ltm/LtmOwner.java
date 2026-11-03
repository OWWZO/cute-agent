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

    public String asOwnerKey() {
        return type.name().toLowerCase(Locale.ROOT) + ":" + id;
    }
}
