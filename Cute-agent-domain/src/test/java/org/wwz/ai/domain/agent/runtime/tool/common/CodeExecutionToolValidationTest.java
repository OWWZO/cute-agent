package org.wwz.ai.domain.agent.runtime.tool.common;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

/**
 * code_execution 转发给 Runner 之前，fileNames / workspaceFile 必须在本侧收敛。
 */
public class CodeExecutionToolValidationTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void acceptsWorkspaceRelativeAndPublicUrls() throws Exception {
        Path root = folder.newFolder("ws").toPath();

        List<String> values = CodeExecutionTool.validateFileNames(
                List.of("in/data.csv", "https://8.8.8.8/data.csv"), root);

        Assert.assertEquals(List.of("in/data.csv", "https://8.8.8.8/data.csv"), values);
    }

    @Test
    public void rejectsInternalUrls() throws Exception {
        Path root = folder.newFolder("ws").toPath();
        assertFileNamesRejected(root, "http://127.0.0.1/x");
        assertFileNamesRejected(root, "http://169.254.169.254/latest/meta-data");
        assertFileNamesRejected(root, "http://[::1]/x");
        assertFileNamesRejected(root, "http://0177.0.0.1/x");
    }

    @Test
    public void rejectsAbsolutePathsOutsideWorkspace() throws Exception {
        Path root = folder.newFolder("ws").toPath();
        assertFileNamesRejected(root, "/etc/passwd");
        assertFileNamesRejected(root, "C:\\Windows\\win.ini");
    }

    @Test
    public void rejectsWorkspaceFileTraversal() {
        Assert.assertNull(CodeExecutionTool.validateWorkspaceFile(" "));
        Assert.assertEquals("scripts/run.py", CodeExecutionTool.validateWorkspaceFile("scripts/run.py"));
        assertWorkspaceFileRejected("../secret.py");
        assertWorkspaceFileRejected("a/../../secret.py");
        assertWorkspaceFileRejected("/etc/passwd");
        assertWorkspaceFileRejected("C:\\Windows\\win.ini");
    }

    private static void assertFileNamesRejected(Path root, String value) {
        try {
            CodeExecutionTool.validateFileNames(List.of(value), root);
            Assert.fail("should reject: " + value);
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }

    private static void assertWorkspaceFileRejected(String value) {
        try {
            CodeExecutionTool.validateWorkspaceFile(value);
            Assert.fail("should reject: " + value);
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }
}
