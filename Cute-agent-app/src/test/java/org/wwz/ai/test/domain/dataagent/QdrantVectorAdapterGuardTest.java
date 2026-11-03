package org.wwz.ai.test.domain.dataagent;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.vector.VectorFilter;
import org.wwz.ai.infrastructure.dataquery.vector.QdrantProperties;
import org.wwz.ai.infrastructure.dataquery.vector.QdrantVectorAdapter;

import java.util.List;
import java.util.Map;

/**
 * 空过滤/空 id 删除在 Qdrant 里等价于 match-all，适配器必须在触网前拒绝。
 */
public class QdrantVectorAdapterGuardTest {

    @Test
    public void rejectsNullFilter() {
        Assert.assertThrows(IllegalArgumentException.class,
                () -> adapter().deleteByFilter("cute_model_schema", null));
    }

    @Test
    public void rejectsEmptyFilter() {
        Assert.assertThrows(IllegalArgumentException.class,
                () -> adapter().deleteByFilter("cute_model_schema", new VectorFilter(Map.of())));
    }

    @Test
    public void rejectsEmptyIdList() {
        Assert.assertThrows(IllegalArgumentException.class,
                () -> adapter().deleteByIds("cute_model_schema", List.of()));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> adapter().deleteByIds("cute_model_schema", null));
    }

    private static QdrantVectorAdapter adapter() {
        return new QdrantVectorAdapter(new QdrantProperties(), null);
    }
}
