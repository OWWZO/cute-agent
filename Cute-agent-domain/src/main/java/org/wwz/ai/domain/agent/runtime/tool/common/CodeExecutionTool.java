package org.wwz.ai.domain.agent.runtime.tool.common;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwner;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwnerResolver;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.CodeInterpreterResponse;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspacePaths;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.security.OutboundUrlGuard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 调用侧 Agent 直接执行 Python 源码，复用受控 code_execution Runner。 */
@Slf4j
public class CodeExecutionTool implements BaseTool {

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");

    @Override public String getName() { return "code_execution"; }

    @Override public String getDescription() {
        return "直接在受控 Python 沙箱执行源码。用于计算、数据处理、图表及用户可下载文件生成。\n"
                + "沙箱 cwd 为当前会话工作区（与 bash 相同）；skills/ 在该目录下。"
                + "用 Path / open / savefig 等相对路径读写即可，"
                + "例如 Path('chinagt-shanghai-fire-report/index.html').read_text(encoding='utf-8')；"
                + "plt.savefig('chart.png')；df.to_excel('结果.xlsx')。\n"
                + "本次新增或修改的工作区文件会自动采集上传，前端可预览/下载。\n"
                + "结果里的 produced_files[].url 可直接作为 document_generate 的 image.url。\n"
                + "禁止写死宿主绝对路径。fileNames 仅用于公网 http(s) URL 或会话工作区内路径；"
                + "工作区外绝对路径与内网地址会被拒绝。";
    }

    @Override public Map<String, Object> toParams() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("source", Map.of(
                "type", "string",
                "description", "完整 Python 源码。cwd 为会话工作区；生成文件用相对路径，例如 Path('chart.png')、"
                        + "plt.savefig('chart.png')。禁止硬编码 skilloutput/盘符绝对路径。"
                        + "与 workspaceFile 二选一：至少提供一个。"
        ));
        Map<String, Object> freeformObject = new LinkedHashMap<>();
        freeformObject.put("type", "object");
        freeformObject.put("properties", new LinkedHashMap<String, Object>());
        freeformObject.put("required", List.of());

        Map<String, Object> inputs = new LinkedHashMap<>(freeformObject);
        inputs.put("description", "注入 Python 全局变量的 JSON 对象");
        properties.put("inputs", inputs);
        properties.put("fileNames", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "可选。仅填公网 http(s) URL 或会话工作区内路径，系统会下载/定位到会话工作区；"
                        + "工作区外绝对路径和内网地址会被拒绝。"
                        + "工作区已有文件不要填这里，源码里用相对路径 Path/open 直接读。"
        ));
        Map<String, Object> fileItem = new LinkedHashMap<>(freeformObject);
        fileItem.put("description", "工作区文件条目");
        properties.put("files", Map.of("type", "array", "items", fileItem, "description", "写入工作区的小型文本或 Base64 文件"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 600));
        properties.put("memoryBytes", Map.of("type", "integer", "description", "可选内存上限（字节）"));
        properties.put("importTier", Map.of("type", "string", "enum", List.of("stdlib", "extended", "unrestricted")));
        properties.put("resetWorkspace", Map.of("type", "boolean"));
        properties.put("workspaceFile", Map.of("type", "string", "description", "工作区内已有 Python 源码文件，提供时优先执行它"));
        return Map.of("type", "object", "properties", properties, "required", List.of());
    }

    @Override @SuppressWarnings("unchecked")
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> params = input instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            String source = params.get("source") == null ? "" : StringUtils.trimToEmpty(String.valueOf(params.get("source")));
            String workspaceFileArg = stringValue(params.get("workspaceFile"));
            if (source.isBlank() && StringUtils.isBlank(workspaceFileArg)) {
                return ToolResultPayload.failure(
                        "source 与 workspaceFile 必须提供一个",
                        "source 与 workspaceFile 必须提供一个",
                        null,
                        "missing source");
            }
            ToolArtifactSource artifactSource = context.requireCurrentToolArtifactSource(getName());
            CuteConfig config = context.getRuntimeDependencies().requireCuteConfig();
            String ownerKey = resolveOwnerKey(context);
            if (ownerKey == null) {
                return ToolResultPayload.failureFrom("缺少用户身份，无法执行代码", null);
            }
            Path workspaceRoot = WorkspacePaths.resolveSandboxRoot(
                    context.getWorkspaceRoot(), context.getSessionId());
            Map<String, Object> request = new LinkedHashMap<>(params);
            request.put("fileNames", validateFileNames(toStringList(params.get("fileNames")), workspaceRoot));
            String workspaceFile = validateWorkspaceFile(workspaceFileArg);
            if (workspaceFile != null) {
                request.put("workspaceFile", workspaceFile);
            } else {
                request.remove("workspaceFile");
            }
            request.put("requestId", context.getSessionId());
            request.put("sessionId", context.getSessionId());
            request.put("ownerKey", ownerKey);
            request.put("workspaceRoot", workspaceRoot.toString());
            request.put("permissionProfile", "workspace");
            if (source.isBlank()) {
                // workspaceFile 模式下不伪造空源码；远端按文件执行。
                request.remove("source");
            } else {
                request.put("source", source);
            }
            // 远端执行统一绑定当前会话工作区；返回文件再登记到当前 tool artifact source，形成可回放的产物链。
            String body = context.getRuntimeDependencies().requireRemoteHttpPort().execute(RemoteHttpRequest.builder()
                    .method("POST").url(config.getCodeInterpreterUrl() + "/v1/tool/code_execution")
                    .headers(Map.of("Content-Type", "application/json")).body(JSON.toJSONString(request))
                    .connectTimeoutSeconds(60L).readTimeoutSeconds(660L).writeTimeoutSeconds(60L).callTimeoutSeconds(660L).build());
            JSONObject response = JSON.parseObject(body);
            List<CodeInterpreterResponse.FileInfo> fileInfo = JSON.parseArray(response.getString("fileInfo"), CodeInterpreterResponse.FileInfo.class);
            if (fileInfo == null) fileInfo = List.of();
            for (CodeInterpreterResponse.FileInfo info : fileInfo) {
                // 只登记执行服务明确返回的文件，不扫描整个工作区，避免把历史产物误归入本次调用。
                context.registerGeneratedArtifact(artifactSource, File.builder().fileName(info.getFileName())
                        .ossUrl(info.getOssUrl()).domainUrl(info.getDomainUrl()).fileSize(info.getFileSize())
                        .description(info.getFileName()).isInternalFile(false).build());
            }
            if (!fileInfo.isEmpty() && context.getPrinter() != null) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("command", "Python 代码执行产物"); event.put("toolCallId", artifactSource.getToolCallId());
                event.put("toolName", artifactSource.getToolName());
                event.put("requestId", context.getSessionId());
                event.put("sessionId", context.getSessionId());
                event.put("fileInfo", fileInfo);
                context.getPrinter().send("file", event, null);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tool", "code_execution");
            data.put("status", response.getString("status"));
            data.put("stdout", StringUtils.defaultString(response.getString("stdout")));
            data.put("stderr", StringUtils.defaultString(response.getString("stderr")));
            if (response.get("result") != null) {
                data.put("result", response.get("result"));
            }
            if (StringUtils.isNotBlank(response.getString("error"))) {
                data.put("error", response.getString("error"));
            }
            if (!fileInfo.isEmpty()) {
                List<Map<String, Object>> files = new java.util.ArrayList<>();
                for (CodeInterpreterResponse.FileInfo info : fileInfo) {
                    if (info == null) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("file_name", info.getFileName());
                    String url = StringUtils.firstNonBlank(info.getDomainUrl(), info.getOssUrl());
                    if (StringUtils.isNotBlank(url)) {
                        row.put("url", url);
                    }
                    if (info.getFileSize() != null) {
                        row.put("file_size", info.getFileSize());
                    }
                    files.add(row);
                }
                data.put("produced_files", files);
            }
            if (!"ok".equals(response.getString("status"))) {
                // stdout/stderr/result 仍保留在失败 payload 中，模型需要这些上下文才能决定是否重试。
                data.put("ok", Boolean.FALSE);
                return ToolResultPayload.failureFrom(
                        StringUtils.defaultIfBlank(response.getString("error"), "code_execution failed"),
                        data
                );
            }
            data.put("ok", Boolean.TRUE);
            return ToolResultPayload.fromData(data);
        } catch (Exception e) {
            log.error("{} code_execution failed", context == null ? "unknown" : context.getRequestId(), e);
            return ToolResultPayload.failureFrom("code_execution 执行失败：" + e.getMessage(), null);
        }
    }

    /**
     * fileNames 只允许公网 http(s) URL 或会话工作区内路径。
     * <p>URL 由 {@link OutboundUrlGuard} 解析真实 IP 后判定地址类别；绝对路径必须落在会话工作区内，
     * 防止把宿主任意文件拉进工作区再回传。</p>
     */
    static List<String> validateFileNames(List<String> raw, Path workspaceRoot) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Math.max(0, 1);
        List<String> validated = new ArrayList<>(raw.size());
        for (String value : raw) {
            String name = StringUtils.trimToEmpty(value);
            if (name.isEmpty()) {
                continue;
            }
            if (name.startsWith("http://") || name.startsWith("https://")) {
                OutboundUrlGuard.validateHttpUrl(name);
                validated.add(name);
                continue;
            }
            validated.add(validateWorkspacePath(name, workspaceRoot.toAbsolutePath().normalize()));
        }
        return validated;
    }

    /** workspaceFile 必须是工作区内相对路径，拒绝绝对路径、盘符与 {@code ..} 穿越。 */
    static String validateWorkspaceFile(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return validateWorkspacePath(raw.trim(), null);
    }

    private static String validateWorkspacePath(String raw, Path workspaceRoot) {
        String normalized = raw.replace('\\', '/').trim();
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.startsWith("~")
                || WINDOWS_DRIVE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("不允许工作区外绝对路径: " + raw);
        }
        Path relative = Path.of(normalized).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalArgumentException("路径不能逃逸会话工作区: " + raw);
        }
        if (workspaceRoot == null) {
            return relative.toString().replace('\\', '/');
        }
        Path target = workspaceRoot.resolve(relative).normalize();
        if (!target.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("路径不能逃逸会话工作区: " + raw);
        }
        return relative.toString().replace('\\', '/');
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String resolveOwnerKey(AgentContext context) {
        LtmOwner owner = context.getLtmOwner();
        if (owner == null) {
            owner = LtmOwnerResolver.resolve(context.getUserId(), null, context.getSessionId());
        }
        return owner == null ? null : owner.asOwnerKey();
    }
}
