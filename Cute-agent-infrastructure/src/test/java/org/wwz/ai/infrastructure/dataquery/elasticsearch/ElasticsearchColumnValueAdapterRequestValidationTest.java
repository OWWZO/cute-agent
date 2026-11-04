package org.wwz.ai.infrastructure.dataquery.elasticsearch;

import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.column.ColumnValueSearchRequest;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElasticsearchColumnValueAdapterRequestValidationTest {

    @Test
    public void rejectsRequestsThatWouldFailInsideElasticsearchBuilders() {
        assertFalse(ElasticsearchColumnValueAdapter.isValidRequest(null));
        assertFalse(ElasticsearchColumnValueAdapter.isValidRequest(
                new ColumnValueSearchRequest(" ", List.of("model"), 10)));
        assertFalse(ElasticsearchColumnValueAdapter.isValidRequest(
                new ColumnValueSearchRequest("query", List.of(), 10)));
        assertFalse(ElasticsearchColumnValueAdapter.isValidRequest(
                new ColumnValueSearchRequest("query", List.of("model"), 0)));
        assertTrue(ElasticsearchColumnValueAdapter.isValidRequest(
                new ColumnValueSearchRequest("query", List.of("model"), 10)));
    }
}
