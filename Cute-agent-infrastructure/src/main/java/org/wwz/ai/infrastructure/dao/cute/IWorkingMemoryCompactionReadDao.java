package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.domain.agent.memory.WorkingMemoryCompactionEvent;

import java.util.List;

/**
 * 工作记忆压缩审计只读 DAO。
 */
@Mapper
public interface IWorkingMemoryCompactionReadDao {

    List<WorkingMemoryCompactionEvent> queryByTriggerRequestIds(
            @Param("triggerRequestIds") List<String> triggerRequestIds);
}
