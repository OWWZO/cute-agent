package org.wwz.ai.domain.agent.image.service;

import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputNames;

/**
 * 生图工作台共享常量。
 */
public final class ImageGenerationWorkspaceConstants {

    private ImageGenerationWorkspaceConstants() {
    }

    /** 与 ledger/tool-output 投影共用的工具名，保持单一来源。 */
    public static final String WORKSPACE_TOOL_NAME = ToolOutputNames.IMAGE_GENERATION;
    public static final String WORKSPACE_TOOL_CALL_ID_PREFIX = "workspace-image-generation-";
}
