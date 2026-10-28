package org.wwz.ai.domain.agent.channel.policy;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TextChunkerTest {

    @Test
    public void shortTextIsNotSplit() {
        assertEquals(List.of("你好"), TextChunker.split("你好"));
    }

    @Test
    public void blankTextYieldsNothing() {
        assertEquals(List.of(), TextChunker.split(""));
        assertEquals(List.of(), TextChunker.split(null));
        assertEquals(List.of(), TextChunker.split("   "));
    }

    @Test
    public void everyChunkFitsUnderLimit() {
        String text = "这是一段用于测试切块的文字。".repeat(1000);
        List<String> chunks = TextChunker.split(text);
        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= TextChunker.MAX_LENGTH);
        }
    }

    @Test
    public void numberingIsPresentWhenSplitAndAbsentOtherwise() {
        List<String> chunks = TextChunker.split("x".repeat(9000), 4000);
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).startsWith("(1/3)"));
        assertTrue(chunks.get(1).startsWith("(2/3)"));
        assertTrue(chunks.get(2).startsWith("(3/3)"));
    }

    @Test
    public void noContentIsLost() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            source.append("第").append(i).append("行内容。\n");
        }
        List<String> chunks = TextChunker.split(source.toString(), 2000);
        assertTrue(chunks.size() > 1);
        StringBuilder rejoined = new StringBuilder();
        for (String chunk : chunks) {
            rejoined.append(chunk.replaceFirst("^\\(\\d+/\\d+\\)\n", ""));
        }
        // 切点会吞掉边界处的换行，所以只比较「非空白字符」是否完整。
        assertEquals(dropWhitespace(source.toString()), dropWhitespace(rejoined.toString()));
    }

    private static String dropWhitespace(String value) {
        return value.replaceAll("\\s+", "");
    }

    @Test
    public void splitsOnParagraphBoundaryWhenAvailable() {
        String block = "a".repeat(1500);
        String text = block + "\n\n" + block + "\n\n" + block;
        List<String> chunks = TextChunker.split(text, 2000);
        assertTrue(chunks.size() >= 2);
        // 第一块不应把第二段劈开——段落在边界处结束。
        assertTrue(chunks.get(0).endsWith(block));
    }

    @Test
    public void reopensFenceWhenCodeBlockSpansChunks() {
        String code = "x = 1;\n".repeat(1000);
        String text = "```java\n" + code + "```";
        List<String> chunks = TextChunker.split(text, 1000);
        assertTrue(chunks.size() > 1);
        // 后续块必须以重开的 fence 开头，否则 QQ 里会看到裸代码。
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i), chunks.get(i).contains("```"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void absurdlySmallLimitIsRejected() {
        TextChunker.split("x".repeat(100), 10);
    }
}
