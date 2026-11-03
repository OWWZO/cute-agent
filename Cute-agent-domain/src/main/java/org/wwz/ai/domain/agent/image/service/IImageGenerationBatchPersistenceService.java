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
     * <p>参数校验：{@code requestId} 为空白、{@code result} 为 {@code null}、
     * {@code result.getFiles()} 为空，或过滤后没有任何带文件名的有效文件时，
     * 抛 {@link IllegalArgumentException}。</p>
     * <p>原子性：实现必须在同一事务内写入 tool output 与 artifact，任一步失败都抛出异常并整体回滚。
     * 本方法不是幂等接口：同一 {@code requestId} 重复调用会因唯一键冲突失败，而不是静默跳过。</p>
     */
    void persistWorkspaceBatch(String requestId, ImageGenerationExecutionResult result);
}
