package org.wwz.ai.test.domain.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.sql.ComparisonType;

/**
 * 单值算子的参数个数分类必须覆盖所有单值比较符。
 */
public class ComparisonTypeTest {

    @Test
    public void notLikeIsClassifiedAsOneParamOperator() {
        Assert.assertTrue(ComparisonType.ONE_PARAM_TYPE.contains(ComparisonType.NOT_LIKE));
        Assert.assertFalse(ComparisonType.NONE_PARAM_TYPE.contains(ComparisonType.NOT_LIKE));
        Assert.assertFalse(ComparisonType.MORE_PARAM_TYPE.contains(ComparisonType.NOT_LIKE));
    }
}
