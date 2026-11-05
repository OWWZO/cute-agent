package org.wwz.ai.infrastructure.dao.cute.chatmodel;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.wwz.ai.infrastructure.dao.po.cute.chatmodel.ChatModelSchemaPO;

import java.util.List;

/**
 * 问数模型字段 DAO。
 */
@Mapper
public interface ChatModelSchemaMapper extends BaseMapper<ChatModelSchemaPO> {

    /**
     * 物理删除该模型的全部 schema 行（包括当前行），避免逻辑删除导致重复历史行持续累计。
     */
    @Delete("DELETE FROM chat_model_schema WHERE model_code = #{modelCode}")
    int deletePhysicalByModelCode(@Param("modelCode") String modelCode);

    @Insert({
            "<script>",
            "INSERT INTO chat_model_schema",
            "(id, model_code, column_id, column_name, column_comment, few_shot, data_type,",
            " synonyms, vector_uuid, default_recall, analyze_suggest, yn) VALUES",
            "<foreach collection='list' item='item' separator=','>",
            "(#{item.id}, #{item.modelCode}, #{item.columnId}, #{item.columnName}, #{item.columnComment},",
            " #{item.fewShot}, #{item.dataType}, #{item.synonyms}, #{item.vectorUuid},",
            " COALESCE(#{item.defaultRecall}, 0), COALESCE(#{item.analyzeSuggest}, 0),",
            " COALESCE(#{item.yn}, 1))",
            "</foreach>",
            "</script>"
    })
    int insertBatch(@Param("list") List<ChatModelSchemaPO> schemaList);
}
