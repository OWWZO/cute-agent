package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.AgentBrowserSession;
import org.wwz.ai.domain.agent.catalog.model.AiClientApiConfig;
import org.wwz.ai.domain.agent.catalog.model.AiClientToolMcpConfig;
import org.wwz.ai.domain.agent.channel.model.ChannelBinding;
import org.wwz.ai.domain.agent.channel.model.ChannelIdentity;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunStartRecord;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolFileRef;
import org.wwz.ai.domain.agent.memory.ToolCallMemory;
import org.wwz.ai.domain.agent.runtime.agent.ToolExecutionOutcome;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionFile;
import org.wwz.ai.domain.agent.runtime.dto.FileRequest;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.llm.LLMSettings;
import org.wwz.ai.domain.agent.runtime.llm.LlmModelBinding;
import org.wwz.ai.domain.agent.runtime.planmode.PlanApprovalRecord;

/**
 * 敏感字段不得通过 Lombok 生成的 toString 泄漏。
 * <p>覆盖批次 SA 中「密钥 / PII 进 toString」的各类模型；断言直接针对攻击面：
 * 把含特征的 secret 放进字段，toString 里不得出现。</p>
 */
public class SensitiveToStringTest {

    private static final String SECRET = "sk-live-SECRET-123456";
    private static final String OPENID = "qq-openid-SECRET";
    private static final String USER_TEXT = "用户隐私原文-SECRET";

    @Test
    public void browserSessionHidesCdpToken() {
        AgentBrowserSession session = AgentBrowserSession.builder()
                .userId("u1")
                .agentSessionId("s1")
                .agentBrowserName("chrome")
                .cdpWsUrl("wss://cdp.example/devtools/browser/" + SECRET)
                .build();
        Assert.assertFalse(session.toString().contains(SECRET));
    }

    @Test
    public void apiConfigHidesApiKey() {
        AiClientApiConfig config = AiClientApiConfig.builder()
                .apiId("api-1")
                .baseUrl("https://example.com")
                .apiKey(SECRET)
                .build();
        Assert.assertFalse(config.toString().contains(SECRET));
    }

    @Test
    public void mcpConfigHidesTransportConfig() {
        AiClientToolMcpConfig config = AiClientToolMcpConfig.builder()
                .mcpId("mcp-1")
                .transportType("stdio")
                .transportConfig("{\"env\":{\"TOKEN\":\"" + SECRET + "\"}}")
                .build();
        Assert.assertFalse(config.toString().contains(SECRET));
    }

    @Test
    public void channelBindingHidesAndDoesNotSerializeSecret() {
        ChannelBinding binding = ChannelBinding.builder()
                .id(1L)
                .ownerUserId("u1")
                .platform(ChannelBinding.PLATFORM_QQBOT)
                .appId("app-1")
                .appSecret(SECRET)
                .status(ChannelBinding.STATUS_ACTIVE)
                .build();

        Assert.assertFalse(binding.toString().contains(SECRET));
        Assert.assertFalse(JSON.toJSONString(binding).contains(SECRET));

        ChannelBinding rotated = ChannelBinding.builder()
                .id(1L)
                .ownerUserId("u1")
                .platform(ChannelBinding.PLATFORM_QQBOT)
                .appId("app-1")
                .appSecret("rotated-secret")
                .status(ChannelBinding.STATUS_ACTIVE)
                .build();
        Assert.assertEquals("轮换 secret 不应改变对象身份", binding, rotated);
    }

    @Test
    public void channelIdentityHidesPlatformPii() {
        ChannelIdentity identity = ChannelIdentity.builder()
                .bindingId(1L)
                .platform("qqbot")
                .platformUserId(OPENID)
                .userId("ch_1")
                .displayName("用户昵称-" + SECRET)
                .build();
        String text = identity.toString();
        Assert.assertFalse(text.contains(OPENID));
        Assert.assertFalse(text.contains(SECRET));
    }

    @Test
    public void dialogueRunStartHidesQueryText() {
        DialogueRunStartRecord record = DialogueRunStartRecord.builder()
                .requestId("r1")
                .queryText(USER_TEXT)
                .build();
        Assert.assertFalse(record.toString().contains(USER_TEXT));
    }

    @Test
    public void toolFileRefHidesSignedUrls() {
        ToolFileRef ref = ToolFileRef.builder()
                .fileName("report.pdf")
                .downloadUrl("https://oss.example/report.pdf?sign=" + SECRET)
                .previewUrl("https://cdn.example/report.pdf?sign=" + SECRET)
                .ossUrl("oss://bucket/" + SECRET)
                .domainUrl("https://domain.example/" + SECRET)
                .build();
        Assert.assertFalse(ref.toString().contains(SECRET));
    }

    @Test
    public void toolCallMemoryHidesToolPayload() {
        ToolCallMemory memory = ToolCallMemory.builder()
                .toolName("bash")
                .inputJson("{\"command\":\"export TOKEN=" + SECRET + "\"}")
                .llmObservation(USER_TEXT)
                .build();
        Assert.assertFalse(memory.toString().contains(SECRET));
        Assert.assertFalse(memory.toString().contains(USER_TEXT));
    }

    @Test
    public void toolExecutionOutcomeHidesBase64Payload() {
        ToolExecutionOutcome outcome = new ToolExecutionOutcome()
                .setSuccess(true)
                .setToolResult("ok")
                .setBase64Image(SECRET);
        Assert.assertFalse(outcome.toString().contains(SECRET));
    }

    @Test
    public void executionFileHidesSignedUrls() {
        AgentExecutionFile file = AgentExecutionFile.builder()
                .fileName("a.png")
                .ossUrl("https://oss.example/a.png?sign=" + SECRET)
                .domainUrl("https://cdn.example/a.png?sign=" + SECRET)
                .originFileUrl("https://origin.example/" + SECRET)
                .originOssUrl("https://origin-oss.example/" + SECRET)
                .originDomainUrl("https://origin-cdn.example/" + SECRET)
                .build();
        Assert.assertFalse(file.toString().contains(SECRET));
    }

    @Test
    public void fileRequestHidesContentAndLocalPath() {
        FileRequest request = FileRequest.builder()
                .requestId("r1")
                .fileName("a.txt")
                .content("content-" + SECRET)
                .localPath("/srv/secret-dir/" + SECRET)
                .build();
        String text = request.toString();
        Assert.assertFalse(text.contains(SECRET));
        Assert.assertFalse("绝对路径不应进日志", text.contains("/srv/secret-dir"));
    }

    @Test
    public void messageHidesContentAndBase64Image() {
        Message message = Message.builder()
                .content(USER_TEXT)
                .reasoningContent(USER_TEXT)
                .base64Image(SECRET)
                .build();
        Assert.assertFalse(message.toString().contains(USER_TEXT));
        Assert.assertFalse(message.toString().contains(SECRET));
    }

    @Test
    public void llmSettingsHidesApiKeyButKeepsJsonContract() {
        LLMSettings settings = LLMSettings.builder()
                .model("m1")
                .baseUrl("https://example.com")
                .apiKey(SECRET)
                .build();
        Assert.assertFalse(settings.toString().contains(SECRET));
        Assert.assertTrue("JSON 字段名 apikey 是对外契约", JSON.toJSONString(settings).contains("\"apikey\""));
    }

    @Test
    public void modelBindingHidesApiKey() {
        LlmModelBinding binding = LlmModelBinding.builder()
                .modelId("m1")
                .modelName("gpt")
                .baseUrl("https://example.com")
                .apiKey(SECRET)
                .build();
        Assert.assertFalse(binding.toString().contains(SECRET));
    }

    @Test
    public void planApprovalRecordHidesPlanAndResumeContext() {
        PlanApprovalRecord record = PlanApprovalRecord.builder()
                .approvalId("a1")
                .planContent("计划正文-" + SECRET)
                .resumeContextJson("{\"token\":\"" + SECRET + "\"}")
                .build();
        Assert.assertFalse(record.toString().contains(SECRET));
    }
}
