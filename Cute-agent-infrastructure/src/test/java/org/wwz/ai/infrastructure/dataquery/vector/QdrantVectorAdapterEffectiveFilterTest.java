package org.wwz.ai.infrastructure.dataquery.vector;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest;
import org.wwz.ai.domain.agent.rag.model.vector.VectorFilter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class QdrantVectorAdapterEffectiveFilterTest {

    @Test
    public void rejectsFilterWhoseConditionsAreAllDroppedByGrpc() throws Exception {
        RecordingGrpcClient client = new RecordingGrpcClient();
        QdrantVectorAdapter adapter = grpcAdapter(client);

        IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
                () -> adapter.deleteByFilter("cute_model_schema", new VectorFilter(Map.of("score", 1.5d))));

        Assert.assertTrue(error.getMessage().contains("score"));
        Assert.assertEquals(0, client.deleteCalls);
    }

    @Test
    public void keepsNormalGrpcFilterDeletion() throws Exception {
        RecordingGrpcClient client = new RecordingGrpcClient();
        QdrantVectorAdapter adapter = grpcAdapter(client);

        adapter.deleteByFilter("cute_model_schema", new VectorFilter(Map.of("tag", "x")));

        Assert.assertEquals(1, client.deleteCalls);
        Assert.assertEquals(1, client.lastFilter.getMustCount());
    }

    @Test
    public void keepsEmptyFilterRejection() {
        QdrantVectorAdapter adapter = new QdrantVectorAdapter(new QdrantProperties(), null);

        Assert.assertThrows(IllegalArgumentException.class,
                () -> adapter.deleteByFilter("cute_model_schema", null));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> adapter.deleteByFilter("cute_model_schema", new VectorFilter(Map.of())));
    }

    @Test
    public void restFilterWithEffectiveConditionStillExecutes() throws Exception {
        List<RemoteHttpRequest> requests = new ArrayList<>();
        QdrantVectorAdapter adapter = restAdapter(requests);

        adapter.deleteByFilter("cute_model_schema", new VectorFilter(Map.of("score", 1.5d)));

        Assert.assertEquals(1, requests.size());
        Assert.assertTrue(requests.get(0).getBody().contains("\"score\""));
    }

    private static QdrantVectorAdapter grpcAdapter(RecordingGrpcClient client) throws Exception {
        QdrantProperties properties = new QdrantProperties();
        properties.setHost("localhost");
        properties.setPort(6334);
        QdrantVectorAdapter adapter = new QdrantVectorAdapter(properties, null);
        Field clientField = QdrantVectorAdapter.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(adapter, client);
        return adapter;
    }

    private static QdrantVectorAdapter restAdapter(List<RemoteHttpRequest> requests) {
        QdrantProperties properties = new QdrantProperties();
        properties.setUrl("http://localhost:6333");
        properties.setPort(6333);
        RemoteHttpPort http = new RemoteHttpPort() {
            @Override
            public String execute(RemoteHttpRequest request) throws IOException {
                requests.add(request);
                return "{}";
            }
        };
        return new QdrantVectorAdapter(properties, http);
    }

    private static final class RecordingGrpcClient extends QdrantClient {
        private int deleteCalls;
        private Points.Filter lastFilter;

        private RecordingGrpcClient() {
            super(null);
        }

        @Override
        public ListenableFuture<Points.UpdateResult> deleteAsync(String collectionName, Points.Filter filter) {
            deleteCalls++;
            lastFilter = filter;
            return Futures.immediateFuture(Points.UpdateResult.getDefaultInstance());
        }
    }
}
