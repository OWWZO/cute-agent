package org.wwz.ai.domain.agent.runtime.tool.skill;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * Skill 路径边界校验。
 * <p>
 * 两道防线：<b>词法</b>（normalize + startsWith）与<b>真实路径</b>（toRealPath，
 * 拦截符号链接 / Windows junction 逃逸）。
 */
@Component
public class SkillPathGuard {

    /** Windows 盘符前缀，例如 {@code C:foo}、{@code C:\foo}。 */
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");

    public Path ensureUnderRoot(Path rootPath, Path candidatePath) {
        Path normalizedRoot = rootPath.toAbsolutePath().normalize();
        Path normalizedCandidate = candidatePath.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedRoot)) {
            // 异常文本会回给模型/日志：只说明越界，不暴露具体服务器路径。
            throw new SkillLoadException("path escapes registered skill root");
        }
        // 词法检查通过后，再比对真实路径，防止 root 内放一个指向外部的符号链接。
        // 目标尚不存在时也要判定：取最近的已存在祖先解析真实路径，再把缺失段拼回去；
        // 解析失败一律拒绝，不能退回纯词法结果（否则父目录软链接可绕过）。
        Path realRoot = resolveRealPath(normalizedRoot);
        Path realCandidate = resolveRealPath(normalizedCandidate);
        if (realRoot == null || realCandidate == null || !realCandidate.startsWith(realRoot)) {
            // 异常文本会回给模型/日志：不嵌入候选路径，避免泄漏服务器目录结构。
            throw new SkillLoadException("path escapes registered skill root via symlink");
        }
        return normalizedCandidate;
    }

    /**
     * 判断相对路径是否包含穿越/绝对路径/特殊前缀。
     * 无法解析的路径（{@link InvalidPathException}）一律视为非法。
     * <p>返回 {@code true} 的含义是「输入不安全、调用方应拒绝」，不限于出现 {@code ..}；
     * null/空白/UNC/盘符/波浪号等都会返回 true。</p>
     */
    public boolean hasTraversal(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return true;
        }
        String normalized = relativePath.trim().replace('\\', '/');
        if (normalized.startsWith("/")) {
            // 同时拦下 Windows（\\server\share → //server/share）与 Unix（//server/share）UNC 前缀。
            return true;
        }
        if (normalized.startsWith("~")) {
            return true;
        }
        if (WINDOWS_DRIVE.matcher(normalized).matches()) {
            return true;
        }
        if (normalized.contains(":")) {
            return true;
        }
        Path path;
        try {
            path = Path.of(normalized);
        } catch (InvalidPathException e) {
            return true;
        }
        if (path.isAbsolute()) {
            return true;
        }
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析真实路径：目标不存在时取最近的已存在祖先做 {@link Path#toRealPath()}，
     * 再把尚不存在的尾段拼回去，保证父目录链路里的软链接同样被解析。
     * 任何一步失败返回 null，由调用方拒绝。
     */
    private static Path resolveRealPath(Path path) {
        Path existing = path;
        Deque<Path> missingTail = new ArrayDeque<>();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path fileName = existing.getFileName();
            if (fileName == null) {
                return null;
            }
            missingTail.addFirst(fileName);
            existing = existing.getParent();
        }
        if (existing == null) {
            return null;
        }
        try {
            Path real = existing.toRealPath();
            for (Path fileName : missingTail) {
                real = real.resolve(fileName);
            }
            return real;
        } catch (IOException e) {
            return null;
        }
    }
}
