package org.wwz.ai.test.domain.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.memory.ltm.SessionSearchRequest;
import org.wwz.ai.domain.agent.memory.ltm.SessionSearchService;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 只实现五参版本的旧适配器必须透传已有参数；无法支持的模式要显式报错。
 * <p>
 * 回归目标：默认适配器丢弃 requested sessionId / role_filter / around_message_id / window，
 * 让 scroll、会话内检索、角色过滤静默退化成别的模式。
 */
public class SessionSearchServiceDefaultAdapterTest {

    @Test
    public void passesRequestedSessionIdToLegacySearch() {
        AtomicReference<String> capturedSessionId = new AtomicReference<>();
        SessionSearchService service = (sessionId, userId, query, limit, scope) -> {
            capturedSessionId.set(sessionId);
            return "{\"success\":true}";
        };

        service.search(SessionSearchRequest.builder()
                .sessionId("requested-session")
                .currentSessionId("current-session")
                .query("q")
                .build());

        Assert.assertEquals("requested-session", capturedSessionId.get());
    }

    @Test
    public void rejectsScrollOptionsInsteadOfSilentlyDowngrading() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        SessionSearchService service = (sessionId, userId, query, limit, scope) -> {
            delegated.set(true);
            return "{\"success\":true}";
        };

        JSONObject result = JSON.parseObject(service.search(SessionSearchRequest.builder()
                .sessionId("s-1")
                .aroundMessageId(42L)
                .window(5)
                .build()));

        Assert.assertFalse(result.getBooleanValue("success"));
        Assert.assertFalse("旧适配器不支持 scroll，不能静默降级成 read", delegated.get());
    }

    @Test
    public void rejectsRoleFilterInsteadOfIgnoringIt() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        SessionSearchService service = (sessionId, userId, query, limit, scope) -> {
            delegated.set(true);
            return "{\"success\":true}";
        };

        JSONObject result = JSON.parseObject(service.search(SessionSearchRequest.builder()
                .query("q")
                .roleFilter("user")
                .build()));

        Assert.assertFalse(result.getBooleanValue("success"));
        Assert.assertFalse("旧适配器不支持角色过滤，不能静默忽略", delegated.get());
    }
}
