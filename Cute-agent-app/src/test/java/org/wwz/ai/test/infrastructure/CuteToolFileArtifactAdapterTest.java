package org.wwz.ai.test.infrastructure;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.RemoteHttpPort;
import org.wwz.ai.domain.agent.runtime.dto.FileRequest;
import org.wwz.ai.domain.agent.runtime.dto.FileResponse;
import org.wwz.ai.infrastructure.adapter.port.CuteToolFileArtifactAdapter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public class CuteToolFileArtifactAdapterTest {

    @Test
    public void uploadLocalFileFailsWhenReturnedUrlCannotBeReadBack() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String downloadUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/download/missing?token=definitely-missing";
        String responseBody = "{\"downloadUrl\":\"" + downloadUrl + "\","
                + "\"fileName\":\"artifact.txt\"}";
        byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        server.createContext("/v1/file_tool/upload_file_data", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bodyBytes.length);
            exchange.getResponseBody().write(bodyBytes);
            exchange.close();
        });
        server.createContext("/download/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        Path root = Files.createTempDirectory("cute-file-artifact-verify-root-");
        Path localFile = Files.createFile(root.resolve("payload.txt"));
        Files.writeString(localFile, "payload");
        server.start();
        try {
            CuteToolFileArtifactAdapter adapter =
                    new CuteToolFileArtifactAdapter(new NoopRemoteHttpPort(), new OkHttpClient(),
                            CuteToolFileArtifactAdapter.MODE_UPLOAD, root.toString());

            try {
                adapter.uploadLocalFile(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        FileRequest.builder()
                                .requestId("req-verify")
                                .fileName("artifact.txt")
                                .localPath(localFile.toString())
                                .build());
                Assert.fail("unreadable artifact URL must fail the upload");
            } catch (IOException expected) {
                Assert.assertTrue(expected.getMessage().contains("URL verification"));
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(localFile);
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void uploadLocalFileKeepsResponseCharsetWhileReadingBoundedBody() throws Exception {
        Charset gbk = Charset.forName("GBK");
        String expectedFileName = "响应文件.txt";
        AtomicReference<String> multipartBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String downloadUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/download/file";
        byte[] responseBody = ("{\"downloadUrl\":\"" + downloadUrl + "\","
                + "\"fileName\":\"" + expectedFileName + "\"}")
                .getBytes(gbk);
        server.createContext("/v1/file_tool/upload_file_data", exchange -> {
            multipartBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=GBK");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.createContext("/download/file", exchange -> {
            byte[] fileBytes = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, fileBytes.length);
            exchange.getResponseBody().write(fileBytes);
            exchange.close();
        });
        Path root = Files.createTempDirectory("cute-file-artifact-root-");
        Path localFile = Files.createFile(root.resolve("payload.txt"));
        Files.writeString(localFile, "payload");
        server.start();
        try {
            CuteToolFileArtifactAdapter adapter =
                    new CuteToolFileArtifactAdapter(new NoopRemoteHttpPort(), new OkHttpClient(),
                            CuteToolFileArtifactAdapter.MODE_UPLOAD, root.toString());
            FileResponse response = adapter.uploadLocalFile(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    FileRequest.builder()
                            .requestId("req-1")
                            .fileName("test-page/screens/desktop.png")
                            .localPath(localFile.toString())
                            .build());

            Assert.assertNotNull(response);
            Assert.assertEquals(expectedFileName, response.getFileName());
            Assert.assertTrue(multipartBody.get().contains("name=\"relativePath\""));
            Assert.assertTrue(multipartBody.get().contains("test-page/screens/desktop.png"));
            Assert.assertTrue(multipartBody.get().contains("filename=\"desktop.png\""));
        } finally {
            server.stop(0);
            Files.deleteIfExists(localFile);
            Files.deleteIfExists(root);
        }
    }

    private static final class NoopRemoteHttpPort implements RemoteHttpPort {
        @Override
        public String execute(org.wwz.ai.domain.agent.adapter.port.RemoteHttpRequest request) throws IOException {
            throw new UnsupportedOperationException("uploadLocalFile must use its own HTTP call");
        }
    }
}
