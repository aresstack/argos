package com.aresstack.argos.network;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * In-process SOCKS5 proxy (RFC 1928, CONNECT only, no authentication) on 127.0.0.1 that every browser
 * connection must pass. The browser hands over host names (remote DNS); the proxy resolves once, checks
 * every address against the {@link NetworkPolicy} and connects to exactly a checked address. The tunnel
 * carries the browser's bytes unchanged: no TLS interception, no header changes, nothing a page can see.
 *
 * <p>Fail closed: refused or unresolvable targets get a SOCKS error, and once the proxy stops the
 * browser has no route at all.
 */
public final class Socks5Proxy implements AutoCloseable {
    private static final int MAX_BLOCKED_EVENTS = 200;
    // Above Firefox' own network.http.max-connections (900), so the proxy never refuses a browser socket.
    private static final int MAX_CONNECTIONS = 1024;
    private static final int LOG_LINES_PER_MINUTE = 20;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int REPLY_OK = 0, REPLY_FAILURE = 1, REPLY_NOT_ALLOWED = 2,
            REPLY_HOST_UNREACHABLE = 4, REPLY_REFUSED = 5, REPLY_COMMAND = 7, REPLY_ADDRESS_TYPE = 8;

    private final NetworkSettings settings;
    private final HostResolver resolver;
    private final ServerSocket server;
    private final Thread acceptor;
    private final Set<Integer> protectedLoopbackPorts = ConcurrentHashMap.newKeySet();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Deque<Map<String, Object>> blocked = new ArrayDeque<>();
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private long logWindowStart = System.nanoTime();
    private int loggedInWindow;
    private int suppressedInWindow;
    private volatile boolean closed;

    private Socks5Proxy(NetworkSettings settings, HostResolver resolver) throws IOException {
        this.settings = settings;
        this.resolver = resolver;
        this.server = new ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"));
        protectedLoopbackPorts.add(server.getLocalPort());
        if (settings.policy().admitsLoopback()) {
            System.err.println("INTRANET_ALLOW admits loopback: pages can reach local services, including "
                    + "the browser's own automation ports. Use this only for tests against local fixtures.");
        }
        if (settings.upstream() != null && settings.upstreamDns() == NetworkSettings.UpstreamDns.REMOTE) {
            System.err.println("UPSTREAM_DNS=remote: the upstream proxy resolves again, so the policy "
                    + "cannot see where it connects (DNS rebinding at the upstream is possible).");
        }
        this.acceptor = Thread.ofPlatform().daemon().name("argos-network-proxy").start(this::acceptLoop);
    }

    public static Socks5Proxy start(NetworkSettings settings, HostResolver resolver) throws IOException {
        return new Socks5Proxy(settings, resolver);
    }

    public int port() {
        return server.getLocalPort();
    }

    public boolean running() {
        return !closed && !server.isClosed() && acceptor.isAlive();
    }

    /** Loopback ports the browser may never reach, whatever the rules say (Argos itself, probes). */
    public void protectLoopbackPort(int port) {
        if (port > 0) protectedLoopbackPorts.add(port);
    }

    public void releaseLoopbackPort(int port) {
        if (port != port()) protectedLoopbackPorts.remove(port);
    }

    /** Recent refused connections in the {@code browser_network} event shape, oldest first. */
    public List<Map<String, Object>> blockedEvents() {
        synchronized (blocked) {
            return List.copyOf(blocked);
        }
    }

    /** Drops log entries for one target, e.g. the start self-test probes. */
    public void discardBlockedEvents(String target) {
        synchronized (blocked) {
            blocked.removeIf(event -> target.equals(event.get("url")));
        }
    }

    /** Sockets currently tracked for {@link #close()}; for tests. */
    int trackedSockets() {
        return open.size();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException error) {
                if (!closed) System.err.println("Argos network proxy stopped, browser is offline: " + error.getMessage());
                closeQuietly(server);
                return;
            }
            if (!slots.tryAcquire()) {
                closeQuietly(client);
                continue;
            }
            Thread.ofVirtual().name("argos-network-connection").start(() -> {
                try {
                    handle(client);
                } finally {
                    slots.release();
                }
            });
        }
    }

    private void handle(Socket client) {
        open.add(client);
        Socket remote = null;
        try {
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            if (in.readUnsignedByte() != 5) return;
            byte[] methods = in.readNBytes(in.readUnsignedByte());
            if (!containsNoAuth(methods)) {
                out.write(new byte[]{5, (byte) 0xff});
                return;
            }
            out.write(new byte[]{5, 0});

            int version = in.readUnsignedByte();
            int command = in.readUnsignedByte();
            in.readUnsignedByte();
            int addressType = in.readUnsignedByte();
            InetAddress literal = null;
            String host = null;
            switch (addressType) {
                case 1 -> literal = InetAddress.getByAddress(in.readNBytes(4));
                case 3 -> host = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                case 4 -> literal = InetAddress.getByAddress(in.readNBytes(16));
                default -> {
                    reply(out, REPLY_ADDRESS_TYPE);
                    return;
                }
            }
            if (literal != null) host = literal.getHostAddress();
            int port = in.readUnsignedShort();
            if (version != 5) return;
            if (command != 1) {
                reply(out, REPLY_COMMAND);
                return;
            }
            remote = open(out, host, port, literal);
            if (remote != null) relay(client, remote);
        } catch (IOException ignored) {
            // Client went away during the handshake or the tunnel; nothing to report.
        } finally {
            // Every exit path, including refusals and handshake failures, stops tracking both sockets.
            open.remove(client);
            closeQuietly(client);
            if (remote != null) {
                open.remove(remote);
                closeQuietly(remote);
            }
        }
    }

    /** Resolves, checks and connects; on refusal sends the SOCKS error and returns null. */
    private Socket open(OutputStream out, String requestedHost, int port, InetAddress literal) throws IOException {
        String host = NetworkPolicy.normalizeHost(requestedHost);
        NetworkPolicy.Resolution resolution = literal != null
                ? new NetworkPolicy.Resolution(settings.policy().check(host, List.of(literal)), List.of(literal))
                : settings.policy().resolve(host, resolver);
        List<InetAddress> addresses = resolution.addresses();
        if (addresses.isEmpty()) {
            // Unresolvable or a reserved .invalid name (the self-test's proof of remote DNS).
            return refuse(out, host, port, resolution.decision().reason(), REPLY_HOST_UNREACHABLE);
        }
        for (InetAddress address : addresses) {
            AddressClass kind = settings.policy().classify(address);
            boolean local = kind == AddressClass.LOOPBACK || kind == AddressClass.LOCAL_INTERFACE;
            if (local && protectedLoopbackPorts.contains(port)) {
                return refuse(out, host, port, "Argos-internal port " + port, REPLY_NOT_ALLOWED);
            }
        }
        NetworkPolicy.Decision decision = resolution.decision();
        if (!decision.allowed()) return refuse(out, host, port, decision.reason(), REPLY_NOT_ALLOWED);

        Socket remote;
        try {
            remote = settings.upstream() == null ? connectDirect(addresses, port)
                    : connectUpstream(settings.upstreamDns() == NetworkSettings.UpstreamDns.REMOTE
                    ? host : addresses.get(0).getHostAddress(), port);
        } catch (UpstreamRefusal refusal) {
            reply(out, refusal.reply);
            return null;
        } catch (ConnectException refused) {
            reply(out, REPLY_REFUSED);
            return null;
        } catch (IOException unreachable) {
            reply(out, REPLY_HOST_UNREACHABLE);
            return null;
        }
        open.add(remote);
        reply(out, REPLY_OK);
        return remote;
    }

    private Socket refuse(OutputStream out, String host, int port, String reason, int code) throws IOException {
        String target = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        String line;
        synchronized (blocked) {
            if (blocked.size() >= MAX_BLOCKED_EVENTS) blocked.removeFirst();
            blocked.addLast(Map.of("at", Instant.now().toString(), "kind", "blocked", "method", "CONNECT",
                    "url", target, "status", 0, "reason", reason));
            // A page can trigger refusals in a loop; keep the service log bounded.
            long now = System.nanoTime();
            if (now - logWindowStart > 60_000_000_000L) {
                reportSuppressedRefusals();
                logWindowStart = now;
                loggedInWindow = 0;
            }
            if (loggedInWindow < LOG_LINES_PER_MINUTE) {
                loggedInWindow++;
                line = "Argos network policy blocked " + target + ": " + reason;
            } else {
                suppressedInWindow++;
                line = null;
            }
        }
        if (line != null) System.err.println(line);
        reply(out, code);
        return null;
    }

    private static Socket connectDirect(List<InetAddress> addresses, int port) throws IOException {
        IOException last = null;
        for (InetAddress address : addresses) {
            Socket socket = new Socket();
            try {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
                return socket;
            } catch (IOException failed) {
                closeQuietly(socket);
                last = failed;
            }
        }
        throw last;
    }

    private Socket connectUpstream(String target, int port) throws IOException {
        UpstreamProxy upstream = settings.upstream();
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(upstream.host(), upstream.port()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            if (upstream.kind() == UpstreamProxy.Kind.SOCKS5) socks5Handshake(socket, upstream, target, port);
            else httpConnect(socket, upstream, target, port);
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException | RuntimeException failed) {
            closeQuietly(socket);
            throw failed;
        }
    }

    private static void socks5Handshake(Socket socket, UpstreamProxy upstream, String target, int port) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        out.write(upstream.authenticated() ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
        if (in.readUnsignedByte() != 5) throw new UpstreamRefusal(REPLY_FAILURE);
        int method = in.readUnsignedByte();
        if (method == 2 && upstream.authenticated()) {
            byte[] user = upstream.username().getBytes(StandardCharsets.UTF_8);
            byte[] secret = upstream.password().getBytes(StandardCharsets.UTF_8);
            if (user.length > 255 || secret.length > 255) throw new UpstreamRefusal(REPLY_FAILURE);
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(1);
            auth.write(user.length);
            auth.write(user);
            auth.write(secret.length);
            auth.write(secret);
            out.write(auth.toByteArray());
            in.readUnsignedByte();
            if (in.readUnsignedByte() != 0) throw new UpstreamRefusal(REPLY_FAILURE);
        } else if (method != 0) {
            throw new UpstreamRefusal(REPLY_FAILURE);
        }
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(new byte[]{5, 1, 0});
        if (IpRange.isAddressLiteral(target)) {
            byte[] raw = InetAddress.getByName(target).getAddress();
            request.write(raw.length == 4 ? 1 : 4);
            request.write(raw);
        } else {
            byte[] name = target.getBytes(StandardCharsets.US_ASCII);
            if (name.length > 255) throw new UpstreamRefusal(REPLY_HOST_UNREACHABLE);
            request.write(3);
            request.write(name.length);
            request.write(name);
        }
        request.write(port >> 8);
        request.write(port & 0xff);
        out.write(request.toByteArray());
        in.readUnsignedByte();
        int reply = in.readUnsignedByte();
        in.readUnsignedByte();
        int boundType = in.readUnsignedByte();
        int skip = switch (boundType) {
            case 1 -> 4;
            case 4 -> 16;
            case 3 -> in.readUnsignedByte();
            default -> throw new UpstreamRefusal(REPLY_FAILURE);
        };
        in.readNBytes(skip + 2);
        if (reply != 0) throw new UpstreamRefusal(reply <= 8 ? reply : REPLY_FAILURE);
    }

    private static void httpConnect(Socket socket, UpstreamProxy upstream, String target, int port) throws IOException {
        boolean ipv6 = IpRange.isAddressLiteral(target) && InetAddress.getByName(target) instanceof Inet6Address;
        String authority = (ipv6 ? "[" + target + "]" : target) + ":" + port;
        StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\n");
        if (upstream.authenticated()) {
            String credentials = upstream.username() + ":" + upstream.password();
            request.append("Proxy-Authorization: Basic ")
                    .append(Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                    .append("\r\n");
        }
        request.append("\r\n");
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        // Byte by byte so no tunnel data after the header is consumed.
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int tail = 0;
        while (header.size() < 16_384) {
            int next = in.read();
            if (next < 0) throw new UpstreamRefusal(REPLY_FAILURE);
            header.write(next);
            tail = (tail << 8) | next;
            if (tail == 0x0d0a0d0a) {
                String text = header.toString(StandardCharsets.ISO_8859_1);
                String[] status = text.substring(0, text.indexOf("\r\n")).split(" ");
                if (status.length >= 2 && status[1].equals("200")) return;
                throw new UpstreamRefusal(status.length >= 2 && status[1].equals("403") ? REPLY_NOT_ALLOWED : REPLY_FAILURE);
            }
        }
        throw new UpstreamRefusal(REPLY_FAILURE);
    }

    private void relay(Socket client, Socket remote) throws IOException {
        client.setSoTimeout(0);
        Thread upload = Thread.ofVirtual().name("argos-network-relay").start(() -> pump(client, remote));
        pump(remote, client);
        try {
            upload.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pump(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
            to.shutdownOutput();
        } catch (IOException broken) {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static boolean containsNoAuth(byte[] methods) {
        for (byte method : methods) {
            if (method == 0) return true;
        }
        return false;
    }

    private static void reply(OutputStream out, int code) throws IOException {
        out.write(new byte[]{5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Already closed.
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(server);
        open.forEach(Socks5Proxy::closeQuietly);
        open.clear();
        reportSuppressedRefusals();
    }

    /** The count of refusals the bounded log left out, at the end of a window and when the proxy stops. */
    private void reportSuppressedRefusals() {
        synchronized (blocked) {
            if (suppressedInWindow > 0) {
                System.err.println("Argos network policy: " + suppressedInWindow
                        + " further refused connections not logged");
                suppressedInWindow = 0;
            }
        }
    }

    /** The upstream proxy refused; carries the SOCKS reply code for the browser. */
    private static final class UpstreamRefusal extends IOException {
        private final int reply;

        UpstreamRefusal(int reply) {
            super("upstream proxy refused (SOCKS reply " + reply + ")");
            this.reply = reply;
        }
    }
}
