package org.wwz.ai.domain.agent.runtime.tool.skill;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 在会话工作区 cwd 下执行 shell 命令（<b>没有沙箱隔离</b>）。
 *
 * <p><b>信任边界：</b>命令以宿主用户的完整权限运行，{@code workspaceRoot} 只是初始工作目录，
 * 不是安全边界；进程可以读写工作区之外的任意路径、访问网络并修改系统状态。因此只能用于
 * 受信任的本地命令，不能把模型/用户输入直接交给它。需要真正隔离时应切换到远端沙箱
 * （{@code BashTool}）或后续的 Docker 实现。</p>
 *
 * <p>当前最小护栏：内置高危命令拒绝列表 + 可配置追加模式，执行前记录审计日志。
 * 对齐参考项目 bash 工具语义：非零退出码 / 超时是业务结果（exit_code / timed_out），
 * 不是工具通道错误。真正的隔离（Docker）可在后续替换本实现而不改 BashTool 契约。</p>
 */
@Slf4j
@Component
public class WorkspaceBashExecutor {

    /** 内置拒绝项：只拦截明确可能破坏宿主的命令，不做通用 shell 解析。 */
    private static final List<Pattern> DEFAULT_DENY_PATTERNS = List.of(
            Pattern.compile("(?i)\\brm\\s+-[a-z]*(?:r[a-z]*f|f[a-z]*r)[a-z]*\\s+(?:/|--no-preserve-root|~)(?:\\s|$)"),
            Pattern.compile("(?i)\\brm\\s+-r\\s+-f\\s+(?:/|--no-preserve-root|~)(?:\\s|$)"),
            Pattern.compile("(?i)\\bmkfs(?:\\.[a-z0-9]+)?\\b"),
            Pattern.compile("(?i)\\bdd\\s+if="),
            Pattern.compile("(?i)\\b(?:shutdown|reboot|poweroff|halt)\\b"),
            Pattern.compile("(?i)\\bformat\\s+[a-z]:"),
            Pattern.compile(":\\(\\)\\s*\\{")
    );

    /**
     * Windows PowerShell 5.1 重定向输出默认按 OEM 代码页（中文系统为 GBK），
     * 直接按 UTF-8 解码会全是乱码。执行前把控制台与管道输出统一改成 UTF-8。
     */
    private static final String POWERSHELL_UTF8_PREFIX =
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                    + "$OutputEncoding = [System.Text.Encoding]::UTF8; ";
    private static final String TRUNCATION_MARKER = "\n…[输出被截断]…\n";

    private final SkillRuntimeOptions options;

    public WorkspaceBashExecutor(SkillRuntimeOptions options) {
        this.options = options;
    }

    public ExecResult exec(Path workspaceRoot, String command, long timeoutMs) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot is required");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        Path cwd = workspaceRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(cwd);
        } catch (IOException e) {
            throw new IllegalStateException("failed to ensure workspace: " + cwd, e);
        }

        String denied = deniedReason(command, options.getBashDeniedPatterns());
        if (denied != null) {
            log.warn("bash.exec denied cwd={} pattern={} command={}",
                    cwd, denied, abbreviateForLog(command));
            return ExecResult.builder()
                    .exitCode(126)
                    .stdout("")
                    .stderr("command denied by policy: " + denied)
                    .timedOut(false)
                    .durationMs(0L)
                    .build();
        }
        log.info("bash.exec start cwd={} command={}", cwd, abbreviateForLog(command));

        long effectiveTimeout = timeoutMs > 0
                ? Math.min(timeoutMs, options.getBashMaxTimeoutSec() * 1000L)
                : options.getBashTimeoutSec() * 1000L;
        if (effectiveTimeout <= 0) {
            effectiveTimeout = 120_000L;
        }

        List<String> argv = buildShellArgv(command);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        env.put("SKILL_WORKSPACE", cwd.toString());
        env.put("PYTHON", options.getRuntimePython() == null ? "python" : options.getRuntimePython());
        // Python 在 Windows 重定向输出时默认跟随区域代码页（GBK）；统一强制 UTF-8 才能与 text() 的解码一致。
        env.put("PYTHONIOENCODING", "utf-8");

        long started = System.currentTimeMillis();
        Process process = null;
        try {
            process = pb.start();
            StreamCollector stdout = new StreamCollector(process.getInputStream(), options.getBashOutputMaxChars());
            StreamCollector stderr = new StreamCollector(process.getErrorStream(), options.getBashOutputMaxChars());
            Thread outThread = new Thread(stdout, "bash-stdout");
            Thread errThread = new Thread(stderr, "bash-stderr");
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                outThread.join(2_000);
                errThread.join(2_000);
                ExecResult timeoutResult = ExecResult.builder()
                        .exitCode(null)
                        .stdout(stdout.text())
                        .stderr(merge(stderr.text(), "execution timed out after " + (effectiveTimeout / 1000) + "s"))
                        .truncated(stdout.truncated() || stderr.truncated())
                        .timedOut(true)
                        .durationMs(System.currentTimeMillis() - started)
                        .build();
                log.info("bash.exec finish cwd={} exit=timeout durationMs={}",
                        cwd, timeoutResult.getDurationMs());
                return timeoutResult;
            }
            outThread.join(2_000);
            errThread.join(2_000);
            int code = process.exitValue();
            ExecResult result = ExecResult.builder()
                    .exitCode(code)
                    .stdout(stdout.text())
                    .stderr(stderr.text())
                    .truncated(stdout.truncated() || stderr.truncated())
                    .timedOut(false)
                    .durationMs(System.currentTimeMillis() - started)
                    .build();
            log.info("bash.exec finish cwd={} exit={} durationMs={}",
                    cwd, result.getExitCode(), result.getDurationMs());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("bash interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("bash start failed: " + e.getMessage(), e);
        }
    }

    /** @return 命中的拒绝规则；未命中返回 {@code null}。 */
    static String deniedReason(String command, List<String> extraPatterns) {
        if (command == null || command.isBlank()) {
            return null;
        }
        for (Pattern pattern : DEFAULT_DENY_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return pattern.pattern();
            }
        }
        if (extraPatterns != null) {
            for (String extra : extraPatterns) {
                if (extra == null || extra.isBlank()) {
                    continue;
                }
                try {
                    if (Pattern.compile(extra, Pattern.CASE_INSENSITIVE).matcher(command).find()) {
                        return extra;
                    }
                } catch (Exception e) {
                    log.warn("invalid bash denied pattern ignored: {}", extra);
                }
            }
        }
        return null;
    }

    private static String abbreviateForLog(String command) {
        return command.length() <= 1_000 ? command : command.substring(0, 1_000) + "…";
    }

    static List<String> buildShellArgv(String command) {
        return buildShellArgv(command, System.getProperty("os.name", ""));
    }

    /** 便于跨平台单测：显式传入 os.name 判定分支。 */
    static List<String> buildShellArgv(String command, String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        List<String> argv = new ArrayList<>();
        if (os.contains("win")) {
            // Windows：优先 PowerShell（系统自带）；命令字符串按 bash 语义交给模型，脚本侧多用 python/node
            argv.add("powershell.exe");
            argv.add("-NoProfile");
            argv.add("-NonInteractive");
            argv.add("-Command");
            argv.add(POWERSHELL_UTF8_PREFIX + command);
        } else {
            argv.add("bash");
            argv.add("-lc");
            argv.add(command);
        }
        return argv;
    }

    /**
     * 保留头尾并插入截断标记。
     * <p>不能在 UTF-16 代理对中间切分，否则会留下孤立代理字符，JSON 序列化与前端渲染都会出错。</p>
     */
    static String truncateMiddle(String raw, int maxChars) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= maxChars) {
            return raw;
        }
        int headEnd = maxChars / 2;
        if (headEnd > 0 && Character.isHighSurrogate(raw.charAt(headEnd - 1))) {
            headEnd--;
        }
        int tailStart = raw.length() - (maxChars - headEnd - TRUNCATION_MARKER.length());
        if (tailStart > 0 && tailStart < raw.length() && Character.isLowSurrogate(raw.charAt(tailStart))) {
            tailStart++;
        }
        return raw.substring(0, headEnd) + TRUNCATION_MARKER + raw.substring(tailStart);
    }

    private static String merge(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + "\n" + b;
    }

    @Value
    @Builder
    public static class ExecResult {
        Integer exitCode;
        String stdout;
        String stderr;
        boolean truncated;
        boolean timedOut;
        long durationMs;
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream in;
        private final int maxChars;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean truncated;

        StreamCollector(InputStream in, int maxChars) {
            this.in = in;
            this.maxChars = Math.max(4_096, maxChars);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            int maxBytes = maxChars * 4;
            try {
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    if (buffer.size() >= maxBytes) {
                        truncated = true;
                        // drain remainder
                        while (in.read(chunk) >= 0) {
                            // discard
                        }
                        break;
                    }
                    int toWrite = Math.min(n, maxBytes - buffer.size());
                    buffer.write(chunk, 0, toWrite);
                    if (toWrite < n) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // process closed streams
            }
        }

        String text() {
            String raw = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            if (raw.length() <= maxChars) {
                return raw;
            }
            truncated = true;
            return truncateMiddle(raw, maxChars);
        }

        boolean truncated() {
            return truncated;
        }
    }
}
