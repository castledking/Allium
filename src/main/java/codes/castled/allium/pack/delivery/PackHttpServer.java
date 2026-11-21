package codes.castled.allium.pack.delivery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal embedded HTTP server for {@code resource-pack.standalone.self-host}.
 *
 * <p>Serves only the in-memory bytes of Allium's current {@link PackSource} at
 * {@value #PACK_PATH} ({@code GET} and {@code HEAD}). Never reads the filesystem per request,
 * so directory traversal and unrelated file exposure are impossible.
 */
public final class PackHttpServer {

    public static final String PACK_PATH = "/" + PackExport.STANDALONE_FILE;
    private static final int BACKLOG = 32;

    private final String bindHost;
    private final int configuredPort;
    private final AtomicReference<PackSource> pack;
    private final @Nullable ExecutorService executor;
    private HttpServer server;
    private volatile int boundPort = -1;

    public PackHttpServer(String bindHost, int port, PackSource initialPack) {
        this(bindHost, port, initialPack, null);
    }

    public PackHttpServer(String bindHost, int port, PackSource initialPack, @Nullable ExecutorService executor) {
        this.bindHost = Objects.requireNonNull(bindHost, "bindHost");
        this.configuredPort = port;
        this.pack = new AtomicReference<>(Objects.requireNonNull(initialPack, "initialPack"));
        this.executor = executor;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        InetSocketAddress address = isWildcard(bindHost)
                ? new InetSocketAddress(configuredPort)
                : new InetSocketAddress(bindHost, configuredPort);
        HttpServer created = HttpServer.create(address, BACKLOG);
        created.setExecutor(executor);
        created.createContext("/", this::handle);
        created.start();
        this.server = created;
        this.boundPort = created.getAddress().getPort();
    }

    public void update(PackSource next) {
        pack.set(Objects.requireNonNull(next, "next"));
    }

    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop(0);
            } finally {
                server = null;
                boundPort = -1;
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public String bindHost() {
        return bindHost;
    }

    public int configuredPort() {
        return configuredPort;
    }

    /** Actual bound TCP port (useful when {@code configuredPort} is 0). */
    public int port() {
        return boundPort > 0 ? boundPort : configuredPort;
    }

    public boolean matchesBinding(String host, int port) {
        return bindHost.equals(host) && configuredPort == port;
    }

    /**
     * Builds the URL advertised to joining players. Uses {@code publicUrl} when set; otherwise
     * {@code http://<host>:<port>/allium-core-pack.zip} using {@code publicHost}, a non-wildcard
     * {@code bindHost}, Bukkit's {@code serverIp}, or {@code 127.0.0.1} as a local fallback.
     */
    public String packUrl(String publicHost, String publicUrl, String serverIp) {
        if (publicUrl != null && (publicUrl.startsWith("https://") || publicUrl.startsWith("http://"))) {
            return publicUrl;
        }
        String host = resolveHost(publicHost, serverIp);
        String formattedHost = formatHostForUrl(host);
        return "http://" + formattedHost + ":" + port() + PACK_PATH;
    }

    public String resolveHost(String publicHost, String serverIp) {
        if (publicHost != null && !publicHost.isBlank()) {
            return publicHost.trim();
        }
        if (!isWildcard(bindHost)) {
            return bindHost;
        }
        if (serverIp != null && !serverIp.isBlank() && !isWildcard(serverIp.trim())) {
            return serverIp.trim();
        }
        return "127.0.0.1";
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!PACK_PATH.equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            PackSource current = pack.get();
            byte[] bytes = current.bytes();
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + PackExport.STANDALONE_FILE + "\"");
            exchange.getResponseHeaders().set("ETag", "\"" + current.sha1() + "\"");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private static boolean isWildcard(String host) {
        return host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host) || "*".equals(host);
    }

    private static String formatHostForUrl(String host) {
        if (host.contains(":") && !(host.startsWith("[") && host.endsWith("]"))) {
            return "[" + host + "]";
        }
        return host;
    }
}
