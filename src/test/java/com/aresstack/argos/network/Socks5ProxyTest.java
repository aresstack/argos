package com.aresstack.argos.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class Socks5ProxyTest {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<String> resolved = new CopyOnWriteArrayList<>();
    private final AtomicInteger echoConnections = new AtomicInteger();

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable resource : resources) resource.close();
    }

    /** Fake DNS: *.public.test is public, everything else answers from the map. */
    private Socks5Proxy.Resolver dns(Map<String, List<String>> records) {
        return host -> {
            resolved.add(host);
            List<String> answer = records.get(host);
            if (answer == null) throw new UnknownHostException(host);
            List<InetAddress> addresses = new ArrayList<>();
            for (String literal : answer) addresses.add(InetAddress.getByName(literal));
            return addresses;
        };
    }

    private Socks5Proxy proxy(String rules, Socks5Proxy.Resolver resolver) throws IOException {
        return proxy(new NetworkSettings(NetworkSettings.Mode.STRICT, NetworkPolicy.parse(rules), null, null), resolver);
    }

    private Socks5Proxy proxy(NetworkSettings settings, Socks5Proxy.Resolver resolver) throws IOException {
        Socks5Proxy proxy = Socks5Proxy.start(settings, resolver);
        resources.add(proxy);
        return proxy;
    }

    private int echoServer() throws IOException {
        ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        resources.add(server);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    echoConnections.incrementAndGet();
                    Thread.ofVirtual().start(() -> {
                        try (socket) {
                            socket.getInputStream().transferTo(socket.getOutputStream());
                        } catch (IOException ignored) {
                            // Test client closed.
                        }
                    });
                } catch (IOException closed) {
                    return;
                }
            }
        });
        return server.getLocalPort();
    }

    /** Minimal browser-side SOCKS5 client; returns the connected socket after a success reply. */
    private record Attempt(int reply, Socket socket) {
    }

    private Attempt connect(int proxyPort, String host, int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", proxyPort);
        resources.add(socket);
        OutputStream out = socket.getOutputStream();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        out.write(new byte[]{5, 1, 0});
        assertEquals(5, in.readUnsignedByte());
        assertEquals(0, in.readUnsignedByte());
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(new byte[]{5, 1, 0});
        if (IpRange.isAddressLiteral(host)) {
            byte[] raw = InetAddress.getByName(host).getAddress();
            request.write(raw.length == 4 ? 1 : 4);
            request.write(raw);
        } else {
            request.write(3);
            request.write(host.length());
            request.write(host.getBytes(StandardCharsets.US_ASCII));
        }
        request.write(port >> 8);
        request.write(port & 0xff);
        out.write(request.toByteArray());
        assertEquals(5, in.readUnsignedByte());
        int reply = in.readUnsignedByte();
        in.readNBytes(8);
        return new Attempt(reply, socket);
    }

    private static String roundTrip(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        return new String(socket.getInputStream().readNBytes(text.length()), StandardCharsets.UTF_8);
    }

    @Test
    void tunnelsAdmittedTargetsByHostNameUnchanged() throws Exception {
        int echo = echoServer();
        Socks5Proxy proxy = proxy("intranet.test", dns(Map.of("intranet.test", List.of("127.0.0.1"))));
        // A host rule never admits loopback ...
        assertEquals(2, connect(proxy.port(), "intranet.test", echo).reply());
        // ... only a range rule does.
        Socks5Proxy admitting = proxy("intranet.test@127.0.0.1/32", dns(Map.of("intranet.test", List.of("127.0.0.1"))));
        Attempt attempt = connect(admitting.port(), "intranet.test", echo);
        assertEquals(0, attempt.reply());
        assertEquals("GET / HTTP/1.1\r\n", roundTrip(attempt.socket(), "GET / HTTP/1.1\r\n"));
    }

    @Test
    void refusesInternalTargetsBeforeConnectingAndLogsThem() throws Exception {
        int echo = echoServer();
        Socks5Proxy proxy = proxy("", dns(Map.of("rebind.test", List.of("93.184.215.14", "127.0.0.1"))));
        assertEquals(2, connect(proxy.port(), "rebind.test", echo).reply());
        assertEquals(2, connect(proxy.port(), "127.0.0.1", echo).reply());
        assertEquals(2, connect(proxy.port(), "::ffff:127.0.0.1", echo).reply());
        assertEquals(2, connect(proxy.port(), "169.254.169.254", 80).reply());
        assertEquals(0, echoConnections.get());
        List<Map<String, Object>> blocked = proxy.blockedEvents();
        assertEquals(4, blocked.size());
        assertEquals("rebind.test:" + echo, blocked.get(0).get("url"));
        assertEquals("blocked", blocked.get(0).get("kind"));
        assertTrue(blocked.get(3).get("reason").toString().startsWith("metadata address"));
    }

    @Test
    void resolvesOncePerConnectionSoRebindingCannotSwapTheCheckedAddress() throws Exception {
        int echo = echoServer();
        AtomicInteger calls = new AtomicInteger();
        // First answer passes the rule, every later one would point at a protected range.
        Socks5Proxy proxy = proxy("127.0.0.1", host -> {
            resolved.add(host);
            return List.of(InetAddress.getByName(calls.getAndIncrement() == 0 ? "127.0.0.1" : "10.0.0.1"));
        });
        Attempt first = connect(proxy.port(), "rebind.test", echo);
        assertEquals(0, first.reply());
        assertEquals("ok", roundTrip(first.socket(), "ok"));
        assertEquals(1, calls.get(), "one lookup, and the connection used exactly that answer");
        assertEquals(2, connect(proxy.port(), "rebind.test", echo).reply());
        assertEquals(1, echoConnections.get());
    }

    @Test
    void neverResolvesInvalidNamesAndRefusesUnresolvableHosts() throws Exception {
        Socks5Proxy proxy = proxy("", dns(Map.of()));
        assertEquals(4, connect(proxy.port(), "argos-boundary-probe.invalid", 80).reply());
        assertEquals(4, connect(proxy.port(), "missing.test", 80).reply());
        assertEquals(List.of("missing.test"), resolved);
        assertEquals("argos-boundary-probe.invalid:80", proxy.blockedEvents().get(0).get("url"));
        proxy.discardBlockedEvents("argos-boundary-probe.invalid:80");
        assertEquals(1, proxy.blockedEvents().size());
    }

    @Test
    void protectedLoopbackPortsStayClosedEvenWhenLoopbackIsAllowed() throws Exception {
        int echo = echoServer();
        Socks5Proxy proxy = proxy("127.0.0.1", dns(Map.of()));
        proxy.protectLoopbackPort(echo);
        assertEquals(2, connect(proxy.port(), "127.0.0.1", echo).reply());
        assertEquals(2, connect(proxy.port(), "127.0.0.1", proxy.port()).reply(), "no proxy loops");
        proxy.releaseLoopbackPort(echo);
        assertEquals(0, connect(proxy.port(), "127.0.0.1", echo).reply());
    }

    @Test
    void supportsOnlyUnauthenticatedConnect() throws Exception {
        Socks5Proxy proxy = proxy("", dns(Map.of()));
        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            socket.getOutputStream().write(new byte[]{5, 1, 2});
            assertArrayEquals(new byte[]{5, (byte) 0xff}, socket.getInputStream().readNBytes(2));
        }
        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[]{5, 1, 0});
            in.readNBytes(2);
            // UDP ASSOCIATE would let traffic around the TCP policy.
            out.write(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 0, 0});
            assertEquals(7, in.readNBytes(2)[1]);
        }
    }

    @Test
    void stoppedProxyLeavesTheBrowserWithoutRoute() throws Exception {
        Socks5Proxy proxy = proxy("", dns(Map.of()));
        int port = proxy.port();
        assertTrue(proxy.running());
        proxy.close();
        assertFalse(proxy.running());
        assertThrows(IOException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test
    void chainsSocksUpstreamWithCheckedAddressOrHostName() throws Exception {
        int echo = echoServer();
        List<String> upstreamLookups = new CopyOnWriteArrayList<>();
        Socks5Proxy upstream = proxy("127.0.0.1", host -> {
            upstreamLookups.add(host);
            return List.of(InetAddress.getByName("127.0.0.1"));
        });
        UpstreamProxy chained = UpstreamProxy.parse("socks5://127.0.0.1:" + upstream.port(), null, null);
        Socks5Proxy.Resolver local = dns(Map.of("site.test", List.of("127.0.0.1")));

        Socks5Proxy checkedIp = proxy(new NetworkSettings(NetworkSettings.Mode.STRICT,
                NetworkPolicy.parse("127.0.0.1"), chained, NetworkSettings.UpstreamDns.LOCAL), local);
        Attempt viaIp = connect(checkedIp.port(), "site.test", echo);
        assertEquals(0, viaIp.reply());
        assertEquals("one", roundTrip(viaIp.socket(), "one"));
        assertEquals(List.of(), upstreamLookups, "upstream received the checked IP, not the name");

        Socks5Proxy byName = proxy(new NetworkSettings(NetworkSettings.Mode.STRICT,
                NetworkPolicy.parse("127.0.0.1"), chained, NetworkSettings.UpstreamDns.REMOTE), local);
        Attempt viaName = connect(byName.port(), "site.test", echo);
        assertEquals(0, viaName.reply());
        assertEquals("two", roundTrip(viaName.socket(), "two"));
        assertEquals(List.of("site.test"), upstreamLookups);

        // The local policy still applies first: the upstream never sees refused targets.
        assertEquals(2, connect(byName.port(), "10.0.0.1", 80).reply());
    }

    @Test
    void chainsHttpConnectUpstreamWithCredentials() throws Exception {
        ServerSocket fake = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        resources.add(fake);
        List<String> requests = new CopyOnWriteArrayList<>();
        Thread.ofVirtual().start(() -> {
            try (Socket socket = fake.accept()) {
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream header = new ByteArrayOutputStream();
                while (!header.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) header.write(in.read());
                requests.add(header.toString(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                in.transferTo(socket.getOutputStream());
            } catch (IOException ignored) {
                // Test finished.
            }
        });
        UpstreamProxy http = UpstreamProxy.parse("http://127.0.0.1:" + fake.getLocalPort(), "argos", "pw");
        Socks5Proxy proxy = proxy(new NetworkSettings(NetworkSettings.Mode.STRICT, NetworkPolicy.publicOnly(),
                http, NetworkSettings.UpstreamDns.LOCAL), dns(Map.of("public.test", List.of("93.184.215.14"))));
        Attempt attempt = connect(proxy.port(), "public.test", 443);
        assertEquals(0, attempt.reply());
        assertEquals("tls", roundTrip(attempt.socket(), "tls"));
        assertTrue(requests.get(0).startsWith("CONNECT 93.184.215.14:443 HTTP/1.1\r\n"), requests.get(0));
        assertTrue(requests.get(0).contains("Proxy-Authorization: Basic YXJnb3M6cHc=\r\n"));
    }
}
