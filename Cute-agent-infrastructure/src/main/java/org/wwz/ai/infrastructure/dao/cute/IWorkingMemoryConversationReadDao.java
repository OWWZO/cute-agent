package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.domain.agent.ledger.model.WorkingMemoryConversationRow;

import java.util.List;

/**
 * 工作记忆会话只读 DAO。
 */
@Mapper
public interface IWorkingMemoryConversationReadDao {

    List<WorkingMemoryConversationRow> queryBySessionIds(
            @Param("sessionIds") List<String> sessionIds);
}
