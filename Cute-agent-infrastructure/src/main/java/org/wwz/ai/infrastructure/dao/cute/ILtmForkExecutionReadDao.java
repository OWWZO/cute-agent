package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.domain.agent.memory.ltm.LtmForkExecutionEvent;

import java.util.List;

/**
 * LTM fork 执行审计只读 DAO。
 */
@Mapper
public interface ILtmForkExecutionReadDao {

    List<LtmForkExecutionEvent> queryByTriggerRequestIds(
            @Param("triggerRequestIds") List<String> triggerRequestIds);
}
