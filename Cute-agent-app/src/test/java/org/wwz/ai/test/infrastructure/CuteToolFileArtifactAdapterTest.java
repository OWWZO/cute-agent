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
import java.nio.file.Files;
import java.nio.file.Path;

public class CuteToolFileArtifactAdapterTest {

    @Test
    public void uploadLocalFileKeepsResponseCharsetWhileReadingBoundedBody() throws Exception {
        Charset gbk = Charset.forName("GBK");
        String expectedFileName = "响应文件.txt";
        byte[] responseBody = ("{\"downloadUrl\":\"https://example.test/file\","
                + "\"fileName\":\"" + expectedFileName + "\"}")
                .getBytes(gbk);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/file_tool/upload_file_data", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=GBK");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
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
                            .fileName("upload.txt")
                            .localPath(localFile.toString())
                            .build());

            Assert.assertNotNull(response);
            Assert.assertEquals(expectedFileName, response.getFileName());
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
