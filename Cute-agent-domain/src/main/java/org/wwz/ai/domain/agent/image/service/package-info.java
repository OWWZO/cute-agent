/**
 * 生图工作台领域服务契约（{@code image.service} 子包）。
 * <p>
 * 本包只声明服务接口：执行内核（{@code IImageGenerationExecutionKernel}）、
 * 批次持久化（{@code IImageGenerationBatchPersistenceService}）与工作台入口
 * （{@code IWorkspaceImageGenerationService}）。领域模型在兄弟包
 * {@code org.wwz.ai.domain.agent.image.model}，下游调用端口在
 * {@code org.wwz.ai.domain.agent.image.port}。
 * <p>
 * 技术实现：{@code IWorkspaceImageGenerationService} 由
 * {@code org.wwz.ai.infrastructure.imagegeneration} 实现；应用编排在
 * {@code org.wwz.ai.application.agent.image}，HTTP 适配在
 * {@code org.wwz.ai.trigger.http.agent.image}。
 */
package org.wwz.ai.domain.agent.image.service;
