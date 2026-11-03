package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.tool.skill.DefaultSkillRegistry;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillDescriptor;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillMarkdownParser;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillPathGuard;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillRuntimeOptions;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillScriptDiscoverer;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skill 目录刷新必须整体发布快照：读者拿到的四个索引始终来自同一代扫描结果。
 */
public class DefaultSkillRegistrySnapshotTest {

    @Test
    public void refreshReplacesWholeSnapshotReference() throws Exception {
        Path rootAlpha = skillRoot("alpha");
        Path rootBeta = skillRoot("beta");
        SkillRuntimeOptions options = options(rootAlpha);
        DefaultSkillRegistry registry = registry(options);

        registry.refresh();
        Object first = snapshotOf(registry);

        options.setDirectories(List.of(rootBeta.toString()));
        registry.refresh();
        Object second = snapshotOf(registry);

        Assert.assertNotSame(first, second);
        Assert.assertEquals(List.of("alpha"), skillNames(first));
        Assert.assertEquals(List.of("beta"), skillNames(second));
        assertSnapshotConsistent(first);
        assertSnapshotConsistent(second);
    }

    @Test
    public void initLoadsSkillsWithoutExplicitRefresh() throws Exception {
        DefaultSkillRegistry registry = registry(options(skillRoot("boot")));
        Assert.assertFalse("构造后尚未扫描", registry.isEnabled());

        registry.init();

        Assert.assertTrue(registry.isEnabled());
        Assert.assertEquals(List.of("boot"),
                registry.listSkills().stream()
                        .map(org.wwz.ai.domain.agent.runtime.tool.skill.SkillDefinition::getName)
                        .toList());
        Assert.assertNotNull("启动钩子必须存在",
                DefaultSkillRegistry.class.getMethod("init").getAnnotation(jakarta.annotation.PostConstruct.class));
    }

    @Test
    public void concurrentRefreshNeverPublishesMixedIndexes() throws Exception {
        Path rootAlpha = skillRoot("alpha");
        Path rootBeta = skillRoot("beta");
        SkillRuntimeOptions options = options(rootAlpha);
        DefaultSkillRegistry registry = registry(options);
        registry.refresh();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 60 && failure.get() == null; i++) {
                    options.setDirectories(List.of((i % 2 == 0 ? rootBeta : rootAlpha).toString()));
                    registry.refresh();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "skill-registry-writer");

        List<Thread> readers = List.of(
                reader(registry, start, writer, failure, "skill-registry-reader-1"),
                reader(registry, start, writer, failure, "skill-registry-reader-2"),
                reader(registry, start, writer, failure, "skill-registry-reader-3"),
                reader(registry, start, writer, failure, "skill-registry-reader-4"));

        writer.start();
        readers.forEach(Thread::start);
        start.countDown();

        writer.join(TimeUnit.SECONDS.toMillis(30));
        for (Thread reader : readers) {
            reader.join(TimeUnit.SECONDS.toMillis(30));
        }
        Assert.assertFalse("writer did not finish", writer.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("snapshot observation failed", failure.get());
        }
    }

    private static Thread reader(DefaultSkillRegistry registry,
                                 CountDownLatch start,
                                 Thread writer,
                                 AtomicReference<Throwable> failure,
                                 String name) {
        return new Thread(() -> {
            try {
                start.await();
                while (writer.isAlive() && failure.get() == null) {
                    assertSnapshotConsistent(snapshotOf(registry));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, name);
    }

    private static SkillRuntimeOptions options(Path root) {
        return SkillRuntimeOptions.builder()
                .enabled(true)
                .directories(List.of(root.toString()))
                .build();
    }

    private static DefaultSkillRegistry registry(SkillRuntimeOptions options) {
        SkillPathGuard skillPathGuard = new SkillPathGuard();
        return new DefaultSkillRegistry(
                options,
                new SkillMarkdownParser(),
                new SkillScriptDiscoverer(skillPathGuard),
                skillPathGuard);
    }

    private static Path skillRoot(String skillName) throws Exception {
        Path root = Files.createTempDirectory("skill-snapshot-" + skillName);
        Path skillDirectory = root.resolve(skillName);
        Files.createDirectories(skillDirectory);
        Files.writeString(skillDirectory.resolve("SKILL.md"), """
                ---
                name: %s
                description: snapshot fixture
                ---

                # Snapshot Fixture
                """.formatted(skillName), StandardCharsets.UTF_8);
        return root;
    }

    private static Object snapshotOf(DefaultSkillRegistry registry) throws ReflectiveOperationException {
        return field(registry, "snapshot");
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @SuppressWarnings("unchecked")
    private static List<String> skillNames(Object snapshot) throws ReflectiveOperationException {
        List<SkillDescriptor> descriptors = (List<SkillDescriptor>) field(snapshot, "descriptors");
        return descriptors.stream().map(SkillDescriptor::getName).toList();
    }

    @SuppressWarnings("unchecked")
    private static void assertSnapshotConsistent(Object snapshot) throws ReflectiveOperationException {
        List<SkillDescriptor> descriptors = (List<SkillDescriptor>) field(snapshot, "descriptors");
        Map<String, SkillDescriptor> byId = (Map<String, SkillDescriptor>) field(snapshot, "byId");
        Map<String, List<SkillDescriptor>> byName =
                (Map<String, List<SkillDescriptor>>) field(snapshot, "byName");
        Map<String, SkillDescriptor> byUniqueName =
                (Map<String, SkillDescriptor>) field(snapshot, "byUniqueName");

        Set<SkillDescriptor> descriptorSet = new HashSet<>(descriptors);
        Set<String> idSet = new HashSet<>();
        descriptors.forEach(descriptor -> idSet.add(descriptor.getRef().id()));

        Assert.assertEquals(idSet, byId.keySet());
        Assert.assertEquals(descriptorSet, new HashSet<>(byId.values()));
        Assert.assertTrue(descriptorSet.containsAll(byUniqueName.values()));
        Assert.assertEquals(byId.keySet().stream()
                .map(id -> byId.get(id).getName())
                .collect(java.util.stream.Collectors.toSet()), byUniqueName.keySet());
        for (List<SkillDescriptor> sameName : byName.values()) {
            Assert.assertTrue(descriptorSet.containsAll(sameName));
        }
    }
}
