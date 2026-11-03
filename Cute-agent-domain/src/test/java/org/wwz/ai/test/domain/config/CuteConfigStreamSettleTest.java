package org.wwz.ai.test.domain.config;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.wwz.ai.domain.agent.cute.config.CuteConfig;
import org.wwz.ai.domain.agent.runtime.tasklist.StreamSettlePolicy;

import java.util.Map;

/**
 * {@code autobots.autoagent.stream.defer-background-settle} 必须真正驱动静态策略。
 * <p>
 * 回归目标：{@code @Value} 挂在字段上时 Spring 直接写字段、绕过自定义 setter，
 * 配置成 false 后 {@link StreamSettlePolicy} 仍停留在默认 true，静默失效。
 */
public class CuteConfigStreamSettleTest {

    @After
    public void restoreDefault() {
        StreamSettlePolicy.setDeferBackgroundSettle(true);
    }

    @Test
    public void setterInjectionDrivesStreamSettlePolicy() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-config",
                    Map.of("autobots.autoagent.stream.defer-background-settle", "false")));
            context.register(CuteConfig.class);
            context.refresh();

            CuteConfig config = context.getBean(CuteConfig.class);
            Assert.assertFalse(config.isDeferBackgroundSettle());
            Assert.assertFalse("配置项必须同步到 StreamSettlePolicy", StreamSettlePolicy.deferBackgroundSettle());
        }
    }
}
