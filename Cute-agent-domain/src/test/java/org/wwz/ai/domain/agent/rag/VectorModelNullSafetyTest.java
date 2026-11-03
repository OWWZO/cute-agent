package org.wwz.ai.domain.agent.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.vector.VectorFilter;
import org.wwz.ai.domain.agent.rag.model.vector.VectorSearchHit;
import org.wwz.ai.domain.agent.rag.model.vector.VectorSearchRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量模型的空值边界：空过滤/空向量必须快速失败，外部 payload 的显式 null 值必须能通过。
 */
public class VectorModelNullSafetyTest {

    @Test
    public void emptyFilterShouldFailFastInsteadOfMatchingAll() {
        assertIllegalArgument(() -> new VectorFilter(null));
        assertIllegalArgument(() -> new VectorFilter(Map.of()));
    }

    @Test
    public void filterWithNullEntryShouldReportClearError() {
        Map<String, Object> must = new HashMap<>();
        must.put("modelCode", null);
        assertIllegalArgument(() -> new VectorFilter(must));
    }

    @Test
    public void hitPayloadShouldAllowExplicitNullValues() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("modelCode", "sales");
        payload.put("missingField", null);

        VectorSearchHit hit = new VectorSearchHit("id-1", 0.9F, payload);

        Assert.assertTrue(hit.getPayload().containsKey("missingField"));
        Assert.assertNull(hit.getPayload().get("missingField"));
        payload.put("changed", true);
        Assert.assertFalse(hit.getPayload().containsKey("changed"));
    }

    @Test
    public void searchRequestShouldRejectNullOrEmptyVector() {
        assertIllegalArgument(() -> new VectorSearchRequest("schema", null, 10, null, Map.of(), List.of(), null));
        assertIllegalArgument(() -> new VectorSearchRequest("schema", List.of(), 10, null, Map.of(), List.of(), null));
        assertIllegalArgument(() -> new VectorSearchRequest(
                "schema", Arrays.asList(0.1F, null), 10, null, Map.of(), List.of(), null));
    }

    private void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
