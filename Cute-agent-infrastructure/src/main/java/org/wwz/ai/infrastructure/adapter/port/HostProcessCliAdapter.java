package org.wwz.ai.infrastructure.adapter.port;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.adapter.port.cli.CliExecutionPort;
import org.wwz.ai.domain.agent.adapter.port.cli.CliInvocation;
import org.wwz.ai.domain.agent.adapter.port.cli.CliResult;
import org.wwz.ai.types.agent.config.HostCliProperties;
import org.wwz.ai.types.agent.config.OpenCliProperties;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@EnableConfigurationProperties({OpenCliProperties.class, HostCliProperties.class})
public class HostProcessCliAdapter implements CliExecutionPort {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Override
    public CliResult exec(CliInvocation invocation) {
        if (invocation == null || StringUtils.isBlank(invocation.getTool())) {
            return fail("", "missing tool", 1, false, false, 0L);
        }
        String resolved;
        try {
            resolved = resolveBinary(invocation.getTool());
        } catch (IllegalArgumentException e) {
            return fail(invocation.getTool(), e.getMessage(), 127, false, false, 0L);
        }
        if (resolved == null) {
            return fail(invocation.getTool(), "executable not found: " + invocation.getTool(), 127, false, false, 0L);
        }
        List<String> command = new ArrayList<>();
        command.add(resolved);
        if (invocation.getArgs() != null) {
            command.addAll(invocation.getArgs());
        }
        int maxChars = invocation.getMaxOutputChars() > 0 ? invocation.getMaxOutputChars() : 200_000;
        long timeoutMs = invocation.getTimeoutMs() > 0 ? invocation.getTimeoutMs() : 60_000L;
        long started = System.currentTimeMillis();
        Process process = null;
        StreamCollector stdout = new StreamCollector(maxChars);
        StreamCollector stderr = new StreamCollector(maxChars);
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            if (StringUtils.isNotBlank(invocation.getCwd())) {
                File cwd = new File(invocation.getCwd());
                if (cwd.isDirectory()) {
                    builder.directory(cwd);
                }
            }
            applyChildEnvironment(builder.environment(), invocation);
            process = builder.start();
            Thread outThread = startCollector(process.getInputStream(), stdout);
            Thread errThread = startCollector(process.getErrorStream(), stderr);
            if (invocation.getStdin() != null) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(invocation.getStdin().getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            boolean timedOut = !finished;
            if (timedOut) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            outThread.join(1000);
            errThread.join(1000);
            int exit = timedOut ? -1 : process.exitValue();
            long duration = System.currentTimeMillis() - started;
            boolean truncated = stdout.truncated || stderr.truncated;
            String stdoutText = stdout.text();
            return CliResult.builder()
                    .ok(!timedOut && exit == 0)
                    .tool(invocation.getTool())
                    .exitCode(exit)
                    .stdout(stdoutText)
                    .stderr(stderr.text())
                    .durationMs(duration)
                    .timedOut(timedOut)
                    .truncated(truncated)
                    // CLI 明确报告的产物候选优先于 workspace 前后快照；超时/失败时已有文件仍要上报。
                    .artifacts(CliArtifactCandidateParser.parse(stdoutText))
                    .build();
        } catch (Exception e) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return fail(invocation.getTool(), StringUtils.defaultIfBlank(e.getMessage(), "process failed"),
                    1, false, false, System.currentTimeMillis() - started);
        }
    }

    @Override
    public boolean isResolvable(String tool) {
        try {
            return resolveBinary(tool) != null;
        } catch (IllegalArgumentException ignore) {
            return false;
        }
    }

    public static void applyChildEnvironment(Map<String, String> environment, CliInvocation invocation) {
        if (environment == null || invocation == null) {
            return;
        }
        if (invocation.getUnsetEnv() != null) {
            for (String key : invocation.getUnsetEnv()) {
                if (StringUtils.isNotBlank(key)) {
                    environment.remove(key);
                }
            }
        }
        if (invocation.getEnv() == null || invocation.getEnv().isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : invocation.getEnv().entrySet()) {
            if (StringUtils.isNotBlank(entry.getKey()) && entry.getValue() != null) {
                environment.put(entry.getKey(), entry.getValue());
            }
        }
    }

    String resolveBinary(String tool) {
        if (StringUtils.isBlank(tool)) {
            return null;
        }
        String trimmed = tool.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".ps1")) {
            throw new IllegalArgumentException("refusing to spawn PowerShell script: " + trimmed);
        }
        Path direct = Path.of(trimmed);
        if (direct.isAbsolute() || trimmed.contains("/") || trimmed.contains("\\")) {
            if (direct.toString().toLowerCase(Locale.ROOT).endsWith(".ps1")) {
                throw new IllegalArgumentException("refusing to spawn PowerShell script: " + trimmed);
            }
            return Files.isRegularFile(direct) ? direct.toAbsolutePath().toString() : null;
        }
        String pathEnv = StringUtils.defaultString(System.getenv("PATH"));
        String[] dirs = pathEnv.split(WINDOWS ? ";" : ":");
        List<String> names = candidateNames(trimmed);
        for (String dir : dirs) {
            if (StringUtils.isBlank(dir)) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(dir.trim(), name);
                if (Files.isRegularFile(candidate)) {
                    if (name.toLowerCase(Locale.ROOT).endsWith(".ps1")) {
                        continue;
                    }
                    return candidate.toAbsolutePath().toString();
                }
            }
        }
        return null;
    }

    private static List<String> candidateNames(String tool) {
        if (!WINDOWS) {
            return List.of(tool);
        }
        List<String> names = new ArrayList<>();
        String lower = tool.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cmd") || lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".com")) {
            names.add(tool);
            return names;
        }
        names.add(tool + ".cmd");
        names.add(tool + ".exe");
        names.add(tool);
        return names;
    }

    private static Thread startCollector(InputStream stream, StreamCollector collector) {
        return Thread.ofVirtual().name("cli-stream").start(() -> {
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    collector.write(buffer, read);
                }
            } catch (Exception ignore) {
            }
        });
    }

    private static CliResult fail(String tool, String error, int exit, boolean timedOut, boolean truncated, long duration) {
        return CliResult.builder()
                .ok(false)
                .tool(tool)
                .exitCode(exit)
                .stdout("")
                .stderr(StringUtils.defaultString(error))
                .durationMs(duration)
                .timedOut(timedOut)
                .truncated(truncated)
                .artifacts(List.of())
                .build();
    }

    private static final class StreamCollector {
        private final StringBuilder buffer = new StringBuilder();
        private final int maxChars;
        private boolean truncated;

        private StreamCollector(int maxChars) {
            this.maxChars = Math.max(1, maxChars);
        }

        private synchronized void write(char[] data, int length) {
            if (truncated) {
                return;
            }
            int remaining = maxChars - buffer.length();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int accepted = Math.min(length, remaining);
            buffer.append(data, 0, accepted);
            if (accepted < length) {
                truncated = true;
            }
            if (buffer.length() == maxChars && Character.isHighSurrogate(buffer.charAt(maxChars - 1))) {
                buffer.setLength(maxChars - 1);
                truncated = true;
            }
        }

        private synchronized String text() {
            return buffer.toString();
        }
    }
}
