package org.wwz.ai.domain.agent.channel.policy;

import org.junit.Test;
import org.wwz.ai.domain.agent.channel.model.ChannelAttachmentRef;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelAttachmentFormatterTest {

    @Test
    public void formatsWithClickableUrls() {
        String text = ChannelAttachmentFormatter.format(List.of(
                new ChannelAttachmentRef("report.md", "https://example.com/tool/preview/im-1/report.md"),
                new ChannelAttachmentRef("chart.png", "https://example.com/tool/preview/im-1/chart.png")
        ));
        assertEquals("\n\n📎 附件\n"
                + "1. report.md\n"
                + "   https://example.com/tool/preview/im-1/report.md\n"
                + "2. chart.png\n"
                + "   https://example.com/tool/preview/im-1/chart.png", text);
    }

    @Test
    public void formatsUnreachableFilesWithExplicitHint() {
        String text = ChannelAttachmentFormatter.format(List.of(
                new ChannelAttachmentRef("report.md", ""),
                new ChannelAttachmentRef("chart.png", "")
        ));
        assertEquals("\n\n📎 附件\n"
                + "1. report.md（当前环境文件链接不可用）\n"
                + "2. chart.png（当前环境文件链接不可用）", text);
    }

    @Test
    public void mixesReachableAndUnreachable() {
        String text = ChannelAttachmentFormatter.format(List.of(
                new ChannelAttachmentRef("a.md", "https://example.com/a.md"),
                new ChannelAttachmentRef("b.md", "")
        ));
        assertTrue(text.contains("1. a.md\n   https://example.com/a.md"));
        assertTrue(text.contains("2. b.md（当前环境文件链接不可用）"));
    }

    @Test
    public void emptyListProducesNothing() {
        // 不留空的「📎 附件」标题。
        assertEquals("", ChannelAttachmentFormatter.format(List.of()));
        assertEquals("", ChannelAttachmentFormatter.format(null));
    }

    @Test
    public void blankFileNamesAreSkippedAndNotNumbered() {
        String text = ChannelAttachmentFormatter.format(List.of(
                new ChannelAttachmentRef("  ", "https://example.com/x"),
                new ChannelAttachmentRef("real.md", "https://example.com/real.md")
        ));
        assertTrue(text.contains("1. real.md"));
        assertTrue(!text.contains("2."));
    }

    @Test
    public void allBlankFileNamesCollapseToEmpty() {
        assertEquals("", ChannelAttachmentFormatter.format(List.of(
                new ChannelAttachmentRef("", "https://example.com/x")
        )));
    }
}
