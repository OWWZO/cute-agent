package org.wwz.ai.domain.agent.runtime.tool.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 技能路径契约与 SKILL.md 占位符替换。
 *
 * <p>对外 / 沙箱统一：{@code skills/&lt;技能名&gt;}（虚拟路径；workspace 工具映射到 runtime 库，
 * bash 执行时在沙箱 cwd 下物化为同名相对路径）。
 */
@Slf4j
@Component
public class SkillRuntimeLayout {

    /** 虚拟路径与沙箱相对根，统一 skills/ */
    public static final String RELATIVE_ROOT = "skills";
    public static final String PLACEHOLDER_SKILL_DIR = "${SKILL_DIR}";
    public static final String PLACEHOLDER_PYTHON = "${PYTHON}";

    private static final Pattern UNSAFE_SEGMENT = Pattern.compile("[^A-Za-z0-9._-]+");
    /** 清洗后的最终不变量：单段、仅安全字符、不是 . / .. */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int MAX_SEGMENT_LEN = 80;
    private static final String FALLBACK_SEGMENT = "skill";
    /** 有损清洗后追加的稳定短哈希长度（"%08x"）；同一原始名跨进程稳定。 */
    private static final int HASH_LEN = 8;

    private final String python;

    public SkillRuntimeLayout(SkillRuntimeOptions skillRuntimeOptions) {
        String configured = skillRuntimeOptions == null ? null : skillRuntimeOptions.getRuntimePython();
        this.python = (configured == null || configured.isBlank()) ? "python" : configured.trim();
    }

    public String getPython() {
        return python;
    }

    public String render(String skillName, String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content
                .replace(PLACEHOLDER_SKILL_DIR, dirOf(skillName))
                .replace(PLACEHOLDER_PYTHON, python);
    }

    public String relativeDirOf(String skillName) {
        return RELATIVE_ROOT + "/" + segmentOf(skillName);
    }

    public String dirOf(String skillName) {
        return relativeDirOf(skillName);
    }

    public String segmentOf(String name) {
        if (name == null || name.isBlank()) {
            // 空技能名通常意味着上游解析失败；回退到公共目录会让多个技能互相覆盖，先留下告警。
            log.warn("技能名为空，目录回退到 {}", FALLBACK_SEGMENT);
            return FALLBACK_SEGMENT;
        }
        String original = name.trim();
        String cleaned = UNSAFE_SEGMENT.matcher(original).replaceAll("-");
        boolean truncated = cleaned.length() > MAX_SEGMENT_LEN;
        if (truncated) {
            cleaned = cleaned.substring(0, MAX_SEGMENT_LEN);
        }
        String segment = cleaned.replaceAll("^[.-]+|[.-]+$", "");
        boolean lossy = truncated || !segment.equals(original)
                // 仅大小写不同的名字在大小写不敏感文件系统上会落到同一目录。
                || !original.equals(original.toLowerCase(Locale.ROOT));
        // 显式收口：清洗规则本身已把分隔符替换掉，但这里再断言一次最终不变量，
        // 避免未来放宽 UNSAFE_SEGMENT（例如支持 Unicode）时静默带回 . / .. 或多段路径。
        if (!isSafeSegment(segment)) {
            log.warn("技能名清洗后仍不安全，目录改用 {} 加哈希: {}", FALLBACK_SEGMENT, name);
            segment = FALLBACK_SEGMENT;
            lossy = true;
        }
        if (!lossy) {
            return segment;
        }
        // 清洗有损时追加原始名的稳定短哈希：纯非 ASCII / 超长前缀 / 大小写变体
        // 不能共享同一个 materialized 目录，否则一个技能会覆盖另一个。
        return withHashSuffix(segment, original);
    }

    private static String withHashSuffix(String base, String original) {
        String suffix = "-" + String.format(Locale.ROOT, "%08x", original.hashCode());
        int maxBaseLen = MAX_SEGMENT_LEN - HASH_LEN - 1;
        String head = base.length() > maxBaseLen ? base.substring(0, maxBaseLen) : base;
        head = head.replaceAll("[.-]+$", "");
        if (head.isEmpty()) {
            head = FALLBACK_SEGMENT;
        }
        return head + suffix;
    }

    static boolean isSafeSegment(String segment) {
        return segment != null
                && !segment.isBlank()
                && !".".equals(segment)
                && !"..".equals(segment)
                && segment.indexOf('/') < 0
                && segment.indexOf('\\') < 0
                && SAFE_SEGMENT.matcher(segment).matches();
    }
}
