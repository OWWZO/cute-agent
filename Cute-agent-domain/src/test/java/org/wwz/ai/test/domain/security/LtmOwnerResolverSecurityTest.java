package org.wwz.ai.test.domain.security;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwner;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwnerResolver;
import org.wwz.ai.domain.agent.memory.ltm.LtmOwnerType;
import org.wwz.ai.types.agent.user.UserRequestContext;

/**
 * LTM owner 回归：ERP 与 userId 不再共用命名空间；无身份时不再回退全局 anonymous。
 */
public class LtmOwnerResolverSecurityTest {

    @Test
    public void userIdAndErpUseDistinctOwnerKeys() {
        LtmOwner byUser = LtmOwnerResolver.resolve("1001", null, null);
        LtmOwner byErp = LtmOwnerResolver.resolve(null, "1001", null);

        Assert.assertEquals(LtmOwnerType.USER, byUser.getType());
        Assert.assertEquals("user:1001", byUser.asOwnerKey());
        Assert.assertEquals(LtmOwnerType.ERP, byErp.getType());
        Assert.assertEquals("erp:1001", byErp.asOwnerKey());
        Assert.assertNotEquals("同值 userId / erp 不得映射到同一 owner",
                byUser.asOwnerKey(), byErp.asOwnerKey());
    }

    @Test
    public void fallsBackToSessionScopeInsteadOfGlobalAnonymous() {
        LtmOwner owner = LtmOwnerResolver.resolve(null, null, "sess-1");
        Assert.assertEquals(LtmOwnerType.SESSION, owner.getType());
        Assert.assertEquals("session:sess-1", owner.asOwnerKey());
    }

    @Test
    public void returnsNullWhenNoIdentityAtAll() {
        Assert.assertNull("无身份必须 fail-closed，不得回退 anonymous",
                LtmOwnerResolver.resolve(null, null, null));
    }

    @Test
    public void requestContextStillWins() {
        UserRequestContext.bind("ctx-user");
        try {
            LtmOwner owner = LtmOwnerResolver.resolve(null, null, "sess-1");
            Assert.assertEquals("user:ctx-user", owner.asOwnerKey());
        } finally {
            UserRequestContext.clear();
        }
    }
}
