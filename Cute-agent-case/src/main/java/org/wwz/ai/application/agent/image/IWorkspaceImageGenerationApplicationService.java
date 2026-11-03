package org.wwz.ai.application.agent.image;

import org.wwz.ai.application.agent.image.command.WorkspaceImageGenerationCommand;
import org.wwz.ai.application.agent.image.command.WorkspaceImageHistoryQuery;
import org.wwz.ai.application.agent.image.result.WorkspaceImageGenerationView;
import org.wwz.ai.application.agent.image.result.WorkspaceImageHistoryPageView;

/**
 * 生图工作台用例入口契约。
 * <p>实现由 Spring 单例承载，方法本身不保存请求级状态，可按并发请求调用；幂等性和资源
 * 约束由 {@code generate} 的具体实现决定。</p>
 */
public interface IWorkspaceImageGenerationApplicationService {

    /**
     * 发起一次生图工作台请求。
     * <p>{@code command} 在正常调用路径必须非 null，且 prompt 不能为空白；当前实现会对
     * 非法输入抛 {@link IllegalArgumentException}，上游或基础设施失败则抛运行时异常。</p>
     * <p>{@code requestId} 是对齐命令、结果和历史的关联键，但当前实现不按它去重：
     * 重复提交同一 requestId 不保证复用已有结果，调用方应避免重复触发。</p>
     * <p>正常成功路径返回非 null；映射层在当前领域服务返回 null 时也会返回 null。</p>
     */
    WorkspaceImageGenerationView generate(WorkspaceImageGenerationCommand command);

    /**
     * 分页查询工作台生图历史。
     * <p>{@code query} 可以为 null，此时当前实现按第 1 页、每页 10 条查询。</p>
     * <p>非 null 时 {@code pageNo} 从 1 开始；当前实现会把小于 1 的页码归一到 1，
     * 把小于等于 0 的 pageSize 归一到 10，并把 pageSize 上限限制为 50。调用方仍应
     * 主动传入合法范围，避免依赖实现侧的容错。</p>
     */
    WorkspaceImageHistoryPageView queryHistory(WorkspaceImageHistoryQuery query);
}
