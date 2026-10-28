package org.wwz.ai.infrastructure.channel.qqbot;

import org.apache.commons.lang3.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 扫码绑定的 AES-256-GCM 工具。
 * <p>流程：本地生成 32 字节随机 key 交给 {@code create_bind_task}，QQ 用它加密 bot 的
 * client_secret；扫码完成后本地解密。密钥始终只在本地，secret 不以明文经过网络。</p>
 */
public final class QqBotCrypto {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private QqBotCrypto() {
    }

    /** 生成 base64 编码的 256 位 AES key。 */
    public static String generateBindKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * 解密 {@code bot_encrypt_secret}。
     * <p>密文布局：{@code IV(12B) ‖ ciphertext ‖ AuthTag(16B)}，AAD 为空。</p>
     */
    public static String decryptSecret(String encryptedBase64, String keyBase64) {
        if (StringUtils.isAnyBlank(encryptedBase64, keyBase64)) {
            throw new IllegalArgumentException("密文与密钥不能为空");
        }
        try {
            byte[] key = Base64.getDecoder().decode(keyBase64);
            byte[] raw = Base64.getDecoder().decode(encryptedBase64);
            if (raw.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度异常：" + raw.length);
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            byte[] ciphertextWithTag = new byte[raw.length - IV_LENGTH];
            System.arraycopy(raw, IV_LENGTH, ciphertextWithTag, 0, ciphertextWithTag.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertextWithTag);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // 解密失败通常意味着 key 与 task 不匹配（例如任务被重建但复用了旧 key）。
            throw new IllegalStateException("AppSecret 解密失败", e);
        }
    }
}
