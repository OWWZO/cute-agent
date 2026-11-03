package org.wwz.ai.infrastructure.dao.cute;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.cute.po.SessionTodoPO;

import java.util.List;

@Mapper
public interface ISessionTodoDao {

    List<SessionTodoPO> selectBySessionId(@Param("sessionId") String sessionId);

    Integer selectMaxSeqNo(@Param("sessionId") String sessionId);

    /**
     * 唯一键为 {@code (session_id, task_id, deleted)}。同一活动行冲突时更新内容、
     * 保持 {@code deleted = 0} 并刷新 {@code update_time}；已被软删除的行不会被复用。
     */
    int upsert(SessionTodoPO row);

    int softDelete(@Param("sessionId") String sessionId, @Param("taskId") String taskId);

    int softDeleteAllBySessionId(@Param("sessionId") String sessionId);
}
