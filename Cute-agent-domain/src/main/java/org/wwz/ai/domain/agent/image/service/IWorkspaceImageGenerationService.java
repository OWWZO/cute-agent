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
     */
    WorkspaceImageGenerationResult generate(WorkspaceImageGenerationCommand command);

    /**
     * 分页查询工作台生图历史。
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
