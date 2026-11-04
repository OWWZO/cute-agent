package org.wwz.ai.infrastructure.dataquery.vector;

import io.qdrant.client.grpc.JsonWithInt;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class QdrantVectorAdapterPayloadTest {

    @Test
    public void nullListItemsAreSkippedBeforeBuildingProtobufValue() throws Exception {
        QdrantVectorAdapter adapter = new QdrantVectorAdapter(new QdrantProperties(), null);
        Method convert = QdrantVectorAdapter.class.getDeclaredMethod(
                "toQdrantValue", Object.class, int.class);
        convert.setAccessible(true);

        JsonWithInt.Value value = (JsonWithInt.Value) convert.invoke(
                adapter, Arrays.asList("a", null), 0);

        assertEquals(1, value.getListValue().getValuesCount());
    }
}
