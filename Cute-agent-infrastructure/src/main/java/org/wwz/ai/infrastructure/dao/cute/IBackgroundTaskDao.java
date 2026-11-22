package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.cute.po.BackgroundTaskPO;

import java.util.List;

@Mapper
public interface IBackgroundTaskDao {

    List<BackgroundTaskPO> selectBySessionId(@Param("sessionId") String sessionId);

    BackgroundTaskPO selectBySessionAndTaskId(@Param("sessionId") String sessionId,
                                              @Param("taskId") String taskId);

    BackgroundTaskPO selectActiveByChildSessionId(@Param("childSessionId") String childSessionId);

    List<BackgroundTaskPO> selectRunningByOwnerAgentId(@Param("rootSessionId") String rootSessionId,
                                                       @Param("ownerAgentId") String ownerAgentId);

    int upsert(BackgroundTaskPO row);

    /**
     * 启动对账：扫「仍 running 且早于宽限窗之前启动」的后台任务登记行。
     * <p>
     * 这些行的执行体（子 Agent Future / shell 进程）随进程消亡，永远不会有终态回调。
     * 运行中的 registry hydrate 必须保留原始状态；只有本启动对账查询可以证明进程
     * 重启后的孤儿并以 {@code lost} 语义落库。
     */
    List<BackgroundTaskPO> selectOrphanedRunning(@Param("startedBeforeMs") long startedBeforeMs,
                                                 @Param("limit") int limit);
}
