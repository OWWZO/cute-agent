package org.wwz.ai.test.domain.rag;

import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.BeanUtils;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelSchema;
import org.wwz.ai.domain.agent.rag.model.vector.VectorSchemaPayload;

/**
 * 召回开关字段必须能从 int 源模型复制进 payload，并出现在向量载荷 JSON 中。
 */
public class VectorSchemaPayloadTest {

    @Test
    public void recallFlagsAreCopiedFromPrimitiveIntModel() {
        ChatModelSchema source = new ChatModelSchema();
        source.setModelCode("m1");
        source.setColumnId("c1");
        source.setDefaultRecall(1);
        source.setAnalyzeSuggest(-1);

        VectorSchemaPayload payload = new VectorSchemaPayload();
        BeanUtils.copyProperties(source, payload);

        Assert.assertEquals(Integer.valueOf(1), payload.getDefaultRecall());
        Assert.assertEquals(Integer.valueOf(-1), payload.getAnalyzeSuggest());

        JSONObject json = JSONObject.parseObject(JSONObject.toJSONString(payload));
        Assert.assertTrue("召回契约字段不能被 null 过滤掉", json.containsKey("defaultRecall"));
        Assert.assertTrue("召回契约字段不能被 null 过滤掉", json.containsKey("analyzeSuggest"));
        Assert.assertEquals(Integer.valueOf(1), json.getInteger("defaultRecall"));
    }
}
