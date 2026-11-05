package org.wwz.ai.infrastructure.adapter.port;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.cli.CliInvocation;
import org.wwz.ai.domain.agent.adapter.port.cli.CliResult;

import java.util.List;

public class HostProcessCliAdapterCharacterBudgetTest {

    @Test
    public void shouldApplyMaxOutputCharsAsCharacters() {
        CliResult english = exec("abcdef", 5);
        CliResult chinese = exec("中文中文中文", 5);

        Assert.assertEquals("abcde", english.getStdout());
        Assert.assertEquals("中文中文中", chinese.getStdout());
        Assert.assertEquals(5, chinese.getStdout().length());
        Assert.assertTrue(english.isTruncated());
        Assert.assertTrue(chinese.isTruncated());
    }

    @Test
    public void shouldNotSplitSurrogatePairAtCharacterLimit() {
        CliResult result = exec("😀😀😀", 3);

        Assert.assertEquals("😀", result.getStdout());
        String value = result.getStdout();
        Assert.assertFalse(Character.isHighSurrogate(value.charAt(value.length() - 1)));
        Assert.assertFalse(Character.isLowSurrogate(value.charAt(0)));
        Assert.assertTrue(result.isTruncated());
    }

    private CliResult exec(String output, int maxChars) {
        return new HostProcessCliAdapter().exec(CliInvocation.builder()
                .tool("node")
                .args(List.of("-e", "process.stdout.write(" + jsString(output) + ")"))
                .timeoutMs(10_000)
                .maxOutputChars(maxChars)
                .capture("stdout")
                .build());
    }

    private String jsString(String value) {
        return "'" + value.replace("'", "\\'") + "'";
    }
}
