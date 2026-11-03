package org.wwz.ai.infrastructure.dao.po;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * ai_agent_tool_output_canvas_publish 的持久化对象。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ToolOutputCanvasPublishPO extends AbstractToolOutputPO {

    private String title;

    /** 画布发布模式，沿用领域工具输出。 */
    private String mode;

    private String primaryFileName;
    private String previewUrl;
    private String downloadUrl;

    /** 是否通知前端打开预览面板，0/1；null 表示未记录。 */
    private Integer openInPanel;

    /** 是否由参数修复后仍成功发布，0/1；null 表示未记录。 */
    private Integer salvaged;
}
