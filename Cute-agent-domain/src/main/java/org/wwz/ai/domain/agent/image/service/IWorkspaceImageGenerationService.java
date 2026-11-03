package org.wwz.ai.domain.agent.image.service;

import org.wwz.ai.domain.agent.image.model.WorkspaceImageGenerationCommand;
import org.wwz.ai.domain.agent.image.model.WorkspaceImageGenerationHistoryPage;
import org.wwz.ai.domain.agent.image.model.WorkspaceImageGenerationResult;

/**
 * 生图工作台服务。
 */
public interface IWorkspaceImageGenerationService {

    /**
     * 发起一次生图工作台请求。
     * <p>同步执行：包含上游生图调用与批次持久化，可能长时间阻塞。</p>
     * <p>失败语义：{@code command} 为 {@code null}、{@code prompt} 为空白、图生图模式没有参考图、
     * {@code maskFileNames} 多于 {@code fileNames} 时抛 {@link IllegalArgumentException}；
     * 上游未返回可识别图片时抛 {@link IllegalStateException}；网关与持久化的运行时异常原样向上抛出。
     * 成功时返回非 {@code null} 结果。</p>
     * <p>{@code command.getRequestId()} 只作为批次键：为空时自动生成 UUID，不做去重；
     * 相同 requestId 重复提交会再次调用上游，并在持久化阶段因唯一键冲突失败。</p>
     */
    WorkspaceImageGenerationResult generate(WorkspaceImageGenerationCommand command);

    /**
     * 分页查询工作台生图历史。
     *
     * <p>{@code pageNo} 从 1 开始，小于 1 的值按 1 处理；{@code pageSize} 小于等于 0 时按 10 处理，
     * 大于 50 时按 50 截断。越界值由实现钳制到有效区间，不抛异常。</p>
     *
     * <p><b>当前契约没有 workspace/user/tenant 维度</b>：历史行来自
     * {@code ai_agent_tool_output_image_generation} 的 {@code request_source=workspace}，
     * 该表与工作台生成请求都没有记录 userId，因此实现无法在查询里加归属条件。</p>
     * <p>安全要求：调用方（HTTP 入口）必须先完成登录鉴权，并把该接口视为<b>全局工作台历史</b>；
     * 若要支持多租户隔离，必须先把 userId 落到生成批次上，再扩展本契约的签名与查询条件，
     * 不能只在方法签名上挂一个参数。</p>
     */
    WorkspaceImageGenerationHistoryPage queryHistory(int pageNo, int pageSize);
}
