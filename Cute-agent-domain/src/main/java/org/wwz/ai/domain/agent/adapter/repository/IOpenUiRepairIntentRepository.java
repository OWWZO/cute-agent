package org.wwz.ai.domain.agent.adapter.repository;

import org.wwz.ai.domain.agent.runtime.openui.OpenUiRepairIntent;

import java.util.List;

/**
 * openui 修复编辑意图的仓储端口（plans/038 §10）。
 *
 * <p>key = sessionId + messageId + programHash：意图的 SEARCH 针对特定原文，
 * 原文 hash 是重放时判断"意图是否还有效"的依据。messageId 沿用会话的 requestId。</p>
 */
public interface IOpenUiRepairIntentRepository {

    /**
     * 覆盖式保存一个 key 下的全部意图（先删旧再插新，intent_index 按列表顺序 0..n）。
     * 只应保存前端验收通过的意图。
     *
     * <p>原子性：当前实现在单个事务内完成删除与插入，插入失败会整体回滚。
     * {@code intents} 为 null 或空列表表示清空该 key。列表中的 null 元素与
     * {@code searchText} 为空的元素会被跳过，但 {@code intent_index} 仍取元素在原列表中的
     * 下标（因此写出的下标可能不连续）；重复意图不做去重。</p>
     *
     * @param model 随每一行一起持久化，可为 null；覆盖保存后同一 key 下只保留本次传入的值
     */
    void saveAll(String sessionId, String messageId, String programHash,
                 List<OpenUiRepairIntent> intents, String model);

    /**
     * 按 key 拉全部意图，按 intent_index 升序；无记录返回空列表。
     * <p>三个 key 参数任一为 null 时，当前实现直接返回空列表。</p>
     */
    List<OpenUiRepairIntent> listByProgram(String sessionId, String messageId, String programHash);
}
