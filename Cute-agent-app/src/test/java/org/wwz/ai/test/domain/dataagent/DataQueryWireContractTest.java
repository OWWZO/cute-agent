package org.wwz.ai.test.domain.dataagent;

import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.query.DataQueryEventType;
import org.wwz.ai.domain.agent.rag.model.query.DataQueryStreamEvent;
import org.wwz.ai.domain.agent.rag.model.query.Nl2SqlQuery;
import org.wwz.ai.domain.agent.rag.model.query.Nl2SqlResult;
import org.wwz.ai.domain.agent.rag.model.schema.DataQueryModelDescriptor;

import java.util.List;

public class DataQueryWireContractTest {

    @Test
    public void serializesNl2SqlRequestWithExistingFieldNames() {
        DataQueryModelDescriptor model = new DataQueryModelDescriptor();
        model.setModelCode("sales_model");
        Nl2SqlQuery query = new Nl2SqlQuery();
        query.setRequestId("request-1");
        query.setTraceId("trace-1");
        query.setQuery("sales by month");
        query.setModelCodeList(List.of("sales_model"));
        query.setSchemaInfo(List.of(model));
        query.setDbType("mysql");
        query.setUseVector(true);
        query.setUseElastic(false);

        JSONObject json = JSONObject.parseObject(JSONObject.toJSONString(query));

        Assert.assertEquals("request-1", json.getString("requestId"));
        Assert.assertEquals("trace-1", json.getString("traceId"));
        Assert.assertEquals("sales by month", json.getString("query"));
        Assert.assertEquals("mysql", json.getString("dbType"));
        Assert.assertTrue(json.getBooleanValue("useVector"));
        Assert.assertFalse(json.getBooleanValue("useElastic"));
        Assert.assertTrue(json.containsKey("schemaInfo"));
        Assert.assertTrue(json.containsKey("currentDateInfo"));
        Assert.assertTrue(json.containsKey("recallType"));
    }

    @Test
    public void readsNl2SqlSnakeCaseResultFields() {
        Nl2SqlResult result = JSONObject.parseObject("""
                {"code":200,"request_id":"request-1","nl2sql_think":"reasoning",
                 "status":"data","err_msg":"","data":[{"query":"sales","nl2sql":"SELECT 1"}],
                 "cost_time":{"total":123,"llm":"45","dirty":"abc"}}
                """, Nl2SqlResult.class);

        Assert.assertEquals("request-1", result.getRequestId());
        Assert.assertEquals("reasoning", result.getNl2sqlThink());
        Assert.assertEquals("", result.getErrorMessage());
        Assert.assertEquals("sales", result.getData().get(0).getQuery());
        Assert.assertEquals("SELECT 1", result.getData().get(0).getNl2sql());
        Assert.assertEquals(Long.valueOf(123L), result.getCostTime().get("total"));
        Assert.assertEquals(Long.valueOf(45L), result.getCostTime().get("llm"));
        Assert.assertEquals("脏值按 0 兜底，不能打断整条结果解析",
                Long.valueOf(0L), result.getCostTime().get("dirty"));
    }

    @Test
    public void streamEventEnumKeepsLegacyWireValuesAndRejectsNullType() {
        Assert.assertEquals("CHART_DATA", DataQueryEventType.CHART_DATA.wireValue());
        Assert.assertEquals("THINK", DataQueryEventType.THINK.wireValue());
        Assert.assertEquals("READY", DataQueryEventType.READY.wireValue());
        Assert.assertEquals("ERROR", DataQueryEventType.ERROR.wireValue());
        Assert.assertEquals("DEBUG", DataQueryEventType.DEBUG.wireValue());
        Assert.assertEquals("finished_stream", DataQueryEventType.FINISHED_STREAM.wireValue());

        try {
            DataQueryStreamEvent.text(null, "boom");
            Assert.fail("eventType 必须非空");
        } catch (NullPointerException expected) {
            // expected
        }
    }
}
