package org.wwz.ai.domain.agent.runtime.tool.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.adapter.port.FileArtifactPort;
import org.wwz.ai.domain.agent.runtime.artifact.WorkspaceRelativePath;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionFile;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 将会话上传附件物化到 workspace 本地目录，使 agent 能通过 workspace_* 工具读取。
 * 失败只记日志，不阻断主链路。
 * <p>
 * 必须按二进制下载写入：xlsx/pdf/图片等不能用 UTF-8 文本读写，否则 ZIP 包会损坏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceSessionFileMaterializer {

    private static final long READ_TIMEOUT_SECONDS = 60L;
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    private final WorkspaceService workspaceService;
    private final FileArtifactPort fileArtifactPort;

    public List<String> materialize(AgentContext agentContext, List<AgentExecutionFile> sessionFiles) {
        List<String> written = new ArrayList<>();
        if (agentContext == null || !workspaceService.isEnabled()) {
            return written;
        }
        String workspaceRootText = agentContext.getWorkspaceRoot();
        if (StringUtils.isBlank(workspaceRootText)) {
            return written;
        }
        if (sessionFiles == null || sessionFiles.isEmpty()) {
            return written;
        }

        Path workspaceRoot = Path.of(workspaceRootText).toAbsolutePath().normalize();
        try {
            Files.createDirectories(workspaceRoot);
        } catch (Exception e) {
            log.warn("{} create workspace root failed: {}", agentContext.getRequestId(), workspaceRoot, e);
            return written;
        }

        Set<String> usedPaths = new HashSet<>();
        for (AgentExecutionFile sessionFile : sessionFiles) {
            if (sessionFile == null) {
                continue;
            }
            String relativePath = resolveSafeRelativePath(agentContext, sessionFile, usedPaths);
            if (relativePath == null) {
                continue;
            }
            String sourceUrl = firstNonBlank(
                    sessionFile.getOssUrl(),
                    sessionFile.getOriginOssUrl(),
                    sessionFile.getDomainUrl(),
                    sessionFile.getOriginDomainUrl(),
                    sessionFile.getOriginFileUrl()
            );
            if (StringUtils.isBlank(sourceUrl)) {
                log.info("{} skip materialize without url, relativePath={}",
                        agentContext.getRequestId(), relativePath);
                continue;
            }
            try {
                // 始终二进制下载，避免 xlsx/pdf/png 被当文本 UTF-8 转码损坏
                // relativePath 是逻辑身份；目录层级必须保留，否则 HTML/CSS 相对引用会失效。
                byte[] bytes = fileArtifactPort.readBytes(sourceUrl, READ_TIMEOUT_SECONDS, MAX_FILE_BYTES);
                if (bytes == null || bytes.length == 0) {
                    log.warn("{} materialize empty content, relativePath={}",
                            agentContext.getRequestId(), relativePath);
                    continue;
                }
                if (bytes.length > MAX_FILE_BYTES) {
                    log.warn("{} materialize skip oversized file, relativePath={}, size={}",
                            agentContext.getRequestId(), relativePath, bytes.length);
                    continue;
                }
                Path target = workspaceRoot.resolve(relativePath).normalize();
                if (!target.startsWith(workspaceRoot)) {
                    log.warn("{} reject materialize path outside workspace, relativePath={}",
                            agentContext.getRequestId(), relativePath);
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try {
                    Files.write(target, bytes);
                    written.add(relativePath);
                    usedPaths.add(relativePath.toLowerCase(Locale.ROOT));
                    log.info("{} materialize session file ok, relativePath={}, bytes={}, magic={}",
                            agentContext.getRequestId(), relativePath, bytes.length, magicHint(bytes));
                } catch (Exception writeFailure) {
                    Files.deleteIfExists(target);
                    throw writeFailure;
                }
            } catch (Exception e) {
                log.warn("{} materialize session file failed, relativePath={}",
                        agentContext.getRequestId(), relativePath, e);
            }
        }

        return written;
    }

    private String resolveSafeRelativePath(AgentContext agentContext,
                                           AgentExecutionFile sessionFile,
                                           Set<String> usedPaths) {
        String explicitRelativePath = StringUtils.trimToNull(sessionFile.getRelativePath());
        if (explicitRelativePath != null) {
            String normalized = normalizeRelativePath(explicitRelativePath);
            if (normalized == null) {
                log.warn("{} reject invalid relativePath={}",
                        agentContext.getRequestId(), explicitRelativePath);
                return null;
            }
            return claimMaterializePath(agentContext, normalized, usedPaths);
        }

        // 兼容旧数据：originFileName 只有含目录时才优先于展示用 fileName。
        String originPath = StringUtils.trimToNull(sessionFile.getOriginFileName());
        if (originPath != null && hasDirectory(originPath)) {
            String normalized = normalizeRelativePath(originPath);
            if (normalized != null) {
                return claimMaterializePath(agentContext, normalized, usedPaths);
            }
            // originFileName 是旧协议里的展示元数据，可能含浏览器伪路径；非法时只降级，
            // 不能让一个仅用于展示的字段阻断 fileName 的兼容物化。
            log.warn("{} ignore invalid legacy originFileName path={}",
                    agentContext.getRequestId(), originPath);
        }

        String fallback = firstNonBlank(sessionFile.getFileName(), originPath, "session-file.txt");
        String normalized = normalizeRelativePath(fallback);
        if (normalized == null) {
            log.warn("{} reject invalid fallback file path={}",
                    agentContext.getRequestId(), fallback);
            return null;
        }
        if (!hasDirectory(normalized)) {
            log.warn("{} materialize legacy basename without relativePath, fileName={}",
                    agentContext.getRequestId(), normalized);
        }
        return claimMaterializePath(agentContext, normalized, usedPaths);
    }

    private String claimMaterializePath(AgentContext agentContext,
                                        String relativePath,
                                        Set<String> usedPaths) {
        String identity = relativePath.toLowerCase(Locale.ROOT);
        if (usedPaths.contains(identity)) {
            log.warn("{} duplicate relativePath in one materialize batch, relativePath={}",
                    agentContext.getRequestId(), relativePath);
            return null;
        }
        return relativePath;
    }

    private String normalizeRelativePath(String raw) {
        String safe = WorkspaceRelativePath.sanitize(raw);
        if (StringUtils.isBlank(safe)) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String part : safe.split("/")) {
            String segment = part.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                return null;
            }
            segments.add(segment);
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    private boolean hasDirectory(String path) {
        return path.indexOf('/') >= 0 || path.indexOf('\\') >= 0;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String magicHint(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "empty";
        }
        int n = Math.min(4, bytes.length);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}
