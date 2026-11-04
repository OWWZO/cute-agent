package org.wwz.ai.application.agent.file;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.wwz.ai.application.agent.authorization.ConversationSessionAuthorizationService;
import org.wwz.ai.domain.agent.ledger.entity.DialogueSession;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WorkspaceArchiveApplicationServiceTest {

    @Test
    public void archivesLegitimateDoubleDotFileName() throws IOException {
        Path root = Files.createTempDirectory("workspace-archive");
        Files.writeString(root.resolve("a..b.txt"), "visible", StandardCharsets.UTF_8);

        Set<String> entries = archiveEntries(root);

        Assert.assertTrue(entries.contains("a..b.txt"));
    }

    @Test
    public void skipsSymbolicLinkThatEscapesWorkspace() throws IOException {
        Path temp = Files.createTempDirectory("workspace-archive-link");
        Path root = Files.createDirectory(temp.resolve("root"));
        Path outside = Files.writeString(temp.resolve("outside.txt"), "secret", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(root.resolve("leak.txt"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException("当前文件系统不支持符号链接", e);
        }

        Set<String> entries = archiveEntries(root);

        Assert.assertFalse(entries.contains("leak.txt"));
    }

    private static Set<String> archiveEntries(Path root) throws IOException {
        WorkspaceService workspace = new WorkspaceService(null, null, null, null) {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public Path resolveRoot(String sessionId) {
                return root;
            }
        };
        ConversationSessionAuthorizationService authorization =
                new ConversationSessionAuthorizationService(null, null, null) {
                    @Override
                    public DialogueSession ensureExistingSessionAccessible(String userId, String sessionId) {
                        return DialogueSession.builder().sessionId(sessionId).userId(userId).build();
                    }
                };
        WorkspaceArchiveApplicationService service =
                new WorkspaceArchiveApplicationService(authorization, workspace);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeArchive("user-1", "session-1", output);

        Set<String> entries = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }
}
