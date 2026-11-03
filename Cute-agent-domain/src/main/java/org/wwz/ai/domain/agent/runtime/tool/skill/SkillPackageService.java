package org.wwz.ai.domain.agent.runtime.tool.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.security.OutboundUrlGuard;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能包安装：解析 zip / 粘贴正文 → 写入 skill root → refresh 注册表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillPackageService {

    private static final int MAX_REDIRECTS = 5;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            // 重定向必须逐跳校验，禁止客户端自动跟随到内网地址。
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final SkillRuntimeOptions skillRuntimeOptions;
    private final SkillRegistry skillRegistry;

    /** 静态威胁扫描器（无状态，直接 new；加初始化器后不进入 @RequiredArgsConstructor）。 */
    private final SkillThreatScanner threatScanner = new SkillThreatScanner();

    /**
     * 技能目录的所有变更串行执行：存在性检查、删目录、建目录、refresh 是分离步骤，
     * 并发 install/delete 会互相踩目录（尤其 BLOCK 清理可能删掉另一个请求刚写入的文件）。
     */
    private final java.util.concurrent.locks.ReentrantLock mutationLock =
            new java.util.concurrent.locks.ReentrantLock();

    public SkillPackageParser.ParsedSkillPackage previewZip(byte[] zipBytes) {
        return SkillPackageParser.parse(zipBytes);
    }

    public Map<String, Object> previewZipAsMap(byte[] zipBytes) {
        SkillPackageParser.ParsedSkillPackage p = previewZip(zipBytes);
        boolean taken = skillRegistry.findSkill(p.name()).isPresent();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("description", p.description());
        m.put("contentPreview", truncate(p.content(), 800));
        m.put("extraFiles", p.extraFiles());
        m.put("nameTaken", taken);
        return m;
    }

    /**
     * 上传 zip 安装；replace=true 时覆盖同名目录。
     */
    public Map<String, Object> installZip(byte[] zipBytes, String originalFilename, boolean replace) {
        return installZip(zipBytes, originalFilename, replace, SkillTrustLevel.UPLOAD);
    }

    private Map<String, Object> installZip(byte[] zipBytes, String originalFilename, boolean replace,
                                           SkillTrustLevel trustLevel) {
        mutationLock.lock();
        try {
            return installZipLocked(zipBytes, originalFilename, replace, trustLevel);
        } finally {
            mutationLock.unlock();
        }
    }

    private Map<String, Object> installZipLocked(byte[] zipBytes, String originalFilename, boolean replace,
                                                 SkillTrustLevel trustLevel) {
        SkillPackageParser.ParsedSkillPackage parsed = SkillPackageParser.parse(zipBytes);
        if (skillRegistry.findSkill(parsed.name()).isPresent() && !replace) {
            throw new SkillLoadException("技能「" + parsed.name() + "」已存在；若要覆盖请传 replace=true");
        }
        Map<String, byte[]> files = SkillPackageParser.unpack(zipBytes);
        Path root = requireWritableRoot();
        Path skillDir = root.resolve(parsed.name()).toAbsolutePath().normalize();
        if (!skillDir.startsWith(root.toAbsolutePath().normalize())) {
            throw new SkillLoadException("非法技能目录");
        }
        SkillThreatScanner.Report report = SkillThreatScanner.Report.empty();
        try {
            if (Files.exists(skillDir) && replace) {
                deleteRecursive(skillDir);
            }
            Files.createDirectories(skillDir);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                Path target = skillDir.resolve(e.getKey()).normalize();
                if (!target.startsWith(skillDir)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.write(target, e.getValue());
            }
            // 保证 SKILL.md 正文与解析结果一致（frontmatter 规范化，保留其余字段）
            writeSkillMd(skillDir, parsed.name(), parsed.description(), parsed.content(), parsed.frontMatter());
            report = scanDirectoryAndEnforce(skillDir, trustLevel, parsed.name());
        } catch (IOException e) {
            throw new SkillLoadException("写入技能目录失败：" + e.getMessage(), e);
        }
        skillRegistry.refresh();
        log.info("skill installed from zip name={} file={} replace={}", parsed.name(), originalFilename, replace);
        return withWarnings(skillRow(parsed.name(), trustLevel), report);
    }

    /**
     * 粘贴 SKILL.md 正文创建/覆盖。
     */
    public Map<String, Object> installFromMarkdown(String name, String description, String rawContent, boolean replace) {
        return installFromMarkdown(name, description, rawContent, replace, SkillTrustLevel.UPLOAD);
    }

    private Map<String, Object> installFromMarkdown(String name, String description, String rawContent,
                                                    boolean replace, SkillTrustLevel trustLevel) {
        mutationLock.lock();
        try {
            return installFromMarkdownLocked(name, description, rawContent, replace, trustLevel);
        } finally {
            mutationLock.unlock();
        }
    }

    private Map<String, Object> installFromMarkdownLocked(String name, String description, String rawContent,
                                                          boolean replace, SkillTrustLevel trustLevel) {
        SkillPackageParser.FrontmatterSplit front = SkillPackageParser.splitFrontmatter(
                StringUtils.defaultString(rawContent));
        String resolvedName = SkillPackageParser.sanitizeSkillName(
                StringUtils.defaultIfBlank(name, SkillPackageParser.asText(front.fields().get("name"))));
        String resolvedDesc = StringUtils.defaultIfBlank(
                description, SkillPackageParser.asText(front.fields().get("description")));
        String body = front.body();
        if (StringUtils.isBlank(body)) {
            throw new SkillLoadException("SKILL.md 正文不能为空");
        }
        if (skillRegistry.findSkill(resolvedName).isPresent() && !replace) {
            throw new SkillLoadException("技能「" + resolvedName + "」已存在；若要覆盖请传 replace=true");
        }
        // 先扫文本再落盘，避免写入后被拒绝还要清理
        SkillThreatScanner.Report report =
                scanTextAndEnforce(StringUtils.defaultString(rawContent), trustLevel, resolvedName);
        Path root = requireWritableRoot();
        Path skillDir = root.resolve(resolvedName).toAbsolutePath().normalize();
        try {
            if (Files.exists(skillDir) && replace) {
                // 仅覆盖 SKILL.md，保留 scripts/references
                writeSkillMd(skillDir, resolvedName, resolvedDesc, body, front.fields());
            } else {
                Files.createDirectories(skillDir);
                writeSkillMd(skillDir, resolvedName, resolvedDesc, body, front.fields());
            }
        } catch (IOException e) {
            throw new SkillLoadException("写入技能失败：" + e.getMessage(), e);
        }
        skillRegistry.refresh();
        log.info("skill installed from markdown name={}", resolvedName);
        return withWarnings(skillRow(resolvedName, trustLevel), report);
    }

    /**
     * 从 URL 下载 zip 后安装（在线导入最小闭环）。
     * <p>下载前解析真实 IP 并拒绝内网/回环/链路本地等地址；重定向逐跳复检；
     * 连接/读取有显式超时，响应体超过 {@link SkillPackageParser#MAX_PACKAGE_BYTES} 时中止。</p>
     */
    public Map<String, Object> installFromUrl(String url, boolean replace) {
        if (StringUtils.isBlank(url)) {
            throw new SkillLoadException("url 不能为空");
        }
        try {
            byte[] bytes = downloadZip(url.trim());
            if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
                // 可能是单文件 SKILL.md
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.contains("---") || text.contains("#")) {
                    return installFromMarkdown(null, null, text, replace, SkillTrustLevel.URL);
                }
                throw new SkillLoadException("URL 内容不是 zip 也不是 SKILL.md 文本");
            }
            return installZip(bytes, url.substring(url.lastIndexOf('/') + 1), replace, SkillTrustLevel.URL);
        } catch (SkillLoadException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkillLoadException("下载被中断", e);
        } catch (Exception e) {
            throw new SkillLoadException("下载/导入失败：" + e.getMessage(), e);
        }
    }

    private byte[] downloadZip(String url) throws IOException, InterruptedException {
        URI uri = URI.create(OutboundUrlGuard.validateHttpUrl(url));
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (isRedirect(status)) {
                String location = response.headers().firstValue("Location").orElse(null);
                response.body().close();
                if (StringUtils.isBlank(location)) {
                    throw new SkillLoadException("下载失败：重定向缺少 Location");
                }
                uri = URI.create(OutboundUrlGuard.validateHttpUrl(uri.resolve(location.trim()).toString()));
                continue;
            }
            try (InputStream body = response.body()) {
                if (status != 200) {
                    throw new SkillLoadException("下载失败：HTTP " + status);
                }
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (declared > SkillPackageParser.MAX_PACKAGE_BYTES) {
                    throw new SkillLoadException("技能包超过 32MB");
                }
                return readLimited(body, SkillPackageParser.MAX_PACKAGE_BYTES);
            }
        }
        throw new SkillLoadException("下载失败：重定向次数超过 " + MAX_REDIRECTS);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static byte[] readLimited(InputStream in, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new SkillLoadException("技能包超过 32MB");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public List<Map<String, Object>> listInstalled() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SkillDefinition def : skillRegistry.listSkills()) {
            if (def == null) {
                continue;
            }
            rows.add(skillRow(def.getName()));
        }
        return rows;
    }

    public boolean deleteSkill(String name) {
        mutationLock.lock();
        try {
            String n = SkillPackageParser.sanitizeSkillName(name);
            Path root = requireWritableRoot();
            Path skillDir = root.resolve(n).toAbsolutePath().normalize();
            if (!skillDir.startsWith(root.toAbsolutePath().normalize()) || !Files.isDirectory(skillDir)) {
                return false;
            }
            try {
                deleteRecursive(skillDir);
                skillRegistry.refresh();
                return true;
            } catch (IOException e) {
                throw new SkillLoadException("删除技能失败：" + e.getMessage(), e);
            }
        } finally {
            mutationLock.unlock();
        }
    }

    public void reload() {
        skillRegistry.refresh();
    }

    /**
     * Agent 创作：创建/覆盖 SKILL.md（全局 skill 根目录），并 refresh 注册表。
     * 始终 replace=true 语义，便于迭代优化。
     */
    public Map<String, Object> upsertManual(String name, String description, String content) {
        return installFromMarkdown(name, description, content, true);
    }

    /**
     * 在全局技能目录下写入相对路径文件（脚本/参考资料等），路径不得逃逸技能根。
     * 技能不存在时自动建最小 SKILL.md 骨架。
     */
    public Map<String, Object> writeRelativeFile(String skillName, String relativePath, String content) {
        mutationLock.lock();
        try {
            String name = SkillPackageParser.sanitizeSkillName(skillName);
            String rel = normalizeRelativePath(relativePath);
            Path skillDir = ensureSkillDir(name);
            Path target = skillDir.resolve(rel).normalize();
            if (!target.startsWith(skillDir)) {
                throw new SkillLoadException("路径逃逸技能目录: " + relativePath);
            }
            if ("SKILL.md".equalsIgnoreCase(rel) || rel.endsWith("/SKILL.md")) {
                throw new SkillLoadException("请用 upsert 写 SKILL.md，不要用 write_file 覆盖手册元数据");
            }
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new SkillLoadException("写入技能文件失败：" + e.getMessage(), e);
            }
            skillRegistry.refresh();
            log.info("skill file written name={} path={}", name, rel);
            Map<String, Object> row = skillRow(name);
            row.put("writtenPath", rel);
            row.put("bytes", content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length);
            return row;
        } finally {
            mutationLock.unlock();
        }
    }

    /**
     * 删除技能包内相对文件（不可删 SKILL.md；删技能请用 deleteSkill）。
     */
    public Map<String, Object> deleteRelativeFile(String skillName, String relativePath) {
        mutationLock.lock();
        try {
            String name = SkillPackageParser.sanitizeSkillName(skillName);
            String rel = normalizeRelativePath(relativePath);
            if ("SKILL.md".equalsIgnoreCase(rel) || rel.endsWith("/SKILL.md")) {
                throw new SkillLoadException("不能删除 SKILL.md；删除整个技能请用管理端或 delete 动作");
            }
            Path root = requireWritableRoot();
            Path skillDir = root.resolve(name).toAbsolutePath().normalize();
            if (!Files.isDirectory(skillDir)) {
                throw new SkillLoadException("技能不存在: " + name);
            }
            Path target = skillDir.resolve(rel).normalize();
            if (!target.startsWith(skillDir)) {
                throw new SkillLoadException("路径逃逸技能目录: " + relativePath);
            }
            try {
                boolean deleted = Files.deleteIfExists(target);
                skillRegistry.refresh();
                Map<String, Object> row = skillRow(name);
                row.put("deletedPath", rel);
                row.put("deleted", deleted);
                return row;
            } catch (IOException e) {
                throw new SkillLoadException("删除技能文件失败：" + e.getMessage(), e);
            }
        } finally {
            mutationLock.unlock();
        }
    }

    public List<String> listRelativeFiles(String skillName) {
        String name = SkillPackageParser.sanitizeSkillName(skillName);
        Path root = requireWritableRoot();
        Path skillDir = root.resolve(name).toAbsolutePath().normalize();
        if (!Files.isDirectory(skillDir)) {
            throw new SkillLoadException("技能不存在: " + name);
        }
        List<String> files = new ArrayList<>();
        try (var walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String rel = skillDir.relativize(p).toString().replace('\\', '/');
                files.add(rel);
            });
        } catch (IOException e) {
            throw new SkillLoadException("列举技能文件失败：" + e.getMessage(), e);
        }
        files.sort(String::compareTo);
        return files;
    }

    private Path ensureSkillDir(String name) {
        Path root = requireWritableRoot();
        Path skillDir = root.resolve(name).toAbsolutePath().normalize();
        if (!skillDir.startsWith(root.toAbsolutePath().normalize())) {
            throw new SkillLoadException("非法技能目录");
        }
        try {
            if (!Files.isDirectory(skillDir)) {
                Files.createDirectories(skillDir);
                writeSkillMd(skillDir, name, "agent-authored skill",
                        "（由 agent 自动创建骨架；请用 workspace_write/edit 补全手册）\n",
                        Map.of());
                skillRegistry.refresh();
            }
        } catch (IOException e) {
            throw new SkillLoadException("创建技能目录失败：" + e.getMessage(), e);
        }
        return skillDir;
    }

    /**
     * 对已落盘的 skill 目录做静态威胁扫描，并按来源信任等级决定是否拒绝。
     * BLOCK 时先清理目录再抛异常，避免留下半安装的 skill。
     */
    private SkillThreatScanner.Report scanDirectoryAndEnforce(Path skillDir, SkillTrustLevel trustLevel, String name) {
        if (!skillRuntimeOptions.isThreatScanEnabled()) {
            return SkillThreatScanner.Report.empty();
        }
        SkillThreatScanner.Report report = threatScanner.scan(skillDir);
        if (report.clean()) {
            return report;
        }
        if (SkillInstallPolicy.decide(trustLevel, report) == SkillInstallPolicy.Decision.BLOCK) {
            try {
                deleteRecursive(skillDir);
            } catch (IOException e) {
                log.warn("failed to clean rejected skill dir {}: {}", skillDir, e.getMessage());
            }
            throw new SkillLoadException("技能「" + name + "」包含高风险内容，已拒绝安装："
                    + String.join("；", report.descriptions()));
        }
        log.warn("skill {} threat scan warnings: {}", name, report.descriptions());
        return report;
    }

    /**
     * 粘贴 markdown 场景：落盘前先扫文本。
     */
    private SkillThreatScanner.Report scanTextAndEnforce(String rawContent, SkillTrustLevel trustLevel, String name) {
        if (!skillRuntimeOptions.isThreatScanEnabled()) {
            return SkillThreatScanner.Report.empty();
        }
        SkillThreatScanner.Report report = threatScanner.scanText(rawContent, "SKILL.md");
        if (report.clean()) {
            return report;
        }
        if (SkillInstallPolicy.decide(trustLevel, report) == SkillInstallPolicy.Decision.BLOCK) {
            throw new SkillLoadException("技能「" + name + "」包含高风险内容，已拒绝安装："
                    + String.join("；", report.descriptions()));
        }
        log.warn("skill {} threat scan warnings: {}", name, report.descriptions());
        return report;
    }

    private Map<String, Object> withWarnings(Map<String, Object> row, SkillThreatScanner.Report report) {
        if (row != null && report != null && !report.clean()) {
            row.put("warnings", report.descriptions());
        }
        return row;
    }

    private static String normalizeRelativePath(String relativePath) {
        if (StringUtils.isBlank(relativePath)) {
            throw new SkillLoadException("path 不能为空");
        }
        String rel = relativePath.trim().replace('\\', '/');
        while (rel.startsWith("./")) {
            rel = rel.substring(2);
        }
        if (rel.startsWith("/") || !SkillPackageParser.isSafeRelativePath(rel)) {
            throw new SkillLoadException("非法相对路径: " + relativePath);
        }
        return rel;
    }

    private Map<String, Object> skillRow(String name) {
        return skillRow(name, null);
    }

    /** trustLevel 标明技能来源（upload/url/...）；无法判定时保持兼容值 {@code upload}。 */
    private Map<String, Object> skillRow(String name, SkillTrustLevel trustLevel) {
        SkillDefinition def = skillRegistry.findSkill(name).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", def == null ? null : def.getDescription());
        m.put("basePath", def == null || def.getBasePath() == null ? null : def.getBasePath().toString());
        m.put("source", trustLevel == SkillTrustLevel.URL ? "url" : "upload");
        return m;
    }

    private Path requireWritableRoot() {
        List<String> dirs = skillRuntimeOptions.getDirectories();
        if (dirs == null || dirs.isEmpty()) {
            throw new SkillLoadException("未配置 skill.directories");
        }
        Path root = Path.of(dirs.get(0)).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new SkillLoadException("无法创建技能根目录：" + root, e);
        }
        return root;
    }

    /**
     * 写回 SKILL.md。
     * <p>
     * 字段顺序固定为 {@code name} → {@code description} → 其余保留字段。序列化交给
     * {@link SkillFrontMatterWriter}，由 YAML dumper 负责加引号与转义——<b>禁止字符串拼接</b>：
     * 含 {@code ": "} 的值若裸写，后续 snakeyaml 解析会报
     * {@code mapping values are not allowed here}。
     * <p>
     * 写入走同目录临时文件 + 原子移动，避免出现半截文件。
     */
    private void writeSkillMd(Path skillDir, String name, String description, String body,
                              Map<String, Object> preservedFields) throws IOException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name);
        if (StringUtils.isNotBlank(description)) {
            fields.put("description", flatten(description));
        }
        if (preservedFields != null) {
            for (Map.Entry<String, Object> entry : preservedFields.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()
                        || "name".equals(key) || "description".equals(key)
                        || entry.getValue() == null) {
                    continue;
                }
                fields.put(key, entry.getValue());
            }
        }

        SkillFrontMatterValidator.Result validation = SkillFrontMatterValidator.validate(fields);
        for (String warning : validation.warnings()) {
            log.warn("skill {} frontmatter: {}", name, warning);
        }
        if (validation.hasErrors()) {
            throw new SkillLoadException("技能 frontmatter 非法：" + String.join("；", validation.errors()));
        }

        String content = SkillFrontMatterWriter.renderDocument(fields, body);
        Path target = skillDir.resolve(SkillPackageParser.SKILL_FILE);
        Path temp = Files.createTempFile(skillDir, ".SKILL-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 把多行描述压成单行：YAML 单行标量更易读，也避免写回时引入块标量。
     */
    private static String flatten(String text) {
        return text == null ? null : text.replaceAll("\\s*\\R\\s*", " ").trim();
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
