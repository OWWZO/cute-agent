package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.vector.VectorFilter;
import org.wwz.ai.domain.agent.rag.model.vector.VectorPoint;

import java.util.List;

/**
 * 向量集合与点数据管理端口。
 */
public interface VectorIndexAdminPort {

    boolean collectionExists(String collectionName) throws Exception;

    void createCollection(String collectionName, int dimension) throws Exception;

    void recreateCollection(String collectionName, int dimension) throws Exception;

    void upsert(String collectionName, List<VectorPoint> points) throws Exception;

    void deleteByIds(String collectionName, List<String> ids) throws Exception;

    /**
     * 按过滤条件删除。实现应在返回前完成删除请求提交，不得在本地异步排队；
     * 删除是否立即可被后续查询观察到仍取决于存储端一致性。
     */
    void deleteByFilter(String collectionName, VectorFilter filter) throws Exception;
}
