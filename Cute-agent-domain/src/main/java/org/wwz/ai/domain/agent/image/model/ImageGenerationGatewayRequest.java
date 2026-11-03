package org.wwz.ai.domain.agent.image.model;

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
 * 生图网关请求模型（Java 直连上游）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerationGatewayRequest {
    private String requestId;
    private String prompt;
    private String mode;
    @Setter(AccessLevel.NONE)
    private List<String> fileNames;

    @Setter(AccessLevel.NONE)
    private List<String> maskFileNames;
    private String fileName;
    private String fileDescription;
    private String model;
    private String size;
    private Integer n;
    private Integer timeoutSeconds;
    private Boolean stream;

    /** 返回只读副本，避免请求交给网关后列表仍被外部改写。 */
    public List<String> getFileNames() {
        return immutableCopy(fileNames);
    }

    public List<String> getMaskFileNames() {
        return immutableCopy(maskFileNames);
    }

    private static List<String> immutableCopy(List<String> values) {
        return values == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
