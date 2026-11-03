package org.wwz.ai.test.domain.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.Nl2SqlQueryService;
import org.wwz.ai.domain.agent.rag.model.config.DataQuerySettings;
import org.wwz.ai.domain.agent.rag.model.query.DataQueryResult;
import org.wwz.ai.domain.agent.rag.model.query.Nl2SqlQuery;
import org.wwz.ai.domain.agent.rag.model.query.Nl2SqlResult;
import org.wwz.ai.domain.agent.rag.model.query.SqlExecutionResult;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryModelDescriptor;
import org.wwz.ai.domain.agent.rag.model.schema.DataQuerySchema;
import org.wwz.ai.domain.agent.rag.port.DataQueryExecutionPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NL2SQL 执行前必须对替换表名后的最终 SQL 重新做只读校验，并强制行数上限。
 */
public class Nl2SqlQueryServiceTest {

    private static final String MODEL_CODE = "sales_model";

    @Test
    public void rejectsSqlThatBecomesNonSelectAfterModelCodeRewrite() {
        RecordingExecutionPort port = new RecordingExecutionPort();
        Nl2SqlQueryService service = new Nl2SqlQueryService(new DataQuerySettings(), port, null, null);

        Nl2SqlQuery request = request("sql", "SELECT * FROM sales_data WHERE 1=0; DROP TABLE users");
        Nl2SqlResult result = result();

        RuntimeException exception = Assert.assertThrows(RuntimeException.class,
                () -> service.queryData(request, result));

        Assert.assertTrue(exception.getMessage().contains("只读 SELECT"));
        Assert.assertNull("非法 SQL 不得进入执行端口", port.sql);
    }

    @Test
    public void executesSelectWithRowCapAndTruncatesOverflow() throws Exception {
        RecordingExecutionPort port = new RecordingExecutionPort();
        port.rows = rows(600);
        Nl2SqlQueryService service = new Nl2SqlQueryService(new DataQuerySettings(), port, null, null);

        List<DataQueryResult> results = service.queryData(request("table", "sales_data"), result());

        Assert.assertEquals("SELECT * FROM sales_data", port.sql);
        Assert.assertEquals("端口必须收到安全行数上限", 500, port.limit);
        Assert.assertEquals(1, results.size());
        Assert.assertEquals("超过上限的结果必须截断", 500, results.get(0).getDataList().size());
    }

    private static Nl2SqlQuery request(String modelType, String modelContent) {
        DataQuerySchema schema = new DataQuerySchema();
        schema.setColumnId("id");
        schema.setColumnName("id");
        schema.setDataType("BIGINT");

        DataQueryModelDescriptor model = new DataQueryModelDescriptor();
        model.setModelCode(MODEL_CODE);
        model.setType(modelType);
        model.setContent(modelContent);
        model.setSchemaList(List.of(schema));

        Nl2SqlQuery request = new Nl2SqlQuery();
        request.setRequestId("req-test");
        request.setTraceId("trace-test");
        request.setDbType("mysql");
        request.setSchemaInfo(List.of(model));
        return request;
    }

    private static Nl2SqlResult result() {
        Nl2SqlResult.GeneratedQuery generated = new Nl2SqlResult.GeneratedQuery();
        generated.setQuery("销售额");
        generated.setNl2sql("SELECT * FROM " + MODEL_CODE);

        Nl2SqlResult result = new Nl2SqlResult();
        result.setCode(200);
        result.setData(List.of(generated));
        return result;
    }

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            rows.add(row);
        }
        return rows;
    }

    private static final class RecordingExecutionPort implements DataQueryExecutionPort {
        private String sql;
        private int limit;
        private List<Map<String, Object>> rows = List.of();

        @Override
        public SqlExecutionResult query(String sql) {
            return query(sql, 0);
        }

        @Override
        public SqlExecutionResult query(String sql, int limit) {
            this.sql = sql;
            this.limit = limit;
            SqlExecutionResult result = new SqlExecutionResult(sql);
            result.setDataList(rows);
            result.setDataSize((long) rows.size());
            return result;
        }
    }
}
