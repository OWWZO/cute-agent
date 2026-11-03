package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.domain.agent.memory.WorkingMemoryTurn;

@Mapper
public interface IWorkingMemoryTurnDao {

    int insertTurn(WorkingMemoryTurn turn);

    Integer selectMaxTurnSeq(@Param("sessionId") String sessionId,
                             @Param("memoryScope") String memoryScope);

    WorkingMemoryTurn selectByRequestId(@Param("requestId") String requestId);

    /**
     * 查询指定会话与作用域内全部 READY turns，按 {@code turn_seq} 升序返回。
     * 调用方依赖该顺序重建消息序列。
     */
    java.util.List<WorkingMemoryTurn> selectReadyBySessionIdAndScope(@Param("sessionId") String sessionId,
                                                                     @Param("memoryScope") String memoryScope);

    /**
     * 将指定 scope 内全部 READY turns 标为 INVALID（压缩后投影替换）。
     */
    int markReadyInvalidBySessionIdAndScope(@Param("sessionId") String sessionId,
                                            @Param("memoryScope") String memoryScope);
}
