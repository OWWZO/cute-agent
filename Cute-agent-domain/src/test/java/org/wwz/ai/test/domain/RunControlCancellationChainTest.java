package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;
import org.wwz.ai.domain.agent.runtime.cancel.RunControl;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 父取消令牌必须整链保存：任一上游 cancel 都能中止当前 run。
 */
public class RunControlCancellationChainTest {

    @Test
    public void allParentTokensAreKeptAndObserved() {
        RunControl control = new RunControl();
        RunCancellation taskToken = new RunCancellation();
        RunCancellation userStopToken = new RunCancellation();
        control.bindParentCancellation(taskToken);
        control.bindParentCancellation(userStopToken);

        Assert.assertEquals(2, control.cancellationChain().size());
        AtomicInteger abortHooks = new AtomicInteger();
        control.registerAbort(abortHooks::incrementAndGet);

        taskToken.cancel("task_stop");

        Assert.assertTrue("父链中任意令牌 cancel 都必须可见", control.isCancelled());
        Assert.assertEquals("task_stop", control.getCancelReason());
        Assert.assertEquals(1, abortHooks.get());
    }

    @Test
    public void userStopTokenStillPropagatesWhenItIsNotLastBound() {
        RunControl control = new RunControl();
        RunCancellation userStopToken = new RunCancellation();
        RunCancellation taskToken = new RunCancellation();
        control.bindParentCancellation(userStopToken);
        control.bindParentCancellation(taskToken);

        userStopToken.cancel("user_stop");

        Assert.assertTrue(control.isCancelled());
        Assert.assertEquals("user_stop", control.getCancelReason());
    }

    @Test
    public void ownTokenStillWinsInChain() {
        RunControl control = new RunControl();
        RunCancellation own = new RunCancellation();
        RunCancellation parent = new RunCancellation();
        control.bindCancellation(own);
        control.bindParentCancellation(own);
        control.bindParentCancellation(parent);

        Assert.assertEquals(2, control.cancellationChain().size());
    }
}
