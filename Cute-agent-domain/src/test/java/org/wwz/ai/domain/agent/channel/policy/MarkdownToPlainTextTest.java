package org.wwz.ai.domain.agent.channel.policy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MarkdownToPlainTextTest {

    @Test
    public void headingsLoseHashesAndGetTheirOwnBlock() {
        String out = MarkdownToPlainText.convert("## 本周进展\n做完了 A。");
        assertTrue(out, out.contains("本周进展"));
        assertTrue(out, !out.contains("##"));
        assertTrue(out, out.startsWith("本周进展"));
    }

    @Test
    public void boldItalicAndStrikeMarkersAreRemoved() {
        assertEquals("粗体 斜体 删除线",
                MarkdownToPlainText.convert("**粗体** *斜体* ~~删除线~~"));
    }

    @Test
    public void inlineCodeKeepsItsContent() {
        assertEquals("调用 foo() 即可", MarkdownToPlainText.convert("调用 `foo()` 即可"));
    }

    @Test
    public void listMarkersSurvive() {
        String out = MarkdownToPlainText.convert("- 第一项\n- 第二项");
        assertEquals("- 第一项\n- 第二项", out);
    }

    @Test
    public void tableBecomesKeyValueLines() {
        String out = MarkdownToPlainText.convert("""
                | 指标 | 数值 |
                | --- | --- |
                | 收入 | 120 |
                | 成本 | 80 |""");
        assertTrue(out, out.contains("- 指标: 收入, 数值: 120"));
        assertTrue(out, out.contains("- 指标: 成本, 数值: 80"));
        assertTrue(out, !out.contains("|"));
    }

    @Test
    public void codeBlockIsPreservedVerbatim() {
        String markdown = """
                说明如下：
                ```python
                # 这是注释，不是标题
                x = 1 * 2
                ```
                结束。""";
        String out = MarkdownToPlainText.convert(markdown);
        assertTrue(out, out.contains("# 这是注释，不是标题"));
        assertTrue(out, out.contains("x = 1 * 2"));
        assertTrue(out, out.contains("```python"));
    }

    @Test
    public void linksBecomeTextPlusUrl() {
        assertEquals("文档 https://example.com/a",
                MarkdownToPlainText.convert("[文档](https://example.com/a)"));
    }

    @Test
    public void bareUrlLinkIsNotDuplicated() {
        assertEquals("https://example.com/a",
                MarkdownToPlainText.convert("[](https://example.com/a)"));
    }

    @Test
    public void blockquoteMarkerIsRemoved() {
        assertEquals("引用内容", MarkdownToPlainText.convert("> 引用内容"));
    }

    @Test
    public void horizontalRuleDisappearsButParagraphBreakRemains() {
        assertEquals("上\n\n下", MarkdownToPlainText.convert("上\n\n---\n\n下"));
    }

    @Test
    public void blankInputYieldsEmptyString() {
        assertEquals("", MarkdownToPlainText.convert(""));
        assertEquals("", MarkdownToPlainText.convert(null));
        assertEquals("", MarkdownToPlainText.convert("   \n\n  "));
    }

    @Test
    public void consecutiveBlankLinesCollapse() {
        String out = MarkdownToPlainText.convert("a\n\n\n\n\nb");
        assertEquals("a\n\nb", out);
    }
}
