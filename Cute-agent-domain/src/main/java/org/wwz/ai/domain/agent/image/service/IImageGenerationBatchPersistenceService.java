package org.wwz.ai.domain.agent.image.service;

import org.wwz.ai.domain.agent.image.model.ImageGenerationExecutionResult;

/**
 * 生图批次持久化服务。
 */
public interface IImageGenerationBatchPersistenceService {

    /**
     * 持久化一次工作台生图请求对应的单个批次（不是向已有批次追加；{@code result.getFiles()}
     * 是该批次的多文件产出）。
     * <p>{@code requestId} 是批次的权威键：实现以该参数为准，不读取
     * {@link ImageGenerationExecutionResult#getRequestId()}；调用方必须保证二者描述同一批次。</p>
     */
    void persistWorkspaceBatch(String requestId, ImageGenerationExecutionResult result);
}
