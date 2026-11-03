package org.wwz.ai.domain.agent.runtime.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * 文件名归一化必须消灭路径分隔符，防止上游把文件名当路径拼接。
 */
public class StringUtilRemoveSpecialCharsTest {

    @Test
    public void pathSeparatorsBecomeUnderscores() {
        Assert.assertEquals(".._.._x", StringUtil.removeSpecialChars("../../x"));
        Assert.assertEquals("a_b_c", StringUtil.removeSpecialChars("a/b\\c"));
        Assert.assertEquals("C:_Windows_win.ini", StringUtil.removeSpecialChars("C:\\Windows\\win.ini"));
    }

    @Test
    public void normalizedNameContainsNoPathSeparator() {
        String name = StringUtil.removeSpecialChars("../../etc/passwd");
        Assert.assertFalse(name.contains("/"));
        Assert.assertFalse(name.contains("\\"));
    }
}
