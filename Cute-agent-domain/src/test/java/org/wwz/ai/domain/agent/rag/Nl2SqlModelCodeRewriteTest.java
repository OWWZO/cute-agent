package org.wwz.ai.domain.agent.rag;

import org.junit.Assert;
import org.junit.Test;

/**
 * 模型编码改写必须按字面量处理，且只重写目标模型。
 */
public class Nl2SqlModelCodeRewriteTest {

    @Test
    public void regexMetacharactersInModelCodeDoNotThrow() {
        String sql = "select * from m1";
        Assert.assertEquals(sql, Nl2SqlQueryService.rewriteModelCode(sql, "a+(b[c]", "t_m1"));
    }

    @Test
    public void replacementDollarAndBackslashAreLiteral() {
        Assert.assertEquals("select * from t$1\\x",
                Nl2SqlQueryService.rewriteModelCode("select * from m1", "m1", "t$1\\x"));
    }

    @Test
    public void onlyWholeIdentifierIsRewritten() {
        Assert.assertEquals("select * from t_order",
                Nl2SqlQueryService.rewriteModelCode("select * from user_order", "user_order", "t_order"));
        Assert.assertEquals("select * from user_detail",
                Nl2SqlQueryService.rewriteModelCode("select * from user_detail", "user_order", "t_order"));
    }

    @Test
    public void backtickedIdentifierIsRewritten() {
        Assert.assertEquals("select * from t_order",
                Nl2SqlQueryService.rewriteModelCode("select * from `user_order`", "user_order", "t_order"));
    }
}
