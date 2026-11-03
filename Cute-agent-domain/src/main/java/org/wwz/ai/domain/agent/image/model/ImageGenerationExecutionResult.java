package org.wwz.ai.domain.agent.image.model;

import com.alibaba.fastjson.JSONObject;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 统一的生图执行结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationExecutionResult {

    private String requestId;

    private String prompt;

    private String mode;

    private String summary;

    private String size;

    private Integer batchCount;

    private Integer sourceImageCount;

    private Integer maskImageCount;

    private Boolean usedFallback;

    private JSONObject rawResponse;

    @Setter(AccessLevel.NONE)
    @Builder.Default
    private List<WorkspaceImageFile> files = new ArrayList<>();

    /** 返回只读副本，避免调用方改写执行结果已登记的产出物。 */
    public List<WorkspaceImageFile> getFiles() {
        return files == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(files));
    }
}
