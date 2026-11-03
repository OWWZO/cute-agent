package org.wwz.ai.test.domain.rag;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.rag.model.vector.VectorFilter;
import org.wwz.ai.domain.agent.rag.model.vector.VectorPoint;
import org.wwz.ai.domain.agent.rag.port.VectorIndexAdminPort;
import org.wwz.ai.domain.agent.rag.service.VectorService;

import java.util.List;
import java.util.Map;

/**
 * 空过滤在向量库里等价于 match-all；入口必须在触达端口前拒绝，避免误删整个集合。
 */
public class VectorServiceDeleteGuardTest {

    @Test
    public void rejectsNullFilter() {
        RecordingAdminPort port = new RecordingAdminPort();
        VectorService service = service(port);

        Assert.assertThrows(IllegalArgumentException.class, () -> service.deleteVector("cute_model_schema", (Map<String, Object>) null));
        Assert.assertFalse(port.called);
    }

    @Test
    public void rejectsEmptyFilterBeforeCallingPort() {
        RecordingAdminPort port = new RecordingAdminPort();
        VectorService service = service(port);

        Assert.assertThrows(IllegalArgumentException.class, () -> service.deleteVector("cute_model_schema", Map.of()));
        Assert.assertFalse("空过滤不得触达向量存储", port.called);
    }

    @Test
    public void delegatesNonEmptyFilter() {
        RecordingAdminPort port = new RecordingAdminPort();
        VectorService service = service(port);

        Assert.assertTrue(service.deleteVector("cute_model_schema", Map.of("modelCode", "sales")));
        Assert.assertTrue(port.called);
        Assert.assertEquals("sales", port.filter.getMust().get("modelCode"));
    }

    private static VectorService service(VectorIndexAdminPort port) {
        VectorService service = new VectorService();
        service.setVectorIndexAdminPort(port);
        return service;
    }

    private static final class RecordingAdminPort implements VectorIndexAdminPort {
        private boolean called;
        private VectorFilter filter;

        @Override
        public boolean collectionExists(String collectionName) {
            return false;
        }

        @Override
        public void createCollection(String collectionName, int dimension) {
        }

        @Override
        public void recreateCollection(String collectionName, int dimension) {
        }

        @Override
        public void upsert(String collectionName, List<VectorPoint> points) {
        }

        @Override
        public void deleteByIds(String collectionName, List<String> ids) {
        }

        @Override
        public void deleteByFilter(String collectionName, VectorFilter filter) {
            this.called = true;
            this.filter = filter;
        }
    }
}
