package org.wwz.ai.domain.agent.runtime.tool.skill;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按需读取 SKILL.md 与附属文件，不把正文留在 catalog 缓存里。
 */
@Component
@RequiredArgsConstructor
public class DefaultSkillLoader implements SkillLoader {

    /** 单次 skill_view 注入的文本上限；更大的文件只读开头，避免整文件进内存。 */
    private static final int MAX_INLINE_TEXT_BYTES = 512 * 1024;

    private final SkillCatalog skillCatalog;
    private final SkillMarkdownParser skillMarkdownParser;
    private final SkillPathGuard skillPathGuard;

    @Override
    public SkillDocument load(SkillRef ref) {
        SkillDescriptor descriptor = requireDescriptor(ref);
        String content = skillMarkdownParser.readBody(descriptor.getBasePath());
        return SkillDocument.builder()
                .descriptor(descriptor)
                // 当前解析器缺失/空文件时抛 SkillLoadException，不返回 null；保留兜底防止未来实现变化。
                .content(content == null ? "" : content)
                .linkedFiles(descriptor.getFiles() == null ? List.of() : List.copyOf(descriptor.getFiles()))
                .build();
    }

    @Override
    public SkillFile loadFile(SkillRef ref, String relativePath) {
        SkillDescriptor descriptor = requireDescriptor(ref);
        if (skillPathGuard.hasTraversal(relativePath)) {
            throw new SkillLoadException("file_path must be a relative path inside the skill directory");
        }
        Path basePath = descriptor.getBasePath().toAbsolutePath().normalize();
        Path target = skillPathGuard.ensureUnderRoot(basePath, basePath.resolve(relativePath.trim()));
        if (!Files.isRegularFile(target)) {
            throw new SkillFileNotFoundException(relativePath, availableFiles(descriptor));
        }
        try {
            long size = Files.size(target);
            if (size > MAX_INLINE_TEXT_BYTES) {
                return SkillFile.builder()
                        .name(descriptor.getName())
                        .path(toRelative(basePath, target))
                        .content(readHead(target, MAX_INLINE_TEXT_BYTES))
                        .binary(false)
                        .size(size)
                        .truncated(true)
                        .build();
            }
            try {
                String content = Files.readString(target, StandardCharsets.UTF_8);
                return SkillFile.builder()
                        .name(descriptor.getName())
                        .path(toRelative(basePath, target))
                        .content(content)
                        .binary(false)
                        .size(size)
                        .build();
            } catch (CharacterCodingException e) {
                return SkillFile.builder()
                        .name(descriptor.getName())
                        .path(toRelative(basePath, target))
                        .content("[Binary file: " + target.getFileName() + ", size: " + size + " bytes]")
                        .binary(true)
                        .size(size)
                        .build();
            }
        } catch (IOException e) {
            throw new SkillLoadException("failed to read " + relativePath, e);
        }
    }

    private static String readHead(Path target, int maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(target)) {
            byte[] head = in.readNBytes(maxBytes);
            return new String(head, StandardCharsets.UTF_8)
                    + "\n…[file truncated at " + maxBytes + " bytes]…";
        }
    }

    private SkillDescriptor requireDescriptor(SkillRef ref) {
        if (ref == null) {
            throw new SkillLoadException("Skill ref is required");
        }
        if (ref.id() != null && !ref.id().isBlank()) {
            return skillCatalog.getRequired(ref.id());
        }
        return skillCatalog.getRequired(ref.name());
    }

    private Map<String, List<String>> availableFiles(SkillDescriptor descriptor) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        if (descriptor.getFiles() == null) {
            return grouped;
        }
        for (SkillFileDescriptor file : descriptor.getFiles()) {
            if (file == null || file.path() == null) {
                continue;
            }
            grouped.computeIfAbsent(file.kind() == null ? "other" : file.kind(), key -> new ArrayList<>())
                    .add(file.path());
        }
        return grouped;
    }

    private String toRelative(Path basePath, Path target) {
        return basePath.relativize(target).toString().replace('\\', '/');
    }
}
