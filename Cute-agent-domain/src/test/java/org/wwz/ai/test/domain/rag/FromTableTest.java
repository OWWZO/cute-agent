package org.wwz.ai.test.domain.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.sql.FromTable;

/**
 * JOIN 链的自引用字段不能参与生成的 toString / equals / hashCode。
 */
public class FromTableTest {

    @Test
    public void deepJoinChainDoesNotOverflowToString() {
        FromTable head = chain(20_000);

        String text = head.toString();

        Assert.assertNotNull(text);
        Assert.assertTrue(text.contains("t0"));
    }

    @Test
    public void deepJoinChainDoesNotOverflowEqualsOrHashCode() {
        FromTable left = chain(20_000);
        FromTable right = chain(20_000);

        Assert.assertEquals(left.hashCode(), right.hashCode());
        Assert.assertEquals(left, right);
    }

    private static FromTable chain(int size) {
        FromTable head = new FromTable();
        head.setTableName("t0");
        FromTable current = head;
        for (int i = 1; i < size; i++) {
            FromTable next = new FromTable();
            next.setTableName("t" + i);
            current.setRightTable(next);
            current = next;
        }
        return head;
    }
}
