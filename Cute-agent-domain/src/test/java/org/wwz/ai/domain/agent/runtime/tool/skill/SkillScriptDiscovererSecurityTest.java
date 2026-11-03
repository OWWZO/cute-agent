package org.wwz.ai.domain.agent.runtime.tool.skill;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * scripts.yaml 是外部上传的 skill 内容，解析器不得实例化任意 Java 类型。
 * <p>
 * 回归目标：CVE-2022-1471 一类的全局标签（{@code !!java.*}）。外部 skill 包里
 * 塞一个类标签，加载 scripts.yaml 时不能触发其构造器。
 */
public class SkillScriptDiscovererSecurityTest {

    @Test
    public void shouldNotInstantiateArbitraryTypesFromScriptsYaml() throws Exception {
        Path skillDirectory = Files.createTempDirectory("skill-scripts-yaml-");
        Files.writeString(skillDirectory.resolve("scripts.yaml"), """
                scripts:
                  probe: !!org.wwz.ai.domain.agent.runtime.tool.skill.SkillScriptDiscovererSecurityTest$RceProbe {}
                """, StandardCharsets.UTF_8);

        RceProbe.INSTANTIATED.set(false);

        // SafeConstructor 会直接拒绝未知标签，而不是先实例化再看结构。
        Assert.assertThrows(RuntimeException.class,
                () -> new SkillScriptDiscoverer(new SkillPathGuard()).discover(skillDirectory));
        Assert.assertFalse("scripts.yaml 不得实例化任意 Java 类型", RceProbe.INSTANTIATED.get());
    }

    /** 探针：只要被 SnakeYAML 反射实例化就会被置位。 */
    public static class RceProbe {
        static final AtomicBoolean INSTANTIATED = new AtomicBoolean();

        public RceProbe() {
            INSTANTIATED.set(true);
        }
    }
}
