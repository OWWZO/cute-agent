package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.ledger.replay.ArtifactRelativePath;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * artifact 元数据路径必须能覆盖工具输出合并阶段产生的退化路径（裸文件名）。
 */
public class ArtifactRelativePathTest {

    @Test
    public void metadataPathReplacesFlattenedFileName() {
        ArtifactView artifact = ArtifactView.builder()
                .fileName("index.html")
                .metadataJson("{\"relativePath\":\"report/index.html\"}")
                .build();
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("relativePath", "index.html");
        target.put("originFileName", "index.html");

        ArtifactRelativePath.putOn(target, artifact);

        Assert.assertEquals("report/index.html", target.get("relativePath"));
        Assert.assertEquals("report/index.html", target.get("originFileName"));
    }

    @Test
    public void existingSpecificPathIsKept() {
        ArtifactView artifact = ArtifactView.builder()
                .fileName("index.html")
                .metadataJson("{\"relativePath\":\"report/index.html\"}")
                .build();
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("relativePath", "site/report/index.html");

        ArtifactRelativePath.putOn(target, artifact);

        Assert.assertEquals("site/report/index.html", target.get("relativePath"));
    }
}
