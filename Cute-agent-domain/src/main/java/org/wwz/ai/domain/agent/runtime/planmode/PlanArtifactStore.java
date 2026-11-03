package org.wwz.ai.domain.agent.runtime.planmode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * 会话计划落盘。
 * 路径：{workspaceRoot}/.cute/plan.md
 * <p>除了词法上的 {@code ..} 校验，读写前还会解析真实路径并拒绝符号链接：
 * 否则 {@code .cute} 或 {@code plan.md} 指向工作区外时，plan mode 的「唯一可写文件」
 * 会变成任意文件覆写。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanArtifactStore {

    public static final String RELATIVE_PLAN_PATH = ".cute/plan.md";

    private final WorkspaceService workspaceService;

    public Path resolvePlanPath(String sessionId) {
        if (workspaceService == null || !workspaceService.isEnabled()) {
            return null;
        }
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        try {
            Path root = workspaceService.resolveAndEnsureRoot(sessionId).toAbsolutePath().normalize();
            Path plan = root.resolve(RELATIVE_PLAN_PATH).normalize();
            if (!plan.startsWith(root)) {
                return null;
            }
            return plan;
        } catch (Exception e) {
            log.warn("resolve plan path failed, sessionId={}", sessionId, e);
            return null;
        }
    }

    public Optional<String> writePlan(String sessionId, String content) {
        Path path = resolvePlanPath(sessionId);
        if (path == null) {
            return Optional.empty();
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 创建目录后再校验一次：防止 .cute 在解析与写入之间被替换成符号链接。
            if (!isSafePlanFile(path)) {
                log.warn("write plan rejected: plan path escapes workspace, sessionId={}", sessionId);
                return Optional.empty();
            }
            Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            return Optional.of(path.toString());
        } catch (Exception e) {
            log.warn("write plan failed, sessionId={}", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * 读取计划正文。
     * <p>返回空可能表示「未生成过计划」或「读取失败（I/O/权限）」；两种情况的区分只体现在
     * 服务端 warn 日志里（带 sessionId），调用方无法据此给出更精确的提示。</p>
     */
    public Optional<String> readPlan(String sessionId) {
        Path path = resolvePlanPath(sessionId);
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !isSafePlanFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("read plan failed, sessionId={}", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * 计划文件必须真实落在工作区内：拒绝符号链接，且已存在部分的真实路径不得逃逸。
     */
    private boolean isSafePlanFile(Path plan) {
        Path parent = plan.getParent();
        Path root = parent == null ? null : parent.getParent();
        if (root == null) {
            return false;
        }
        try {
            Path realRoot = root.toRealPath();
            if (Files.isSymbolicLink(parent)) {
                return false;
            }
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                Path realParent = parent.toRealPath();
                if (!realParent.startsWith(realRoot)) {
                    return false;
                }
            }
            if (Files.isSymbolicLink(plan)) {
                return false;
            }
            if (Files.exists(plan, LinkOption.NOFOLLOW_LINKS)) {
                Path realPlan = plan.toRealPath();
                if (!realPlan.startsWith(realRoot)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
