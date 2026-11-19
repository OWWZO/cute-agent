package org.wwz.ai.test.domain.ledger;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.image.model.WorkspaceImageFile;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolFileRef;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolFileRefMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 通用文件信息映射：调用方只提供 ossUrl/domainUrl 时也要补齐下载/预览地址。
 */
public class ToolFileRefMapperTest {

    @Test
    public void genericFileInfoDerivesDownloadAndPreviewUrls() {
        List<ToolFileRef> refs = ToolFileRefMapper.fromGenericFileInfo(List.of(Map.of(
                "fileName", "report.pdf",
                "ossUrl", "https://oss.example/report.pdf",
                "domainUrl", "https://cdn.example/report.pdf",
                "fileSize", "128"
        )));

        Assert.assertEquals(1, refs.size());
        ToolFileRef ref = refs.get(0);
        Assert.assertEquals("https://oss.example/report.pdf", ref.getDownloadUrl());
        Assert.assertEquals("https://cdn.example/report.pdf", ref.getPreviewUrl());
        Assert.assertEquals(Long.valueOf(128L), ref.getFileSize());
    }

    @Test
    public void explicitDownloadAndPreviewUrlsWinOverFallback() {
        List<ToolFileRef> refs = ToolFileRefMapper.fromGenericFileInfo(List.of(Map.of(
                "fileName", "report.pdf",
                "ossUrl", "https://oss.example/report.pdf",
                "domainUrl", "https://cdn.example/report.pdf",
                "downloadUrl", "https://download.example/report.pdf",
                "previewUrl", "https://preview.example/report.pdf"
        )));

        Assert.assertEquals("https://download.example/report.pdf", refs.get(0).getDownloadUrl());
        Assert.assertEquals("https://preview.example/report.pdf", refs.get(0).getPreviewUrl());
    }

    @Test
    public void workspaceImageFilesMapAllStableFieldsAndSkipNullEntries() {
        List<ToolFileRef> refs = ToolFileRefMapper.fromWorkspaceImageFiles(Arrays.asList(
                WorkspaceImageFile.builder()
                        .fileName("cat.png")
                        .ossUrl("https://oss.example/cat.png")
                        .domainUrl("https://cdn.example/cat.png")
                        .downloadUrl("https://download.example/cat.png")
                        .previewUrl("https://preview.example/cat.png")
                        .fileSize(256L)
                        .mimeType("image/png")
                        .build(),
                null
        ));

        Assert.assertEquals(1, refs.size());
        ToolFileRef ref = refs.get(0);
        Assert.assertEquals("cat.png", ref.getFileName());
        Assert.assertEquals("https://oss.example/cat.png", ref.getOssUrl());
        Assert.assertEquals("https://cdn.example/cat.png", ref.getDomainUrl());
        Assert.assertEquals("https://download.example/cat.png", ref.getDownloadUrl());
        Assert.assertEquals("https://preview.example/cat.png", ref.getPreviewUrl());
        Assert.assertEquals(Long.valueOf(256L), ref.getFileSize());
        Assert.assertEquals("image/png", ref.getMimeType());
    }
}
