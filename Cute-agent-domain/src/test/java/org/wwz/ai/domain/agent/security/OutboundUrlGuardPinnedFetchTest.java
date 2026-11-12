package org.wwz.ai.domain.agent.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证连接阶段只使用校验过的 IP，同时请求仍携带原始 Host。
 */
public class OutboundUrlGuardPinnedFetchTest {

    @Test
    public void pinnedConnectionUsesValidatedAddressAndOriginalHost() throws Exception {
        byte[] expected = "PK pinned skill".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> receivedHost = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/skill.zip", exchange -> {
            receivedHost.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, expected.length);
            exchange.getResponseBody().write(expected);
            exchange.close();
        });
        server.start();

        try {
            AtomicInteger resolverCalls = new AtomicInteger();
            OutboundUrlGuard.HostResolver resolver = host -> {
                resolverCalls.incrementAndGet();
                Assert.assertEquals("example.com", host);
                return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
            };
            RedirectingSocketFactory sockets =
                    new RedirectingSocketFactory(server.getAddress().getPort());
            PinnedHttpFetcher fetcher = new PinnedHttpFetcher(resolver, sockets);

            byte[] actual = fetcher.fetchUrl(
                    "http://example.com/skill.zip", 0, expected.length + 1L);

            Assert.assertArrayEquals(expected, actual);
            Assert.assertEquals(1, resolverCalls.get());
            Assert.assertEquals("example.com", receivedHost.get());
            Assert.assertEquals(
                    "93.184.216.34",
                    sockets.firstRequestedAddress.getHostAddress());
        } finally {
            server.stop(0);
        }
    }

    private static final class RedirectingSocketFactory extends SocketFactory {
        private final int localPort;
        private InetAddress firstRequestedAddress;

        private RedirectingSocketFactory(int localPort) {
            this.localPort = localPort;
        }

        @Override
        public Socket createSocket() {
            return new RedirectingSocket(this);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return createSocket();
        }

        @Override
        public Socket createSocket(String host, int port,
                                   InetAddress localHost, int localPort) throws IOException {
            return createSocket();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return createSocket();
        }

        @Override
        public Socket createSocket(InetAddress address, int port,
                                   InetAddress localAddress, int localPort) throws IOException {
            return createSocket();
        }
    }

    private static final class RedirectingSocket extends Socket {
        private final RedirectingSocketFactory factory;

        private RedirectingSocket(RedirectingSocketFactory factory) {
            this.factory = factory;
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            InetSocketAddress requested = (InetSocketAddress) endpoint;
            if (factory.firstRequestedAddress == null) {
                factory.firstRequestedAddress = requested.getAddress();
            }
            super.connect(new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), factory.localPort), timeout);
        }
    }
}
