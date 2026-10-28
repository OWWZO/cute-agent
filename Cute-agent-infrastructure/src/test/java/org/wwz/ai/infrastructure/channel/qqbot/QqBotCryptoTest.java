package org.wwz.ai.infrastructure.channel.qqbot;

import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QqBotCryptoTest {

    /** 与 Hermes 相同的布局：IV(12) ‖ ciphertext ‖ tag(16)。 */
    private static String encrypt(String plaintext, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] raw = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, raw, 0, iv.length);
        System.arraycopy(sealed, 0, raw, iv.length, sealed.length);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    public void decryptsLayoutUsedByQqPortal() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        byte[] iv = new byte[12];
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) (0xA0 + i);
        }
        String keyBase64 = Base64.getEncoder().encodeToString(key);
        String encrypted = encrypt("s3cr3t-app-secret", key, iv);

        assertEquals("s3cr3t-app-secret", QqBotCrypto.decryptSecret(encrypted, keyBase64));
    }

    @Test
    public void generatedBindKeyIs256BitBase64() {
        String key = QqBotCrypto.generateBindKey();
        assertEquals(32, Base64.getDecoder().decode(key).length);
        assertNotEquals(key, QqBotCrypto.generateBindKey());
    }

    @Test
    public void wrongKeyFailsInsteadOfReturningGarbage() throws Exception {
        byte[] key = new byte[32];
        byte[] other = new byte[32];
        other[0] = 1;
        String encrypted = encrypt("secret", key, new byte[12]);
        try {
            QqBotCrypto.decryptSecret(encrypted, Base64.getEncoder().encodeToString(other));
            fail("错误密钥必须解密失败");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("解密失败"));
        }
    }

    @Test
    public void truncatedCiphertextIsRejected() {
        try {
            QqBotCrypto.decryptSecret(Base64.getEncoder().encodeToString(new byte[8]),
                    QqBotCrypto.generateBindKey());
            fail("过短的密文必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("密文长度异常"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankInputIsRejected() {
        QqBotCrypto.decryptSecret("", QqBotCrypto.generateBindKey());
    }
}
