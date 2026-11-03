package org.wwz.ai.test.domain.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.service.ChatModelSchemaService;

import java.util.Set;

/**
 * 默认配置（sync_value_fields 为空）下 getter 仍会被就地 remove，不能返回不可变集合。
 */
public class ChatModelSchemaServiceSyncValueFieldsTest {

    @Test
    public void blankSyncValueFieldsAreMutable() {
        ChatModelSchemaService service = new ChatModelSchemaService(null);

        Set<String> fields = service.getSyncValueFields(null);
        // 调用方会在返回值上就地改写，getter 不能返回不可变集合。
        fields.add("column_a");
        fields.remove("column_a");

        Assert.assertTrue(fields.isEmpty());
    }
}
