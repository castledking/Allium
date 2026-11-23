package codes.castled.allium.pack.delivery;

import codes.castled.allium.PluginStart;
import codes.castled.allium.pack.PackMerger;
import codes.castled.allium.pack.ResourcePackSettings;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class PackHttpServerTest {

    @Test
    void servesPackOverGetAndHeadRejectsOtherPathsAndMethodsAndHotSwapsBytes() throws Exception {
        byte[] v1 = "allium-pack-v1".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "allium-pack-v2-updated".getBytes(StandardCharsets.UTF_8);
        PackSource source1 = PackSource.bundled(v1);
        PackSource source2 = PackSource.bundled(v2);

        PackHttpServer http = new PackHttpServer("127.0.0.1", 0, source1);
        http.start();
        try {
            assertTrue(http.isRunning());
            assertTrue(http.port() > 0);
            String url1 = http.packUrl("", "", "");
            assertEquals("http://127.0.0.1:" + http.port() + "/allium-core-pack.zip?sha1=" + source1.sha1(), url1);
            String bareUrl = "http://127.0.0.1:" + http.port() + "/allium-core-pack.zip";

            // Digest-specific GET returns exact pack bytes, ETag matching SHA-1, and immutable Cache-Control
            HttpURLConnection get = (HttpURLConnection) new URL(url1).openConnection();
            assertEquals(200, get.getResponseCode());
            assertEquals("application/zip", get.getContentType());
            assertEquals("\"" + source1.sha1() + "\"", get.getHeaderField("ETag"));
            assertTrue(get.getHeaderField("Cache-Control").contains("immutable"));
            assertArrayEquals(v1, get.getInputStream().readAllBytes());
            get.disconnect();

            // Bare unversioned URL requires revalidation so proxies never cache stale bytes
            HttpURLConnection bare = (HttpURLConnection) new URL(bareUrl).openConnection();
            assertEquals(200, bare.getResponseCode());
            assertEquals("no-cache, must-revalidate", bare.getHeaderField("Cache-Control"));
            assertArrayEquals(v1, bare.getInputStream().readAllBytes());
            bare.disconnect();

            // Conditional GET with matching ETag returns 304 Not Modified
            HttpURLConnection cond = (HttpURLConnection) new URL(bareUrl).openConnection();
            cond.setRequestProperty("If-None-Match", "\"" + source1.sha1() + "\"");
            assertEquals(304, cond.getResponseCode());
            cond.disconnect();

            // HEAD returns 200 with Content-Length and no body
            HttpURLConnection head = (HttpURLConnection) new URL(url1).openConnection();
            head.setRequestMethod("HEAD");
            assertEquals(200, head.getResponseCode());
            assertEquals(v1.length, head.getContentLength());
            head.disconnect();

            // Other paths return 404 (no filesystem exposure)
            HttpURLConnection missing = (HttpURLConnection) new URL("http://127.0.0.1:" + http.port() + "/config.yml").openConnection();
            assertEquals(404, missing.getResponseCode());
            missing.disconnect();

            // Non-GET/HEAD returns 405
            HttpURLConnection post = (HttpURLConnection) new URL(url1).openConnection();
            post.setRequestMethod("POST");
            assertEquals(405, post.getResponseCode());
            post.disconnect();

            // Hot update swaps served bytes in-place and updates advertised digest URL; old digest returns 404 no-store
            http.update(source2);
            String url2 = http.packUrl("", "", "");
            assertEquals("http://127.0.0.1:" + http.port() + "/allium-core-pack.zip?sha1=" + source2.sha1(), url2);
            assertNotEquals(url1, url2);

            HttpURLConnection stale = (HttpURLConnection) new URL(url1).openConnection();
            assertEquals(404, stale.getResponseCode());
            assertEquals("no-store", stale.getHeaderField("Cache-Control"));
            stale.disconnect();

            HttpURLConnection updated = (HttpURLConnection) new URL(url2).openConnection();
            assertEquals(200, updated.getResponseCode());
            assertEquals("\"" + source2.sha1() + "\"", updated.getHeaderField("ETag"));
            assertArrayEquals(v2, updated.getInputStream().readAllBytes());
            updated.disconnect();
        } finally {
            http.stop();
        }
        assertFalse(http.isRunning());
    }

    @Test
    void packUrlFormattingHonorsPublicUrlPublicHostServerIpAndIpv6() {
        PackSource source = PackSource.bundled("x".getBytes(StandardCharsets.UTF_8));
        String sha = source.sha1();
        PackHttpServer wildcard = new PackHttpServer("0.0.0.0", 8080, source);
        assertEquals("https://packs.example.com/custom.zip?sha1=" + sha,
                wildcard.packUrl("ignored.host", "https://packs.example.com/custom.zip", "1.2.3.4"));
        assertEquals("https://packs.example.com/custom.zip?v=1&sha1=" + sha,
                wildcard.packUrl("ignored.host", "https://packs.example.com/custom.zip?v=1&sha1=old", "1.2.3.4"));
        assertEquals("http://play.example.com:8080/allium-core-pack.zip?sha1=" + sha,
                wildcard.packUrl("play.example.com", "", "1.2.3.4"));
        assertEquals("http://1.2.3.4:8080/allium-core-pack.zip?sha1=" + sha,
                wildcard.packUrl("", "", "1.2.3.4"));
        assertEquals("http://127.0.0.1:8080/allium-core-pack.zip?sha1=" + sha,
                wildcard.packUrl("", "", ""));
        assertEquals("http://[::1]:8080/allium-core-pack.zip?sha1=" + sha,
                wildcard.packUrl("::1", "", ""));
    }

    @Test
    void packDeliverySelfHostEnablesVerifiesSendsAndReloadsCleanly() throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        AlliumLogger logger = mock(AlliumLogger.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pm);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.logger()).thenReturn(logger);

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                owner: standalone
                prompt: ""
                standalone:
                  self-host:
                    enabled: true
                    bind-host: 127.0.0.1
                    port: 0
                """);
        ResourcePackSettings settings = ResourcePackSettings.parse(cfg);
        byte[] bytes1 = "self-hosted-pack-1".getBytes(StandardCharsets.UTF_8);
        PackSource source1 = PackSource.bundled(bytes1);

        PackDelivery delivery = new PackDelivery(plugin, settings, source1);
        try {
            delivery.enable();
            assertEquals(PackDelivery.Hosted.VERIFIED, delivery.hosted());
            assertTrue(delivery.selfHostPort() > 0);
            String expectedUrl1 = "http://127.0.0.1:" + delivery.selfHostPort() + "/allium-core-pack.zip?sha1=" + source1.sha1();
            assertEquals(expectedUrl1, delivery.activeUrl());

            // Verify real HTTP download works against the running self-host server
            HttpURLConnection conn = (HttpURLConnection) new URL(expectedUrl1).openConnection();
            assertEquals(200, conn.getResponseCode());
            assertArrayEquals(bytes1, conn.getInputStream().readAllBytes());
            conn.disconnect();

            // send(player) uses the digest-versioned self-hosted URL and SHA-1
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getName()).thenReturn("Alice");
            delivery.send(player);
            verify(player).setResourcePack(eq(expectedUrl1), eq(HexFormat.of().parseHex(source1.sha1())), isNull(), eq(false));

            // Reload with a new pack keeps the same port, updates bytes in-place, and advertises the new digest URL
            int portBefore = delivery.selfHostPort();
            byte[] bytes2 = "self-hosted-pack-2".getBytes(StandardCharsets.UTF_8);
            PackSource source2 = PackSource.bundled(bytes2);
            List<String> replies = new ArrayList<>();
            delivery.reload(settings, source2, replies::add);
            assertEquals(portBefore, delivery.selfHostPort());
            String expectedUrl2 = "http://127.0.0.1:" + delivery.selfHostPort() + "/allium-core-pack.zip?sha1=" + source2.sha1();
            assertEquals(expectedUrl2, delivery.activeUrl());
            assertTrue(replies.stream().anyMatch(r -> r.contains("Self-hosting")));

            HttpURLConnection conn2 = (HttpURLConnection) new URL(expectedUrl2).openConnection();
            assertEquals(200, conn2.getResponseCode());
            assertArrayEquals(bytes2, conn2.getInputStream().readAllBytes());
            conn2.disconnect();
        } finally {
            delivery.disable();
        }
        assertEquals(-1, delivery.selfHostPort());
    }

    @Test
    void twoPackGenerationsThroughCachingReverseProxyMatchAdvertisedSha1AndKeepMergedAssets() throws Exception {
        byte[] gen1Zip = mergedPackZip("hint-v1", "curios-v1");
        byte[] gen2Zip = mergedPackZip("hint-v2", "curios-v2");
        PackSource gen1 = PackSource.bundled(gen1Zip);
        PackSource gen2 = PackSource.bundled(gen2Zip);

        PackHttpServer origin = new PackHttpServer("127.0.0.1", 0, gen1);
        origin.start();

        // Standard RFC-like caching reverse proxy: caches `public, max-age=...` responses by full URL
        // without revalidating, and revalidates `no-cache` responses using `If-None-Match`.
        record CachedEntry(byte[] body, String etag, boolean mustRevalidate) {}
        Map<String, CachedEntry> proxyCache = new ConcurrentHashMap<>();
        AtomicInteger originFetches = new AtomicInteger();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        proxy.createContext("/", exchange -> {
            try {
                String uriKey = exchange.getRequestURI().toString();
                CachedEntry cached = proxyCache.get(uriKey);
                if (cached != null && !cached.mustRevalidate()) {
                    exchange.getResponseHeaders().set("X-Cache", "HIT");
                    exchange.getResponseHeaders().set("ETag", cached.etag());
                    exchange.sendResponseHeaders(200, cached.body().length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(cached.body());
                    }
                    return;
                }
                originFetches.incrementAndGet();
                HttpURLConnection upstream = (HttpURLConnection) new URL("http://127.0.0.1:" + origin.port() + uriKey).openConnection();
                if (cached != null && cached.etag() != null) {
                    upstream.setRequestProperty("If-None-Match", cached.etag());
                }
                int status = upstream.getResponseCode();
                if (status == 304 && cached != null) {
                    exchange.getResponseHeaders().set("X-Cache", "REVALIDATED");
                    exchange.getResponseHeaders().set("ETag", cached.etag());
                    exchange.sendResponseHeaders(200, cached.body().length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(cached.body());
                    }
                    upstream.disconnect();
                    return;
                }
                byte[] body = status >= 200 && status < 300 ? upstream.getInputStream().readAllBytes() : new byte[0];
                String cc = upstream.getHeaderField("Cache-Control");
                String etag = upstream.getHeaderField("ETag");
                upstream.disconnect();
                if (status == 200 && cc != null && !cc.contains("no-store")) {
                    proxyCache.put(uriKey, new CachedEntry(body, etag, cc.contains("no-cache")));
                }
                exchange.getResponseHeaders().set("X-Cache", "MISS");
                if (etag != null) exchange.getResponseHeaders().set("ETag", etag);
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            } finally {
                exchange.close();
            }
        });
        proxy.start();

        try {
            String proxyPublicUrl = "http://127.0.0.1:" + proxy.getAddress().getPort() + "/allium-core-pack.zip";

            // Generation 1 through caching proxy
            String advertised1 = origin.packUrl("", proxyPublicUrl, "");
            HttpURLConnection r1 = (HttpURLConnection) new URL(advertised1).openConnection();
            assertEquals(200, r1.getResponseCode());
            assertEquals("MISS", r1.getHeaderField("X-Cache"));
            byte[] received1 = r1.getInputStream().readAllBytes();
            r1.disconnect();
            assertEquals(gen1.sha1(), PackExport.sha1(received1));
            Map<String, byte[]> entries1 = PackMerger.readZip(received1);
            assertEquals("hint-v1", new String(entries1.get("assets/minecraft/lang/en_us.json"), StandardCharsets.UTF_8));
            assertEquals("curios-v1", new String(entries1.get("assets/curios/models/item/card.json"), StandardCharsets.UTF_8));

            // Second fetch of Generation 1 hits the proxy cache cleanly (immutable digest URL)
            HttpURLConnection r1Hit = (HttpURLConnection) new URL(advertised1).openConnection();
            assertEquals(200, r1Hit.getResponseCode());
            assertEquals("HIT", r1Hit.getHeaderField("X-Cache"));
            assertEquals(gen1.sha1(), PackExport.sha1(r1Hit.getInputStream().readAllBytes()));
            r1Hit.disconnect();

            // Bare URL also starts at Gen 1 and caches with revalidation
            HttpURLConnection bare1 = (HttpURLConnection) new URL(proxyPublicUrl).openConnection();
            assertEquals(200, bare1.getResponseCode());
            assertEquals(gen1.sha1(), PackExport.sha1(bare1.getInputStream().readAllBytes()));
            bare1.disconnect();

            // Publish Generation 2 on origin
            origin.update(gen2);
            String advertised2 = origin.packUrl("", proxyPublicUrl, "");
            assertNotEquals(advertised1, advertised2);

            // Generation 2 through the SAME caching proxy receives gen2 bytes matching gen2 SHA-1 (no stale cache!)
            HttpURLConnection r2 = (HttpURLConnection) new URL(advertised2).openConnection();
            assertEquals(200, r2.getResponseCode());
            assertEquals("MISS", r2.getHeaderField("X-Cache"));
            byte[] received2 = r2.getInputStream().readAllBytes();
            r2.disconnect();
            assertEquals(gen2.sha1(), PackExport.sha1(received2));
            Map<String, byte[]> entries2 = PackMerger.readZip(received2);
            assertEquals("hint-v2", new String(entries2.get("assets/minecraft/lang/en_us.json"), StandardCharsets.UTF_8));
            assertEquals("curios-v2", new String(entries2.get("assets/curios/models/item/card.json"), StandardCharsets.UTF_8));

            // Bare unversioned URL through the SAME caching proxy revalidates and receives gen2 bytes too
            HttpURLConnection bare2 = (HttpURLConnection) new URL(proxyPublicUrl).openConnection();
            assertEquals(200, bare2.getResponseCode());
            assertEquals(gen2.sha1(), PackExport.sha1(bare2.getInputStream().readAllBytes()));
            bare2.disconnect();
        } finally {
            proxy.stop(0);
            origin.stop();
        }
    }

    @Test
    void staleExternalVerificationCallbackIsDiscardedAfterNewPackIsPublished() throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        PluginManager pm = mock(PluginManager.class);
        AlliumLogger logger = mock(AlliumLogger.class);
        List<Runnable> serverQueue = new CopyOnWriteArrayList<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pm);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.logger()).thenReturn(logger);
        when(scheduler.runOnServer(any(Runnable.class))).thenAnswer(inv -> {
            serverQueue.add(inv.getArgument(0));
            return TaskHandle.NONE;
        });

        YamlConfiguration extCfg = new YamlConfiguration();
        extCfg.loadFromString("""
                owner: standalone
                standalone:
                  url: https://cdn.example.com/pack-v1.zip
                  verify-hosted-file: true
                """);
        ResourcePackSettings externalSettings = ResourcePackSettings.parse(extCfg);

        YamlConfiguration selfHostCfg = new YamlConfiguration();
        selfHostCfg.loadFromString("""
                owner: standalone
                prompt: ""
                standalone:
                  self-host:
                    enabled: true
                    bind-host: 127.0.0.1
                    port: 0
                """);
        ResourcePackSettings selfHostSettings = ResourcePackSettings.parse(selfHostCfg);

        PackSource pack1 = PackSource.bundled("external-pack-v1".getBytes(StandardCharsets.UTF_8));
        PackSource pack2 = PackSource.bundled("self-hosted-pack-v2".getBytes(StandardCharsets.UTF_8));

        CountDownLatch enteredCheck1 = new CountDownLatch(1);
        CountDownLatch releaseCheck1 = new CountDownLatch(1);
        CountDownLatch finishedCheck1 = new CountDownLatch(1);
        var bg = Executors.newSingleThreadExecutor();
        PackDelivery delivery = new PackDelivery(
                plugin,
                externalSettings,
                pack1,
                url -> {
                    enteredCheck1.countDown();
                    try {
                        releaseCheck1.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    // Stale check returns a mismatching SHA-1 after a new pack was already published.
                    return "0".repeat(40);
                },
                task -> bg.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        finishedCheck1.countDown();
                    }
                })
        );

        try {
            delivery.enable();
            assertTrue(enteredCheck1.await(5, TimeUnit.SECONDS));
            assertEquals(PackDelivery.Hosted.VERIFYING, delivery.hosted());

            // Publish generation 2 (self-hosted) while generation 1's external verification is still in flight.
            List<String> reloadReplies = new ArrayList<>();
            delivery.reload(selfHostSettings, pack2, reloadReplies::add);
            assertEquals(PackDelivery.Hosted.VERIFIED, delivery.hosted());
            String gen2Url = delivery.activeUrl();
            assertTrue(gen2Url.contains("?sha1=" + pack2.sha1()), gen2Url);

            // Release stale generation 1 check and drain any queued server-thread tasks.
            releaseCheck1.countDown();
            assertTrue(finishedCheck1.await(5, TimeUnit.SECONDS));
            serverQueue.forEach(Runnable::run);

            // Generation 2 MUST remain active and verified; stale MISMATCH from generation 1 was discarded.
            assertEquals(PackDelivery.Hosted.VERIFIED, delivery.hosted());
            assertEquals(gen2Url, delivery.activeUrl());
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getName()).thenReturn("Bob");
            delivery.send(player);
            verify(player).setResourcePack(eq(gen2Url), eq(HexFormat.of().parseHex(pack2.sha1())), isNull(), eq(false));
        } finally {
            delivery.disable();
            bg.shutdownNow();
        }
    }

    private static byte[] mergedPackZip(String hintContent, String addonContent) throws Exception {
        SortedMap<String, byte[]> files = new TreeMap<>();
        files.put("pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"Allium\"}}".getBytes(StandardCharsets.UTF_8));
        files.put("assets/minecraft/lang/en_us.json", hintContent.getBytes(StandardCharsets.UTF_8));
        files.put("assets/curios/models/item/card.json", addonContent.getBytes(StandardCharsets.UTF_8));
        return PackMerger.writeZip(files);
    }
}
