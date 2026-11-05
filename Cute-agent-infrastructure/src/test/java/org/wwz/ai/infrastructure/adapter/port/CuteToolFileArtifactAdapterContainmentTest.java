package org.wwz.ai.infrastructure.adapter.port;

import okhttp3.OkHttpClient;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest;
import org.wwz.ai.domain.agent.runtime.dto.FileRequest;
import org.wwz.ai.domain.agent.runtime.dto.FileResponse;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspacePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CuteToolFileArtifactAdapterContainmentTest {

    private static final Path ALLOWED_ROOT = WorkspacePaths.resolveRepoRoot()
            .resolve("cute-tool")
            .resolve("skilloutput");

    @Test
    public void shouldAcceptFileInsideAllowedRoot() throws Exception {
        Files.createDirectories(ALLOWED_ROOT);
        Path directory = Files.createTempDirectory(ALLOWED_ROOT, "w4-artifact-");
        Path file = Files.createFile(directory.resolve("inside.txt"));
        try {
            CuteToolFileArtifactAdapter adapter = new CuteToolFileArtifactAdapter(
                    new StubRemoteHttpPort(), new OkHttpClient());

            FileResponse response = adapter.register("http://127.0.0.1:1601",
                    request(file.toAbsolutePath().toString()));

            Assert.assertEquals("http://files/inside.txt", response.getOssUrl());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void shouldRejectParentTraversalAndAbsoluteEscapeForRegister() throws Exception {
        Files.createDirectories(ALLOWED_ROOT);
        Path outside = Files.createTempFile(ALLOWED_ROOT.getParent(), "artifact-outside-", ".txt");
        try {
            CuteToolFileArtifactAdapter adapter = new CuteToolFileArtifactAdapter(
                    new StubRemoteHttpPort(), new OkHttpClient());

            assertRejected(() -> adapter.register("http://127.0.0.1:1601",
                    request("../" + outside.getFileName())));
            assertRejected(() -> adapter.register("http://127.0.0.1:1601",
                    request(outside.toAbsolutePath().toString())));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    public void shouldRejectEscapeBeforeUploadReadsLocalFile() throws Exception {
        Files.createDirectories(ALLOWED_ROOT);
        Path outside = Files.createTempFile(ALLOWED_ROOT.getParent(), "artifact-outside-", ".txt");
        CuteToolFileArtifactAdapter adapter = new CuteToolFileArtifactAdapter(
                new ThrowingRemoteHttpPort(), new OkHttpClient());
        adapter.setLocalFileMode(CuteToolFileArtifactAdapter.MODE_UPLOAD);
        try {
            assertRejected(() -> adapter.uploadLocalFile("http://127.0.0.1:1601",
                    request(outside.toAbsolutePath().toString())));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private FileRequest request(String localPath) {
        return FileRequest.builder()
                .requestId("req-1")
                .fileName("inside.txt")
                .localPath(localPath)
                .build();
    }

    private void assertRejected(ThrowingOperation operation) throws Exception {
        try {
            operation.run();
            Assert.fail("localPath outside the allowed root must be rejected");
        } catch (IOException e) {
            Assert.assertTrue(e.getMessage().contains("outside allowed root"));
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static final class StubRemoteHttpPort implements RemoteHttpPort {
        @Override
        public String execute(RemoteHttpRequest request) {
            return "{\"ossUrl\":\"http://files/inside.txt\"}";
        }
    }

    private static final class ThrowingRemoteHttpPort implements RemoteHttpPort {
        @Override
        public String execute(RemoteHttpRequest request) {
            throw new AssertionError("HTTP must not be called for a rejected localPath");
        }
    }
}
