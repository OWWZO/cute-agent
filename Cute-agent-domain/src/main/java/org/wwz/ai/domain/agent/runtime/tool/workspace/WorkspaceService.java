package org.wwz.ai.domain.agent.runtime.tool.workspace;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.ledger.IExecutionLedgerReadRepository;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillDefinition;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillRegistry;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillVirtualPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 解析并初始化会话工作区；路径支持虚拟 {@code skills/} → runtime skill 库。
 */
@Component
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRuntimeOptions workspaceRuntimeOptions;
    private final WorkspacePathGuard workspacePathGuard;
    private final SkillRegistry skillRegistry;
    private final SkillVirtualPaths skillVirtualPaths;

    /**
     * 血缘上溯用。用 setter 注入而非构造参数：本类有 18 个测试直接 new 4 参构造，
     * 加构造参数会让它们全部要改，而它们都不关心血缘。为 null 时退化为「自己的目录」。
     */
    private IExecutionLedgerReadRepository executionLedgerReadRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setExecutionLedgerReadRepository(IExecutionLedgerReadRepository repository) {
        this.executionLedgerReadRepository = repository;
    }

    public boolean isEnabled() {
        return workspaceRuntimeOptions != null && workspaceRuntimeOptions.isEnabled();
    }

    public Path resolveAndEnsureRoot(String sessionId) {
        Path root = resolveRoot(sessionId);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new WorkspaceAccessException("failed to create workspace root: " + root, e);
        }
        return root;
    }

    public Path resolveRoot(String sessionId) {
        if (!isEnabled()) {
            throw new WorkspaceAccessException("workspace tools are disabled");
        }
        String template = workspaceRuntimeOptions.getRootTemplate();
        if (StringUtils.isBlank(template) || isDefaultSkillOutputTemplate(template)) {
            return WorkspacePaths.skillOutputSessionRoot(sessionId);
        }
        String expanded = expandTemplate(template, sessionId);
        return Path.of(expanded).toAbsolutePath().normalize();
    }

    /**
     * 解析「该会话应使用的工作区根」，沿 {@code parent_session_id} 上溯到根。
     * <p>
     * 子会话与父共享工作区（文件双向可见）是既有设计：模式 1（父派发）由
     * {@code SubAgentContextFactory} 直接继承父的 workspaceRoot。模式 2（用户从会话树点进子会话）
     * 没有父 context，只能靠血缘查回来。
     * </p>
     * <p>上溯失败（父不存在 / 循环）时降级为「自己的目录」，不抛异常——子会话至少要能工作。</p>
     */
    public Path resolveSessionWorkspaceRoot(String sessionId) {
        return resolveAndEnsureRoot(resolveRootSessionId(sessionId));
    }

    private String resolveRootSessionId(String sessionId) {
        if (StringUtils.isBlank(sessionId) || executionLedgerReadRepository == null) {
            return sessionId;
        }
        String current = sessionId;
        Set<String> visited = new HashSet<>();
        while (StringUtils.isNotBlank(current) && visited.add(current)) {
            DialogueSession session = executionLedgerReadRepository.querySessionEntity(current);
            if (session == null) {
                return current;
            }
            if (StringUtils.isNotBlank(session.getRootSessionId())) {
                // 快速路径：root_session_id 已回填，一次查询到底
                return session.getRootSessionId();
            }
            String parent = session.getParentSessionId();
            if (StringUtils.isBlank(parent)) {
                return current;
            }
            current = parent;
        }
        return sessionId;
    }

    /**
     * 读/list/glob/grep：虚拟 skills/ 直达库；否则 workspace + 已注册 skill 真实路径。
     */
    public Path resolveAllowedPath(Path workspaceRoot, String rawPath) {
        Optional<Path> virtual = skillVirtualPaths == null
                ? Optional.empty()
                : skillVirtualPaths.tryResolve(rawPath);
        if (virtual.isPresent()) {
            return virtual.get();
        }
        Path candidate = resolveCandidate(workspaceRoot, rawPath);
        return workspacePathGuard.ensureUnderAnyRoot(listReadableRoots(workspaceRoot), candidate);
    }

    /**
     * 写/edit：虚拟 skills/ 可写全局库；否则仅会话 workspace。
     */
    public Path resolveWritablePath(Path workspaceRoot, String rawPath) {
        Optional<Path> virtual = skillVirtualPaths == null
                ? Optional.empty()
                : skillVirtualPaths.tryResolve(rawPath);
        if (virtual.isPresent()) {
            return virtual.get();
        }
        Path candidate = resolveCandidate(workspaceRoot, rawPath);
        return workspacePathGuard.ensureUnderRoot(workspaceRoot, candidate);
    }

    public List<Path> listReadableRoots(Path workspaceRoot) {
        Set<Path> roots = new LinkedHashSet<>();
        if (workspaceRoot != null) {
            roots.add(workspaceRoot.toAbsolutePath().normalize());
        }
        Path lib = skillVirtualPaths == null ? null : skillVirtualPaths.libraryRootOrNull();
        if (lib != null) {
            roots.add(lib);
        }
        if (skillRegistry != null && skillRegistry.isEnabled()) {
            for (SkillDefinition skill : skillRegistry.listSkills()) {
                if (skill == null || skill.getBasePath() == null) {
                    continue;
                }
                roots.add(skill.getBasePath().toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(roots);
    }

    /**
     * 绝对路径 → Agent 可见的虚拟/相对路径。
     * 库内 → {@code skills/...}；会话工作区内 → 相对 cwd；其它 → 仅文件名（不暴露宿主路径）。
     */
    public String toAgentVisiblePath(Path workspaceRoot, Path absolutePath) {
        if (absolutePath == null) {
            return null;
        }
        Path abs = absolutePath.toAbsolutePath().normalize();
        Path lib = skillVirtualPaths == null ? null : skillVirtualPaths.libraryRootOrNull();
        if (lib != null && abs.startsWith(lib)) {
            String rel = lib.relativize(abs).toString().replace('\\', '/');
            return rel.isEmpty() ? SkillVirtualPaths.PREFIX : SkillVirtualPaths.PREFIX_SLASH + rel;
        }
        if (workspaceRoot != null) {
            Path ws = workspaceRoot.toAbsolutePath().normalize();
            if (abs.startsWith(ws)) {
                String rel = ws.relativize(abs).toString().replace('\\', '/');
                return rel.isEmpty() ? "." : rel;
            }
        }
        Path name = abs.getFileName();
        return name == null ? "path" : name.toString();
    }

    private Path resolveCandidate(Path workspaceRoot, String rawPath) {
        if (StringUtils.isBlank(rawPath)) {
            throw new WorkspaceAccessException("path is required");
        }
        Path candidate = Path.of(rawPath.trim());
        if (!candidate.isAbsolute()) {
            if (workspaceRoot == null) {
                throw new WorkspaceAccessException("workspace root is not configured");
            }
            candidate = workspaceRoot.resolve(candidate);
        }
        return candidate;
    }

    private boolean isDefaultSkillOutputTemplate(String template) {
        String normalized = template.replace('\\', '/');
        return normalized.contains("cute-tool/skilloutput")
                || normalized.contains("{repoRoot}")
                || normalized.contains("${user.dir}");
    }

    private String expandTemplate(String template, String sessionId) {
        String safeSessionId = StringUtils.defaultIfBlank(sessionId, "anonymous")
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        String repoRoot = WorkspacePaths.resolveRepoRoot().toString();
        return template
                .replace("{sessionId}", safeSessionId)
                .replace("{repoRoot}", repoRoot)
                .replace("${user.dir}", repoRoot)
                .replace("${java.io.tmpdir}", System.getProperty("java.io.tmpdir", "."));
    }
}
