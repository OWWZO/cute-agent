package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.llm.retry.LlmProviderBusinessCodes;

/**
 * 业务码归一：数字码去尾零、文本码小写，保证映射表命中。
 */
public class LlmProviderBusinessCodesTest {

    @Test
    public void numericJsonCodesNormalizeToIntegerKeys() {
        Assert.assertEquals("1302", LlmProviderBusinessCodes.normalizeCode("1302.0"));
        Assert.assertEquals("500", LlmProviderBusinessCodes.normalizeCode("500.00"));
        Assert.assertNotNull("终止配额码不能落回通用 429",
                LlmProviderBusinessCodes.lookup("1302.0"));
        Assert.assertNotNull(LlmProviderBusinessCodes.lookup("500.00"));
    }

    @Test
    public void textualCodesNormalizeToLowercaseKeys() {
        Assert.assertEquals("insufficient_quota",
                LlmProviderBusinessCodes.normalizeCode("INSUFFICIENT_QUOTA"));
        Assert.assertEquals("rate_limit_error",
                LlmProviderBusinessCodes.normalizeCode("Rate_Limit_Error"));
        Assert.assertNotNull(LlmProviderBusinessCodes.lookup("INSUFFICIENT_QUOTA"));
        Assert.assertNotNull(LlmProviderBusinessCodes.lookup("Rate_Limit_Error"));
    }

    @Test
    public void wrapperCodeIsNotARealBusinessCode() {
        Assert.assertNull(LlmProviderBusinessCodes.normalizeCode("PROVIDER_BUSINESS_ERROR"));
    }
}
