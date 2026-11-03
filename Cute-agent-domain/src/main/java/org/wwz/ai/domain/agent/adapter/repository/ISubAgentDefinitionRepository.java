package org.wwz.ai.domain.agent.adapter.repository;

import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinition;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinitionRecord;
import org.wwz.ai.domain.agent.runtime.subagent.SubAgentDefinitionUpsertCommand;

import java.util.List;
import java.util.Optional;

/**
 * 可配置子 Agent 定义仓储（装配配置，非 Execution Ledger）。
 */
public interface ISubAgentDefinitionRepository {

    /** 默认分页安全上限：调用方不传 limit 时最多读取这么多行。 */
    int DEFAULT_LIST_LIMIT = 500;

    /**
     * 查询全部启用且未删除的子 Agent 定义（运行时 Registry 加载）。
     */
    List<SubAgentDefinition> listEnabled();

    /**
     * 管理端列表（未删除；含禁用）的默认分页入口。
     * <p>等价于 {@code listAll(0, DEFAULT_LIST_LIMIT)}；超出上限的配置需要通过分页重载读取。</p>
     */
    default List<SubAgentDefinitionRecord> listAll() {
        return listAll(0, DEFAULT_LIST_LIMIT);
    }

    /**
     * 按 {@code update_time DESC, id DESC} 分页读取管理端列表（未删除；含禁用）。
     * <p>实现必须自行收敛参数：{@code offset < 0} 按 0 处理，{@code limit <= 0} 取默认值，
     * 且不得把调用方传入的任意大 limit 原样下推。</p>
     */
    List<SubAgentDefinitionRecord> listAll(int offset, int limit);

    /**
     * 按 {@code agentKey} 查询定义（管理端编辑/详情）。
     *
     * <p>语义：只返回未删除（{@code deleted = 0}）的行——软删除的定义视为不存在；
     * 禁用（{@code status != 1}）的行仍会返回。{@code agentKey} 为空白时返回
     * {@link Optional#empty()}，查询前会 trim。</p>
     */
    Optional<SubAgentDefinitionRecord> findByAgentKey(String agentKey);

    /**
     * 新增定义。
     *
     * @return {@code true} 表示已插入一行；{@code false} 仅表示命令为空或 {@code agentKey} 空白。
     *         {@code agentKey} 已存在（含并发插入）时由数据库唯一键约束抛出 {@code DataAccessException}，
     *         不会返回 {@code false}；调用方若需区分「重复」应先调用 {@link #findByAgentKey(String)} 预查。
     *         软删除行不参与预查，且唯一键为 {@code (agent_key, deleted)}，同一 key 可重新插入一条未删除行。
     */
    boolean insert(SubAgentDefinitionUpsertCommand command);

    /**
     * 按 {@code agentKey} 更新未删除的定义。
     *
     * @return {@code true} 表示命中并更新了一行；{@code false} 表示命令为空/{@code agentKey} 空白，
     *         或目标行不存在、已被软删除。本方法不会恢复软删除行（SQL 条件含 {@code deleted = 0}）。
     */
    boolean updateByAgentKey(SubAgentDefinitionUpsertCommand command);

    /**
     * 按 {@code agentKey} 软删除。
     *
     * @return {@code true} 表示命中并标记了一行；{@code false} 表示 {@code agentKey} 为空
     *         或没有未删除的匹配行。
     */
    boolean softDeleteByAgentKey(String agentKey);
}
