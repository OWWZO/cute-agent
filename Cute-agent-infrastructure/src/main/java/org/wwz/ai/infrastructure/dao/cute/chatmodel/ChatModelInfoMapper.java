package org.wwz.ai.infrastructure.dao.cute.chatmodel;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.po.cute.chatmodel.ChatModelInfoPO;

/**
 * 问数模型信息 DAO。
 */
@Mapper
public interface ChatModelInfoMapper extends BaseMapper<ChatModelInfoPO> {

    /**
     * DataAgent force-refresh 需要真正清空旧版本元数据，避免逻辑删除历史越积越多。
     *
     * <p>这是绕过 {@code @TableLogic} 的不可逆物理删除：会删除该 code 的全部版本。
     * 调用方应与 schema 清理处于同一事务，并核对返回的影响行数。</p>
     */
    @Delete("DELETE FROM chat_model_info WHERE code = #{modelCode}")
    int deletePhysicalByCode(@Param("modelCode") String modelCode);
}
