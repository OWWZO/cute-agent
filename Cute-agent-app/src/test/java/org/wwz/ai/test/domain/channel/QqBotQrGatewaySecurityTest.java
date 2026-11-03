package org.wwz.ai.test.domain.channel;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.port.ChannelBindingRepository;
import org.wwz.ai.domain.agent.channel.port.ChannelQrGateway;
import org.wwz.ai.infrastructure.channel.QqBotQrGateway;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotCrypto;
import org.wwz.ai.infrastructure.channel.qqbot.QqBotOnboardClient;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫码网关安全回归：修复前 poll(taskId) 不校验调用方身份，且重复 COMPLETED 轮询会返回「已失效」。
 */
public class QqBotQrGatewaySecurityTest {

    private static final String TASK_ID = "task-sec-1";
    private static final String OWNER = "owner-1";

    @Test
    public void pollRejectsUnknownTaskWithoutCallingRemote() {
        QqBotOnboardClient onboard = Mockito.mock(QqBotOnboardClient.class);
        QqBotQrGateway gateway = new QqBotQrGateway(onboard, Mockito.mock(ChannelBindingRepository.class));

        ChannelQrGateway.PollOutcome outcome = gateway.poll(OWNER, "task-unknown");

        Assert.assertEquals(ChannelQrGateway.Status.EXPIRED, outcome.status());
        Mockito.verifyNoInteractions(onboard);
    }

    @Test
    public void pollRejectsNonOwnerWithoutCallingRemote() throws Exception {
        QqBotOnboardClient onboard = Mockito.mock(QqBotOnboardClient.class);
        QqBotQrGateway gateway = new QqBotQrGateway(onboard, Mockito.mock(ChannelBindingRepository.class));
        seedTask(gateway, QqBotCrypto.generateBindKey(), OWNER);

        ChannelQrGateway.PollOutcome outcome = gateway.poll("attacker", TASK_ID);

        Assert.assertEquals(ChannelQrGateway.Status.EXPIRED, outcome.status());
        Mockito.verifyNoInteractions(onboard);
    }

    @Test
    public void repeatedCompletedPollReturnsSameBindingAndInsertsOnce() throws Exception {
        String bindKey = QqBotCrypto.generateBindKey();
        String encrypted = encryptSecret("app-secret", bindKey);
        QqBotOnboardClient onboard = Mockito.mock(QqBotOnboardClient.class);
        Mockito.when(onboard.pollBindResult(TASK_ID)).thenReturn(new QqBotOnboardClient.PollResult(
                QqBotOnboardClient.BindStatus.COMPLETED, "app-1", encrypted, "openid-1"));
        ChannelBindingRepository repository = Mockito.mock(ChannelBindingRepository.class);
        Mockito.when(repository.findByOwner(OWNER, ChannelBinding.PLATFORM_QQBOT))
                .thenReturn(Optional.empty());
        Mockito.doAnswer(invocation -> {
            ChannelBinding binding = invocation.getArgument(0);
            binding.setId(77L);
            return null;
        }).when(repository).insert(Mockito.any());

        QqBotQrGateway gateway = new QqBotQrGateway(onboard, repository);
        seedTask(gateway, bindKey, OWNER);

        ChannelQrGateway.PollOutcome first = gateway.poll(OWNER, TASK_ID);
        ChannelQrGateway.PollOutcome second = gateway.poll(OWNER, TASK_ID);

        Assert.assertEquals(ChannelQrGateway.Status.COMPLETED, first.status());
        Assert.assertEquals(ChannelQrGateway.Status.COMPLETED, second.status());
        Assert.assertEquals(first.bindingId(), second.bindingId());
        Mockito.verify(repository, Mockito.times(1)).insert(Mockito.any());
        // 非 owner 在任务完成后也不能拿到 bindingId。
        Assert.assertEquals(ChannelQrGateway.Status.EXPIRED, gateway.poll("attacker", TASK_ID).status());
    }

    @SuppressWarnings("unchecked")
    private void seedTask(QqBotQrGateway gateway, String bindKey, String owner) throws Exception {
        Field bindKeyField = QqBotQrGateway.class.getDeclaredField("bindKeyByTaskId");
        bindKeyField.setAccessible(true);
        ((Map<String, String>) bindKeyField.get(gateway)).put(TASK_ID, bindKey);

        Field ownerField = QqBotQrGateway.class.getDeclaredField("ownerByTaskId");
        ownerField.setAccessible(true);
        ((Map<String, String>) ownerField.get(gateway)).put(TASK_ID, owner);
    }

    private String encryptSecret(String secret, String bindKeyBase64) throws Exception {
        byte[] key = Base64.getDecoder().decode(bindKeyBase64);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        byte[] raw = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, raw, 0, iv.length);
        System.arraycopy(ciphertext, 0, raw, iv.length, ciphertext.length);
        return Base64.getEncoder().encodeToString(raw);
    }
}
