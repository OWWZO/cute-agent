package org.wwz.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.po.OpenuiRepairIntentPO;

import java.util.List;

/**
 * openui 修复编辑意图表 DAO
 * @description ai_agent_openui_repair_intent 数据访问对象
 */
@Mapper
public interface IOpenuiRepairIntentDao {

    /** 删除一个 key 下的全部意图（覆盖式保存前调用）。 */
    int deleteByProgram(@Param("sessionId") String sessionId,
                        @Param("messageId") String messageId,
                        @Param("programHash") String programHash);

    /** 插入单条意图。 */
    int insert(OpenuiRepairIntentPO intent);

    /** 批量插入意图，减少覆盖式保存的数据库往返。 */
    int insertBatch(@Param("list") List<OpenuiRepairIntentPO> intents);

    /** 按 key 拉全部意图，按 intent_index 升序。 */
    List<OpenuiRepairIntentPO> listByProgram(@Param("sessionId") String sessionId,
                                             @Param("messageId") String messageId,
                                             @Param("programHash") String programHash);
}
