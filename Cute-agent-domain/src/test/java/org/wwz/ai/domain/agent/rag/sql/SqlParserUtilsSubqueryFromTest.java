package org.wwz.ai.domain.agent.rag.sql;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.sql.SqlModel;

/**
 * 无 FROM 的子查询（SELECT 1）不能让 FROM 解析链 NPE。
 */
public class SqlParserUtilsSubqueryFromTest {

    @Test
    public void subqueryWithoutFromShouldStillParse() throws Exception {
        SqlModel model = SqlParserUtils.parseSelectSql("SELECT * FROM (SELECT 1 AS x) t", "mysql");

        Assert.assertNotNull(model);
        Assert.assertNotNull(model.getFromTable());
        Assert.assertEquals("t", model.getFromTable().getTableAlias());
    }
}
