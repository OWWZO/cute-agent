package org.wwz.ai.domain.agent.runtime.artifact;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 工作区相对路径安全校验。
 * <p>
 * 只接受「可证明落在工作区根之内」的相对路径：绝对路径、UNC、Windows 盘符、
 * 带协议/ADS 的冒号、{@code ..} 段、波浪号前缀与 NUL 一律拒绝。
 * 拒绝时返回空串（fail-closed），由调用方按缺省值处理，不把可疑路径继续向下游传递。
 */
public final class WorkspaceRelativePath {

    private WorkspaceRelativePath() {
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.indexOf('\0') >= 0) {
            return "";
        }
        String path = raw.replace('\\', '/').trim();
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty()) {
            return "";
        }
        // 绝对路径 / UNC：归一化后仍以 / 开头说明不是工作区相对路径。
        if (path.startsWith("/")) {
            return "";
        }
        // Windows 盘符（C:foo、C:/foo）与带协议、NTFS ADS 的冒号。
        if (path.indexOf(':') >= 0) {
            return "";
        }
        if (path.startsWith("~")) {
            return "";
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return "";
            }
        }
        try {
            if (Path.of(path).isAbsolute()) {
                return "";
            }
        } catch (InvalidPathException e) {
            return "";
        }
        return path;
    }

    /** @return true 表示该值可安全用作工作区相对路径。 */
    public static boolean isSafe(String raw) {
        return !sanitize(raw).isEmpty();
    }
}
