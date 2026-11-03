package org.wwz.ai.domain.agent.rag.model.chatmodel;

import lombok.Data;

import java.io.Serializable;

/**
 * 问数场景的聊天模型元数据实体。
 * <p>该对象描述模型名称、业务提示词和用途，供 RAG/问数装配链读取。</p>
 */
@Data
public class ChatModelInfo implements Serializable {
    private static final long serialVersionUID = 8763697882256572393L;
    private Long id;
    private String code;
    /** 元数据类型，仅支持 {@code table} 或 {@code sql}。 */
    private String type;
    /** {@code type=table} 时为表名；{@code type=sql} 时为原始 SQL 语句。 */
    private String content;
    private String name;
    private String usePrompt;
    private String businessPrompt;
    /** 软删标记：1=有效，0=已删除（与 PO 的 {@code @TableLogic(value="1", delval="0")} 一致）。 */
    private Integer yn;
}
