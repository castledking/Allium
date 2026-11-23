package codes.castled.allium.pack.delivery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal embedded HTTP server for {@code resource-pack.standalone.self-host}.
 *
 * <p>Serves only the in-memory bytes of Allium's current {@link PackSource} at
 * {@value #PACK_PATH} (optionally qualified with {@code ?sha1=<digest>} or
 * {@code /packs/<digest>/allium-core-pack.zip}) for {@code GET} and {@code HEAD}. Never reads the
 * filesystem per request, so directory traversal and unrelated file exposure are impossible.
 *
 * <p>Digest-specific URLs are immutable for that digest ({@code Cache-Control: public, max-age=31536000, immutable})
 * and return {@code 404} with {@code Cache-Control: no-store} once superseded, while bare unversioned
 * requests require revalidation ({@code Cache-Control: no-cache, must-revalidate} + {@code ETag}) so
 * a caching reverse proxy never serves stale bytes for a new SHA-1.
 */
public final class PackHttpServer {

    public static final String PACK_PATH = "/" + PackExport.STANDALONE_FILE;
    private static final String VERSIONED_PREFIX = "/packs/";
    private static final String VERSIONED_SUFFIX = "/" + PackExport.STANDALONE_FILE;
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
     * Builds the digest-specific URL advertised to joining players. Uses {@code publicUrl} when set
     * (appending or replacing {@code sha1=<digest>} so reverse proxies cache each generation
     * independently); otherwise {@code http://<host>:<port>/allium-core-pack.zip?sha1=<digest>}.
     */
    public String packUrl(String publicHost, String publicUrl, String serverIp) {
        String currentSha1 = pack.get().sha1();
        if (publicUrl != null && (publicUrl.startsWith("https://") || publicUrl.startsWith("http://"))) {
            return withSha1Query(publicUrl.trim(), currentSha1);
        }
        String host = resolveHost(publicHost, serverIp);
        String formattedHost = formatHostForUrl(host);
        return "http://" + formattedHost + ":" + port() + PACK_PATH + "?sha1=" + currentSha1;
    }

    /** Appends (or replaces) the {@code sha1=<digest>} query parameter while preserving path and fragment. */
    static String withSha1Query(String rawUrl, String sha1) {
        int hashIdx = rawUrl.indexOf('#');
        String fragment = hashIdx >= 0 ? rawUrl.substring(hashIdx) : "";
        String withoutFragment = hashIdx >= 0 ? rawUrl.substring(0, hashIdx) : rawUrl;
        int qIdx = withoutFragment.indexOf('?');
        if (qIdx < 0) {
            return withoutFragment + "?sha1=" + sha1 + fragment;
        }
        String base = withoutFragment.substring(0, qIdx);
        String query = withoutFragment.substring(qIdx + 1);
        StringBuilder rebuilt = new StringBuilder();
        boolean replaced = false;
        for (String param : query.split("&")) {
            if (param.isEmpty()) continue;
            if (param.equalsIgnoreCase("sha1") || param.toLowerCase(Locale.ROOT).startsWith("sha1=")) {
                if (!replaced) {
                    if (rebuilt.length() > 0) rebuilt.append('&');
                    rebuilt.append("sha1=").append(sha1);
                    replaced = true;
                }
            } else {
                if (rebuilt.length() > 0) rebuilt.append('&');
                rebuilt.append(param);
            }
        }
        if (!replaced) {
            if (rebuilt.length() > 0) rebuilt.append('&');
            rebuilt.append("sha1=").append(sha1);
        }
        return base + "?" + rebuilt + fragment;
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
            URI requestUri = exchange.getRequestURI();
            String path = requestUri.getPath();
            String requestedSha1 = extractRequestedSha1(path, requestUri.getRawQuery());
            boolean validPath = PACK_PATH.equals(path) || isVersionedPackPath(path);
            if (!validPath) {
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
            if (requestedSha1 != null && !requestedSha1.equalsIgnoreCase(current.sha1())) {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = current.bytes();
            String etag = "\"" + current.sha1() + "\"";
            String cacheControl = requestedSha1 != null
                    ? "public, max-age=31536000, immutable"
                    : "no-cache, must-revalidate";
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + PackExport.STANDALONE_FILE + "\"");
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Cache-Control", cacheControl);
            if (etagMatches(exchange.getRequestHeaders().getFirst("If-None-Match"), etag)) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
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

    private static boolean isVersionedPackPath(@Nullable String path) {
        if (path == null || !path.startsWith(VERSIONED_PREFIX) || !path.endsWith(VERSIONED_SUFFIX)) {
            return false;
        }
        String segment = path.substring(VERSIONED_PREFIX.length(), path.length() - VERSIONED_SUFFIX.length());
        return !segment.isEmpty() && !segment.contains("/");
    }

    @Nullable
    private static String extractRequestedSha1(@Nullable String path, @Nullable String rawQuery) {
        if (isVersionedPackPath(path)) {
            return path.substring(VERSIONED_PREFIX.length(), path.length() - VERSIONED_SUFFIX.length());
        }
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (String param : rawQuery.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0 && param.substring(0, eq).equalsIgnoreCase("sha1")) {
                String value = param.substring(eq + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static boolean etagMatches(@Nullable String ifNoneMatch, String currentEtag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        for (String token : ifNoneMatch.split(",")) {
            String trimmed = token.trim();
            if (trimmed.equals("*") || trimmed.equals(currentEtag) || trimmed.equals("W/" + currentEtag)) {
                return true;
            }
        }
        return false;
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
