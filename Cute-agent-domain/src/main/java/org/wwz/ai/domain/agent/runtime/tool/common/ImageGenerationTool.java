package org.wwz.ai.domain.agent.runtime.tool.common;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.util.StringUtil;
import org.wwz.ai.domain.agent.image.model.ImageGenerationExecuteCommand;
import org.wwz.ai.domain.agent.image.model.ImageGenerationExecutionResult;
import org.wwz.ai.domain.agent.image.model.WorkspaceImageFile;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ImageGenerationToolOutput;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolFileRefMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Base64;
import java.util.stream.Collectors;

/**
 * 图片生成工具，负责把文生图 / 图生图请求转发到 cute-tool。
 */
@Slf4j
public class ImageGenerationTool implements BaseTool {

    private static final String DESCRIPTION = "本工具用于文生图和图生图。用户要求基于已有图片修改、换风格、扩图时应优先调用它；此时必须显式传 fileNames 指定参考图（工作区相对路径或 http(s) URL）。未传 fileNames 时按文生图处理，不会自动复用上传图片。输出图片会自动保存为当前会话产物。";
    private static final String PARAMS = """
            {"type":"object","properties":{"prompt":{"type":"string","description":"图片生成或编辑指令，需要明确主体、风格、构图、质感及修改要求。"},"mode":{"type":"string","enum":["images","edits"],"description":"生成模式，images 表示文生图，edits 表示图生图。用户明确要求忽略已有图片时传 images。"},"fileNames":{"type":"array","items":{"type":"string"},"description":"图生图参考图片列表，可填工作区相对路径（如 cat.png、output/cat.png）或 http(s) URL；不传则不做图生图。"},"maskFileNames":{"type":"array","items":{"type":"string"},"description":"可选的涂抹参考图列表，与 fileNames 一一对应。"},"size":{"type":"string","description":"输出尺寸，例如 1024x1024、1536x1024。"},"n":{"type":"integer","description":"期望生成的图片数量。"}},"required":["prompt"]}
            """;

    private static final String MODE_IMAGES = "images";
    private static final String MODE_EDITS = "edits";
    private static final int DEFAULT_TIMEOUT_SECONDS = 900;

    @Override
    public String getName() {
        return "image_generation_tool";
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public Map<String, Object> toParams() {
        return JSON.parseObject(PARAMS);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Object input, AgentContext context) {
        try {
            // 工具调用分为四个阶段：校验 prompt、解析当前会话中的输入图片引用、
            // 委托执行内核生成产物、再把同一批文件分别登记到 artifact 和 SSE 文件事件。
            // typed output 最后返回给模型/回放链路，三种输出必须使用同一执行结果。
            Map<String, Object> params = (Map<String, Object>) input;
            String prompt = StringUtils.trimToEmpty(valueAsString(params.get("prompt")));
            if (StringUtils.isBlank(prompt)) {
                return buildFailurePayload("image_generation_tool 执行失败：prompt 不能为空。");
            }

            String mode = StringUtils.trimToEmpty(valueAsString(params.get("mode")));
            List<String> fileNames = resolveImageReferences(toStringList(params.get("fileNames")), context);
            List<String> maskFileNames = resolveImageReferences(toStringList(params.get("maskFileNames")), context);
            ToolArtifactSource artifactSource = context.requireCurrentToolArtifactSource(getName());
            ImageGenerationExecutionResult result = context.requireRuntimeDependencies().requireImageGenerationExecutionKernel().execute(ImageGenerationExecuteCommand.builder()
                    // 图片产物目录按 session 归档，和其他工具保持一致，便于会话内统一查看文件。
                    .requestId(context.getSessionId())
                    .prompt(prompt)
                    .mode(StringUtils.isBlank(mode) ? null : mode)
                    .fileNames(fileNames)
                    .maskFileNames(maskFileNames)
                    .fileName(resolveOutputFileName(params.get("fileName")))
                    .fileDescription(resolveOutputDescription(params.get("fileDescription"), prompt))
                    .size(StringUtils.trimToNull(valueAsString(params.get("size"))))
                    .n(resolveInteger(params.get("n"), 1))
                    .timeoutSeconds(DEFAULT_TIMEOUT_SECONDS)
                    .build());
            appendGeneratedArtifacts(result, artifactSource, context);
            emitFileMessage(result, artifactSource, context);
            return buildSuccessPayload(result);
        } catch (Exception e) {
            // context 为 null 时 requireKernel 抛的是 IllegalStateException，catch 内不能再二次 NPE
            log.error("{} image_generation_tool error, input={}",
                    context == null ? "unknown" : context.getRequestId(), input, e);
            return buildFailurePayload("image_generation_tool 执行失败：" + e.getMessage());
        }
    }

    private void appendGeneratedArtifacts(ImageGenerationExecutionResult result, ToolArtifactSource artifactSource, AgentContext context) {
        // artifact registry 是 ledger 可追踪文件引用的入口；这里登记的是生成结果，
        // 不是模型原始 URL，后续历史回放和前端预览都依赖这一稳定引用。
        if (result == null || CollectionUtils.isEmpty(result.getFiles())) {
            return;
        }
        for (WorkspaceImageFile fileInfo : result.getFiles()) {
            File file = File.builder()
                    .fileName(fileInfo.getFileName())
                    .fileSize(fileInfo.getFileSize() == null ? null : Math.toIntExact(fileInfo.getFileSize()))
                    .ossUrl(fileInfo.getOssUrl())
                    .domainUrl(fileInfo.getDomainUrl())
                    .description(result.getSummary())
                    .isInternalFile(false)
                    .build();
            context.registerGeneratedArtifact(artifactSource, file);
        }
    }

    private void emitFileMessage(ImageGenerationExecutionResult result, ToolArtifactSource artifactSource, AgentContext context) {
        // SSE 文件事件服务于当前轮即时展示，不能替代 artifact 持久化；因此即使
        // 前端事件发送失败，typed tool result 仍由 execute 的返回值负责闭环。
        if (result == null || CollectionUtils.isEmpty(result.getFiles())) {
            return;
        }
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("command", "生成图片");
        resultMap.put("fileInfo", result.getFiles());
        if (artifactSource != null) {
            resultMap.put("toolCallId", artifactSource.getToolCallId());
            resultMap.put("toolName", artifactSource.getToolName());
        }
        String messageId = StringUtil.getUUID();
        String digitalEmployee = context.getToolCollection().getDigitalEmployee(getName());
        context.getPrinter().send(messageId, "file", resultMap, digitalEmployee, true);
    }

    /**
     * 把 Agent 给的图片引用归一为下游可直接消费的字符串，按输入形态分流两条路径。
     * <ul>
     *   <li>{@code http(s)://} 与 {@code data:}：原样透传，由下游 {@code loadImageReference}
     *       的对应分支处理——网络 URL 走 HTTP GET 下载后转 data URL。</li>
     *   <li>其余视为工作区相对路径：{@code Files.readAllBytes} 直读本地字节转 data URL，
     *       跳过 preview URL 那趟自调用 HTTP。</li>
     * </ul>
     * 解析失败（文件不存在 / 路径越界 / 绝对路径）时保留原值，交由下游报出明确错误。
     */
    private List<String> resolveImageReferences(List<String> refs, AgentContext context) {
        if (refs == null || refs.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> resolved = new ArrayList<>(refs.size());
        for (String ref : refs) {
            String value = StringUtils.trimToEmpty(ref);
            if (StringUtils.isBlank(value)) {
                continue;
            }
            if (looksLikeDirectUrl(value)) {
                resolved.add(value);
                continue;
            }
            String dataUrl = readWorkspaceImageAsDataUrl(context, value);
            resolved.add(StringUtils.isNotBlank(dataUrl) ? dataUrl : value);
        }
        return resolved;
    }

    /**
     * 读工作区内图片文件并转 data URL。
     * <p>只接受工作区相对路径：绝对路径、越界路径（含 {@code ../}）一律返回 null，
     * 避免 Agent 借引用读到工作区外的任意文件。相对路径可含子目录（{@code output/cat.png}），
     * 不做 basename 截断。</p>
     */
    private String readWorkspaceImageAsDataUrl(AgentContext context, String relativePath) {
        try {
            Path root = resolveWorkspaceRoot(context);
            if (root == null || StringUtils.isBlank(relativePath)) {
                return null;
            }
            Path candidate = Path.of(relativePath.trim());
            if (candidate.isAbsolute()) {
                return null;
            }
            Path file = root.resolve(candidate).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            String mime = Files.probeContentType(file);
            return "data:" + (StringUtils.isBlank(mime) ? "image/png" : mime) + ";base64,"
                    + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.warn("read workspace image failed sessionId={} path={}",
                    context == null ? "unknown" : context.getSessionId(), relativePath, e);
            return null;
        }
    }

    /** 解析并规范化工作区根；未配置或目录不存在时返回 null。 */
    private Path resolveWorkspaceRoot(AgentContext context) {
        if (context == null || StringUtils.isBlank(context.getWorkspaceRoot())) {
            return null;
        }
        Path root = Path.of(context.getWorkspaceRoot().trim()).toAbsolutePath().normalize();
        return Files.isDirectory(root) ? root : null;
    }

    private boolean looksLikeDirectUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("data:")
                || lower.contains("/preview/")
                || lower.contains("/download/");
    }

    private String resolveOutputFileName(Object rawValue) {
        String fileName = StringUtil.removeSpecialChars(StringUtils.trimToEmpty(valueAsString(rawValue)));
        if (StringUtils.isBlank(fileName)) {
            return "图片生成结果";
        }
        return fileName;
    }

    private String resolveOutputDescription(Object rawValue, String prompt) {
        String description = StringUtils.trimToEmpty(valueAsString(rawValue));
        if (StringUtils.isNotBlank(description)) {
            return description;
        }
        return StringUtils.abbreviate(prompt, 80);
    }

    private List<String> toStringList(Object rawValue) {
        if (rawValue == null) {
            return new ArrayList<>();
        }
        if (rawValue instanceof List<?> rawList) {
            return rawList.stream()
                    .map(this::valueAsString)
                    .filter(StringUtils::isNotBlank)
                    .map(StringUtils::trim)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        String text = valueAsString(rawValue);
        if (StringUtils.isBlank(text)) {
            return new ArrayList<>();
        }
        return Arrays.stream(text.split("[,，]"))
                .map(StringUtils::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Integer resolveInteger(Object rawValue, int defaultValue) {
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        String text = valueAsString(rawValue);
        if (StringUtils.isBlank(text)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String valueAsString(Object rawValue) {
        return rawValue == null ? null : String.valueOf(rawValue);
    }

    /**
     * 生图结果需要同时保留 prompt、摘要和图片文件引用，便于后续 replay 还原。
     */
    private ToolResultPayload buildSuccessPayload(ImageGenerationExecutionResult result) {
        // 返回结构同时保留摘要、生成参数和稳定文件引用：摘要给模型继续推理，
        // fileRefs/produced_files 给 ledger、replay 及 document_generate 等下游复用。
        String summary = normalizeSummary(result);
        ImageGenerationToolOutput structuredOutput = ImageGenerationToolOutput.builder()
                .prompt(result.getPrompt())
                .mode(result.getMode())
                .summary(summary)
                .size(result.getSize())
                .batchCount(result.getBatchCount())
                .sourceImageCount(result.getSourceImageCount())
                .maskImageCount(result.getMaskImageCount())
                .usedFallback(result.getUsedFallback())
                .fileRefs(ToolFileRefMapper.fromWorkspaceImageFiles(result.getFiles()))
                .build();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", "image_generation");
        data.put("ok", Boolean.TRUE);
        data.put("summary", summary);
        data.put("prompt", result.getPrompt());
        data.put("mode", result.getMode());
        data.put("size", result.getSize());
        if (result.getBatchCount() != null) {
            data.put("batchCount", result.getBatchCount());
        }
        if (!CollectionUtils.isEmpty(result.getFiles())) {
            List<Map<String, Object>> produced = new ArrayList<>();
            for (WorkspaceImageFile file : result.getFiles()) {
                if (file == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file_name", file.getFileName());
                row.put("mimeType", file.getMimeType());
                String url = StringUtil.firstNonBlank(
                        file.getPreviewUrl(), file.getDomainUrl(), file.getDownloadUrl(), file.getOssUrl());
                if (StringUtils.isNotBlank(url)) {
                    row.put("url", url);
                }
                produced.add(row);
            }
            data.put("produced_files", produced);
            data.put("hint", "Use produced_files.url as image.url for document_generate when needed.");
        }
        return ToolResultPayload.fromData(data, structuredOutput);
    }

    /**
     * 图片生成失败时返回最小 typed output，避免 rich tool 退化成纯字符串。
     */
    private ToolResultPayload buildFailurePayload(String message) {
        return ToolResultPayload.failure(
                message,
                message,
                ImageGenerationToolOutput.builder()
                        .summary(message)
                        .build(),
                message
        );
    }

    private String normalizeSummary(ImageGenerationExecutionResult result) {
        String summary = result == null ? null : StringUtils.trimToNull(result.getSummary());
        if (result != null && !CollectionUtils.isEmpty(result.getFiles())) {
            String fileNames = result.getFiles().stream()
                    .map(WorkspaceImageFile::getFileName)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining("、"));
            if (StringUtils.isBlank(summary)) {
                summary = "已生成图片文件：" + fileNames;
            }
        }
        return StringUtils.defaultIfBlank(summary, "image_generation_tool 执行完成");
    }

    private Map<String, Object> buildStringParam(String description) {
        Map<String, Object> param = new HashMap<>();
        param.put("type", "string");
        param.put("description", description);
        return param;
    }

    private Map<String, Object> buildIntegerParam(String description) {
        Map<String, Object> param = new HashMap<>();
        param.put("type", "integer");
        param.put("description", description);
        return param;
    }

    private Map<String, Object> buildEnumParam(String description, List<String> enumValues) {
        Map<String, Object> param = buildStringParam(description);
        param.put("enum", enumValues);
        return param;
    }

    private Map<String, Object> buildStringArrayParam(String description) {
        Map<String, Object> param = new HashMap<>();
        param.put("type", "array");
        param.put("description", description);

        Map<String, Object> items = new HashMap<>();
        items.put("type", "string");
        param.put("items", items);
        return param;
    }

}
