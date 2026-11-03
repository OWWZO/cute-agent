package org.wwz.ai.infrastructure.dataquery.embedding;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingRequest;
import org.wwz.ai.domain.agent.rag.model.embedding.TextEmbeddingResult;

import java.util.List;

public class HttpTextEmbeddingAdapterTest {

    @Test
    public void shouldConvertObjectVectorsWithoutSerializationRoundTrip() {
        DataQueryEmbeddingProperties properties = new DataQueryEmbeddingProperties();
        properties.setAgentUrl("http://embedding.local");
        RemoteHttpPort remoteHttpPort = request -> "{\"vectors\":[[1.5,2,3.25]]}";
        HttpTextEmbeddingAdapter adapter = new HttpTextEmbeddingAdapter(properties, remoteHttpPort);

        TextEmbeddingResult result = adapter.embed(new TextEmbeddingRequest(List.of("hello"), true));

        Assert.assertTrue(result.isAvailable());
        Assert.assertEquals(List.of(List.of(1.5F, 2F, 3.25F)), result.getVectors());
    }
}
