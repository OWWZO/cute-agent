package org.wwz.ai.domain.agent.channel.policy;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内部 user_id 生成规则：{@code ch_{bindingId}_{sha256(openid) 前 16 位}}。
 * <p>确定性生成的好处是可以从 (bindingId, openid) 直接算出 userId，不必先查表；
 * 前缀带 bindingId 让同一 QQ 用户在不同 bot 下拿到不同身份，与「openid 按 bot 隔离」一致。</p>
 */
public final class ChannelUserId {

    private static final int HASH_PREFIX_LENGTH = 16;

    private ChannelUserId() {
    }

    public static String of(Long bindingId, String platformUserId) {
        if (bindingId == null) {
            throw new IllegalArgumentException("bindingId 不能为空");
        }
        if (StringUtils.isBlank(platformUserId)) {
            throw new IllegalArgumentException("platformUserId 不能为空");
        }
        return "ch_" + bindingId + "_" + sha256Hex(platformUserId.trim()).substring(0, HASH_PREFIX_LENGTH);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境异常。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
