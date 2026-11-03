package org.wwz.ai.domain.agent.image.service;

import org.wwz.ai.domain.agent.image.model.ImageGenerationExecuteCommand;
import org.wwz.ai.domain.agent.image.model.ImageGenerationExecutionResult;

/**
 * 生图执行内核。
 * <p>实现必须无状态且线程安全：内核以实现类（当前为 Spring 单例）被并发生图请求共享，
 * 不得持有按请求变化且未做同步的实例状态。</p>
 */
public interface IImageGenerationExecutionKernel {

    /**
     * 执行一次生图请求：补齐默认尺寸/批次/超时，并把下游文件归一化为工作台文件模型。
     *
     * @throws IllegalArgumentException {@code command} 为 {@code null} 或 {@code prompt} 为空白
     * @throws RuntimeException         网关调用失败、超时等下游错误原样向上抛出
     * @return 非 {@code null}；网关返回 {@code null} 或空文件列表时，返回 {@code files} 为空的结果
     */
    ImageGenerationExecutionResult execute(ImageGenerationExecuteCommand command);
}
