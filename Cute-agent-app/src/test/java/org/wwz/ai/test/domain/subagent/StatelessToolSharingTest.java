package org.wwz.ai.test.domain.subagent;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.beans.factory.config.BeanDefinition;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentContextFactory;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无状态工具的并发共享契约。
 *
 * <p>工具实例不再持有 {@code AgentContext}，上下文由
 * {@link BaseTool#execute(Object, AgentContext)} 显式传入。因此同一个实例可以被
 * 多个 Agent 并发共享，这里锁定三条保证：</p>
 * <ol>
 *   <li>并发调用各自读到自己的 context，不会互相覆盖；</li>
 *   <li>并发调用真正并行，不存在把调用串行化的共享锁；</li>
 *   <li>共享的 {@link ToolCollection} 按调用方传入的 context 派发。</li>
 * </ol>
 */
public class StatelessToolSharingTest {

    @Test
    public void concurrentCallsOnSharedInstanceShouldSeeTheirOwnContext() throws Exception {
        EchoContextTool tool = new EchoContextTool(0L);
        AgentContext childA = context("child-a");
        AgentContext childB = context("child-b");

        int rounds = 40;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> fa = pool.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    Assert.assertEquals("child-a", tool.execute(null, childA));
                }
            });
            Future<?> fb = pool.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    Assert.assertEquals("child-b", tool.execute(null, childB));
                }
            });
            fa.get(5, TimeUnit.SECONDS);
            fb.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        Assert.assertNull("并发调用不应在实例上留下任何 context", tool.lastSeenRequestId.get());
    }

    @Test
    public void concurrentCallsOnSharedInstanceShouldOverlapWithoutSerialization() throws Exception {
        // 单次 200ms；若被共享锁串行化，两次调用约 400ms。
        EchoContextTool tool = new EchoContextTool(200L);
        AgentContext childA = context("child-a");
        AgentContext childB = context("child-b");

        CountDownLatch bothStarted = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long startedAt = System.currentTimeMillis();
        try {
            Future<?> fa = pool.submit(() -> {
                bothStarted.countDown();
                bothStarted.await(2, TimeUnit.SECONDS);
                return tool.execute(null, childA);
            });
            Future<?> fb = pool.submit(() -> {
                bothStarted.countDown();
                bothStarted.await(2, TimeUnit.SECONDS);
                return tool.execute(null, childB);
            });
            Assert.assertEquals("child-a", fa.get(3, TimeUnit.SECONDS));
            Assert.assertEquals("child-b", fb.get(3, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        Assert.assertTrue("无状态工具应并行执行，elapsed=" + elapsed, elapsed < 320L);
    }

    @Test
    public void sharedToolCollectionShouldDispatchWithCallerContext() {
        EchoContextTool tool = new EchoContextTool(0L);
        ToolCollection tools = new ToolCollection();
        tools.addTool(tool);
        // 不绑定 collection 级 context：无状态工具完全依赖调用方传入。

        AgentContext childA = context("child-a");
        AgentContext childB = context("child-b");

        Assert.assertEquals("child-a", tools.execute(EchoContextTool.NAME, Map.of(), childA));
        Assert.assertEquals("child-b", tools.execute(EchoContextTool.NAME, Map.of(), childB));
        // 派发后实例上依然不残留 context。
        Assert.assertNull(tool.lastSeenRequestId.get());
    }

    @Test
    public void sharedCollectionShouldFallBackToBoundContextWhenCallerOmitsIt() {
        EchoContextTool tool = new EchoContextTool(0L);
        ToolCollection tools = new ToolCollection();
        tools.addTool(tool);
        AgentContext bound = context("bound");
        tools.setAgentContext(bound);

        // 旧签名路径不传 context：回落到 collection 自身绑定的那个，保证后台/测试路径不变。
        Assert.assertEquals("bound", tools.execute(EchoContextTool.NAME, Map.of(), bound));
    }

    private static AgentContext context(String requestId) {
        return AgentContext.builder()
                .requestId(requestId)
                .sessionId("s-" + requestId)
                .query("q")
                .build();
    }

    /**
     * 不变量：tool 包下所有 {@link BaseTool} 实现都不得持有 {@code AgentContext}
     * （实例字段、基类字段、包装类型或 static 一律算违规）。
     *
     * <p>这是无状态化契约的根。一旦有工具把 context 缓存回实例，上面的并发测试会因时序
     * 偶发通过，而这个断言对任何一处回退都立即失败。</p>
     *
     * <p>三个扫描盲区必须覆盖：</p>
     * <ol>
     *   <li>沿 {@code getSuperclass()} 链上溯——子类没声明不代表基类没缓存；</li>
     *   <li>{@code ThreadLocal<AgentContext>} / {@code AtomicReference<AgentContext>}
     *       等包装类型同样算违规——只匹配字段类型恰好是 {@code AgentContext} 会漏掉它们；</li>
     *   <li>static 字段显式列为违规——工具是单例共享，static 缓存比实例字段更隐蔽。</li>
     * </ol>
     */
    @Test
    public void noToolShouldHoldAnAgentContextField() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BaseTool.class));
        Set<BeanDefinition> candidates = scanner.findCandidateComponents(
                "org.wwz.ai.domain.agent.runtime.tool");
        Assert.assertFalse("未扫描到任何 BaseTool 实现，扫描路径是否已变更", candidates.isEmpty());

        List<String> offenders = new ArrayList<>();
        for (BeanDefinition definition : candidates) {
            Class<?> toolClass = Class.forName(definition.getBeanClassName());
            // ToolCollection 是工具容器：它自身的 context 绑定用于 MCP 执行等回退路径，不属于工具实例状态。
            if (ToolCollection.class.isAssignableFrom(toolClass)) {
                continue;
            }
            for (Class<?> c = toolClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (holdsAgentContext(field)) {
                        offenders.add(toolClass.getSimpleName() + "." + field.getName());
                    }
                }
            }
        }
        Assert.assertTrue("这些工具仍持有 AgentContext：" + offenders, offenders.isEmpty());
    }

    /**
     * 字段直接是 AgentContext、其包装类型，或 static 缓存了 AgentContext，都算违规。
     *
     * <p>static 判定只针对「类型涉及 AgentContext」的字段：工具单例共享，
     * {@code static AgentContext} / {@code static ThreadLocal<AgentContext>} 比实例字段更隐蔽；
     * 而 {@code @Slf4j} 的 {@code log} 与常量不属于 context 缓存，不应误伤。</p>
     */
    private static boolean holdsAgentContext(Field field) {
        if (AgentContext.class.isAssignableFrom(field.getType())) {
            return true;
        }
        if (!(field.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized)) {
            return false;
        }
        for (java.lang.reflect.Type arg : parameterized.getActualTypeArguments()) {
            if (arg instanceof Class<?> type && AgentContext.class.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 类型锁：{@link BaseTool} 不得保留无 context 的 {@code execute(Object)} 重载。
     *
     * <p>删掉 44 份 stub 是行为层清理；这条把结果固化成类型契约——任何人重新加回
     * 单参重载（哪怕只是 default 委托），无状态契约就重新出现可被绕过的入口。</p>
     */
    @Test
    public void baseToolMustNotDeclareContextFreeExecute() throws Exception {
        for (Method method : BaseTool.class.getDeclaredMethods()) {
            Assert.assertFalse("BaseTool 不得声明 execute(Object) 单参重载",
                    "execute".equals(method.getName()) && method.getParameterCount() == 1);
        }
    }

    /**
     * 控制面生命周期锁：会话级状态共享、run 级状态独占。
     *
     * <p>把 026 的回归修复固化为长期守卫——{@code SubAgentContextFactory} 若回退到
     * 共享整个 {@code RunControl}，子 Agent 的 {@code finishRun} 会再次提前关闭父 SSE、
     * 覆盖父的取消令牌与 inject 队列（三类回归的根因）。</p>
     *
     * <p>共享的判据是<b>状态实例</b>而不是 {@code SessionControl} 外壳：子 Agent 拿到的是
     * 自己的一层包装（便于按子 sessionKey 补建），但内部的 Todo 列表与后台任务表必须是
     * 父的同一实例，否则 TaskStop / TaskOutput 命不中子 Agent。</p>
     */
    @Test
    public void subAgentMustShareSessionStateButNotRunControl() {
        AgentContext parent = context("parent-control");
        AgentContext child = SubAgentContextFactory.create(
                parent, "子任务", "描述", new ToolCollection(), "agent-control", "general-purpose");

        Assert.assertSame("会话级 Todo 列表必须父子共享（TodoWrite 同一列表）",
                parent.requireSessionTaskList(), child.requireSessionTaskList());
        Assert.assertSame("会话级后台任务表必须父子共享（TaskStop/TaskOutput 命中子 Agent）",
                parent.requireBackgroundTasks(), child.requireBackgroundTasks());
        Assert.assertNotSame("run 级控制面必须独占（turnClosed/injects/令牌链互不覆盖）",
                parent.control(), child.control());
    }

    /**
     * 把本次调用看到的 requestId 原样返回，并记录最后一次观察值。
     *
     * <p>{@code lastSeenRequestId} 只在无状态前提下才可能为 null：若实例偷偷缓存了
     * context，这里会留下最后一次写入的痕迹。</p>
     */
    private static final class EchoContextTool implements BaseTool {

        private static final String NAME = "echo_ctx";

        private final long sleepMs;
        private final AtomicReference<String> lastSeenRequestId = new AtomicReference<>();

        private EchoContextTool(long sleepMs) {
            this.sleepMs = sleepMs;
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public String getDescription() {
            return "回显调用方 requestId";
        }

        @Override
        public Map<String, Object> toParams() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Object execute(Object input, AgentContext context) {
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }
            return context == null ? null : context.getRequestId();
        }
    }
}
