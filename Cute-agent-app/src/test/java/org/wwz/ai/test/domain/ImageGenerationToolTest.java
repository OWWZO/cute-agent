package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.types.agent.config.ImageGenerationProperties;
import org.wwz.ai.domain.agent.runtime.tool.common.ImageGenerationTool;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ImageGenerationToolOutput;
import org.wwz.ai.domain.agent.image.model.ImageGenerationExecuteCommand;
import org.wwz.ai.domain.agent.image.model.ImageGenerationExecutionResult;
import org.wwz.ai.domain.agent.image.model.WorkspaceImageFile;
import org.wwz.ai.domain.agent.image.service.IImageGenerationExecutionKernel;
import org.wwz.ai.test.domain.support.CuteRuntimeTestSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * image_generation_tool typed output 回归。
 */
public class ImageGenerationToolTest {

    @Test
    public void shouldUseSharedKernelAndReturnRichStructuredOutput() {
        IImageGenerationExecutionKernel kernel = Mockito.mock(IImageGenerationExecutionKernel.class);
        ArgumentCaptor<ImageGenerationExecuteCommand> commandCaptor = ArgumentCaptor.forClass(ImageGenerationExecuteCommand.class);
        Mockito.when(kernel.execute(commandCaptor.capture())).thenReturn(ImageGenerationExecutionResult.builder()
                .requestId("session-image-001")
                .prompt("生成活动海报")
                .mode("images")
                .summary("已生成图片文件：poster.png")
                .size("1536x1024")
                .batchCount(2)
                .sourceImageCount(0)
                .maskImageCount(0)
                .usedFallback(true)
                .files(List.of(WorkspaceImageFile.builder()
                        .fileName("poster.png")
                        .ossUrl("https://file.example.com/poster.png")
                        .domainUrl("https://file.example.com/preview/poster.png")
                        .downloadUrl("https://file.example.com/poster.png")
                        .previewUrl("https://file.example.com/preview/poster.png")
                        .fileSize(256L)
                        .mimeType("image/png")
                        .build()))
                .build());

        CuteConfig cuteConfig = buildConfig();
        RecordingPrinter printer = new RecordingPrinter();
        ToolCollection toolCollection = new ToolCollection();
        AgentContext context = AgentContext.builder()
                .requestId("req-image-001")
                .sessionId("session-image-001")
                .query("生成活动海报")
                .isStream(true)
                .printer(printer)
                .toolCollection(toolCollection)
                .runtimeDependencies(CuteRuntimeTestSupport.runtimeDependencies(cuteConfig, kernel, new org.springframework.mock.env.MockEnvironment(), null, null, buildImageGenerationProperties()))
                .build();
        toolCollection.setAgentContext(context);

        ImageGenerationTool tool = new ImageGenerationTool();
        Assert.assertFalse(tool.toParams().containsKey("model"));
        ToolArtifactSource artifactSource = ToolArtifactSource.builder()
                .sessionId(context.getSessionId())
                .requestId(context.getRequestId())
                .toolCallId("call-image-001")
                .toolName("image_generation_tool")
                .build();

        ToolResultPayload payload;
        context.bindCurrentToolArtifactSource(artifactSource);
        try {
            payload = (ToolResultPayload) tool.execute(JSONObject.parseObject("""
                    {"prompt":"生成活动海报","n":2,"size":"1536x1024"}
                    """), context);
        } finally {
            context.clearCurrentToolArtifactSource();
        }

        ImageGenerationToolOutput structuredOutput = (ImageGenerationToolOutput) payload.getStructuredOutput();
        Assert.assertTrue(String.valueOf(payload.getLlmData()).contains("poster.png"));
        Assert.assertNull(payload.getLlmObservation());
        Assert.assertNotNull(structuredOutput);
        Assert.assertFalse(payload.getFailed());
        Assert.assertEquals("生成活动海报", structuredOutput.getPrompt());
        Assert.assertEquals("1536x1024", structuredOutput.getSize());
        Assert.assertEquals(Integer.valueOf(2), structuredOutput.getBatchCount());
        Assert.assertTrue(structuredOutput.getUsedFallback());
        Assert.assertFalse(structuredOutput.getFileRefs().isEmpty());
        Assert.assertEquals(List.of("file"), printer.messageTypes());
        Assert.assertTrue(printer.lastMessage() instanceof Map<?, ?>);
        Map<?, ?> fileMessage = (Map<?, ?>) printer.lastMessage();
        Assert.assertEquals("call-image-001", fileMessage.get("toolCallId"));
        Assert.assertEquals("image_generation_tool", fileMessage.get("toolName"));
        Assert.assertEquals(1, context.getVisibleArtifactFiles().size());
        Assert.assertEquals("poster.png", context.getVisibleArtifactFiles().get(0).getFileName());

        ImageGenerationExecuteCommand command = commandCaptor.getValue();
        Assert.assertEquals("session-image-001", command.getRequestId());
        Assert.assertNull(command.getModel());
        Assert.assertEquals(Integer.valueOf(900), command.getTimeoutSeconds());
        Assert.assertEquals(Integer.valueOf(2), command.getN());
    }

    @Test
    public void shouldReadWorkspaceRelativePathAsDataUrl() throws Exception {
        Path workspace = Files.createTempDirectory("cute-image-ws-root");
        Files.writeString(workspace.resolve("cat.png"), "cat-bytes");

        List<String> resolved = executeCapturing(List.of("cat.png"), "req-image-ws-1", "session-image-ws-1",
                workspace.toString()).getFileNames();

        Assert.assertEquals(1, resolved.size());
        Assert.assertTrue(resolved.get(0).startsWith("data:image/png;base64,"));
        Assert.assertEquals(
                Base64.getEncoder().encodeToString("cat-bytes".getBytes(StandardCharsets.UTF_8)),
                StringUtils.substringAfter(resolved.get(0), "base64,")
        );
    }

    @Test
    public void shouldKeepSubDirectoryPathWhenReadingWorkspaceImage() throws Exception {
        Path workspace = Files.createTempDirectory("cute-image-ws-subdir");
        Files.createDirectories(workspace.resolve("output"));
        Files.writeString(workspace.resolve("cat.png"), "root-image");
        Files.writeString(workspace.resolve("output/cat.png"), "subdir-image");

        List<String> resolved = executeCapturing(List.of("output/cat.png"), "req-image-ws-2", "session-image-ws-2",
                workspace.toString()).getFileNames();

        Assert.assertEquals(1, resolved.size());
        Assert.assertEquals(
                Base64.getEncoder().encodeToString("subdir-image".getBytes(StandardCharsets.UTF_8)),
                StringUtils.substringAfter(resolved.get(0), "base64,")
        );
    }

    @Test
    public void shouldRejectPathTraversalAndAbsolutePathFromImageReference() throws Exception {
        // 越界与绝对路径都走不到本地读取，最终保留原值交给下游报错
        for (String reference : List.of("../../etc/passwd", "/etc/passwd")) {
            List<String> resolved = executeCapturing(List.of(reference), "req-image-ws-3", "session-image-ws-3", null)
                    .getFileNames();
            Assert.assertEquals(List.of(reference), resolved);
        }
    }

    @Test
    public void shouldPassThroughNetworkUrlAndDataUrlUnchanged() throws Exception {
        List<String> refs = List.of(
                "https://example.com/a.png",
                "data:image/png;base64,xxx"
        );
        Assert.assertEquals(refs,
                executeCapturing(refs, "req-image-ws-4", "session-image-ws-4", null).getFileNames());
    }

    @Test
    public void shouldKeepOriginalValueWhenWorkspaceImageMissing() throws Exception {
        // 工作区内不存在：保留原值，由下游报出明确错误，不静默吞掉
        Assert.assertEquals(List.of("missing.png"),
                executeCapturing(List.of("missing.png"), "req-image-ws-5", "session-image-ws-5", null).getFileNames());
    }

    /** 以给定引用执行工具，捕获发给下游内核的命令；workspaceRoot 为 null 时取临时目录。 */
    private ImageGenerationExecuteCommand executeCapturing(List<String> references,
                                                           String requestId,
                                                           String sessionId,
                                                           String workspaceRootOverride) throws Exception {
        IImageGenerationExecutionKernel kernel = Mockito.mock(IImageGenerationExecutionKernel.class);
        ArgumentCaptor<ImageGenerationExecuteCommand> commandCaptor = ArgumentCaptor.forClass(ImageGenerationExecuteCommand.class);
        Mockito.when(kernel.execute(commandCaptor.capture())).thenReturn(ImageGenerationExecutionResult.builder()
                .requestId(sessionId)
                .prompt("图片编辑")
                .mode("edits")
                .summary("图片编辑完成")
                .batchCount(1)
                .usedFallback(false)
                .files(List.of(WorkspaceImageFile.builder()
                        .fileName("edited.png")
                        .domainUrl("https://file.example.com/preview/edited.png")
                        .downloadUrl("https://file.example.com/download/edited.png")
                        .fileSize(128L)
                        .mimeType("image/png")
                        .build()))
                .build());

        CuteConfig cuteConfig = buildConfig();
        RecordingPrinter printer = new RecordingPrinter();
        ToolCollection toolCollection = new ToolCollection();
        String workspaceRoot = workspaceRootOverride == null
                ? Files.createTempDirectory("cute-image-ws").toString()
                : workspaceRootOverride;
        AgentContext context = AgentContext.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .query("图片编辑")
                .isStream(true)
                .printer(printer)
                .toolCollection(toolCollection)
                .workspaceRoot(workspaceRoot)
                .runtimeDependencies(CuteRuntimeTestSupport.runtimeDependencies(cuteConfig, kernel, new org.springframework.mock.env.MockEnvironment(), null, null, buildImageGenerationProperties()))
                .build();
        toolCollection.setAgentContext(context);

        context.bindCurrentToolArtifactSource(ToolArtifactSource.builder()
                .sessionId(sessionId)
                .requestId(requestId)
                .toolCallId("call-" + requestId)
                .toolName("image_generation_tool")
                .build());
        try {
            JSONObject params = new JSONObject();
            params.put("prompt", "图片编辑");
            params.put("fileNames", references);
            Assert.assertFalse(((ToolResultPayload) new ImageGenerationTool().execute(params, context)).getFailed());
        } finally {
            context.clearCurrentToolArtifactSource();
        }
        return commandCaptor.getValue();
    }

    private CuteConfig buildConfig() {
        return new CuteConfig();
    }

    /** image_* 已从 CuteConfig 拆到 ImageGenerationProperties；codeInterpreterUrl 仍在 CuteConfig。 */
    private static ImageGenerationProperties buildImageGenerationProperties() {
        return ImageGenerationProperties.builder()
                .baseUrl("https://image-provider.example")
                .apiKey("test-key")
                .model("gpt-image-2.5-flare")
                .build();
    }

    private static class RecordingPrinter implements Printer {
        private final List<String> messageTypes = new ArrayList<>();
        private final AtomicReference<Object> lastMessage = new AtomicReference<>();

        @Override
        public void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal) {
            messageTypes.add(messageType);
            lastMessage.set(message);
        }

        @Override
        public void send(String messageId, String messageType, Object message, Map<String, Object> extraResultMap, String digitalEmployee, Boolean isFinal) {
            messageTypes.add(messageType);
            lastMessage.set(message);
        }

        @Override
        public void send(String messageType, Object message) {
            messageTypes.add(messageType);
            lastMessage.set(message);
        }

        @Override
        public void send(String messageType, Object message, String digitalEmployee) {
            messageTypes.add(messageType);
            lastMessage.set(message);
        }

        @Override
        public void send(String messageId, String messageType, Object message, Boolean isFinal) {
            messageTypes.add(messageType);
            lastMessage.set(message);
        }

        @Override
        public void sendWithResultMap(String messageId, String messageType, Object message, Map<String, Object> extraResultMap, Boolean isFinal) {
            messageTypes.add(messageType);
            lastMessage.set(message);
        }

        @Override
        public void sendWithResultMap(String messageType, Object message, Map<String, Object> extraResultMap) {
            messageTypes.add(messageType);
            lastMessage.set(message);
        }

        @Override
        public void close() {
        }

        @Override
        public void updateAgentType(org.wwz.ai.domain.agent.runtime.enums.AgentType agentType) {
        }

        private List<String> messageTypes() {
            return messageTypes;
        }

        private Object lastMessage() {
            return lastMessage.get();
        }
    }
}
