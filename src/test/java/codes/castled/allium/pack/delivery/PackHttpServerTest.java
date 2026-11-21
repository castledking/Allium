package codes.castled.allium.pack.delivery;

import codes.castled.allium.PluginStart;
import codes.castled.allium.pack.ResourcePackSettings;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

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
            String url = http.packUrl("", "", "");
            assertEquals("http://127.0.0.1:" + http.port() + "/allium-core-pack.zip", url);

            // GET returns exact pack bytes and ETag matching SHA-1
            HttpURLConnection get = (HttpURLConnection) new URL(url + "?sha1=" + source1.sha1()).openConnection();
            assertEquals(200, get.getResponseCode());
            assertEquals("application/zip", get.getContentType());
            assertEquals("\"" + source1.sha1() + "\"", get.getHeaderField("ETag"));
            assertArrayEquals(v1, get.getInputStream().readAllBytes());
            get.disconnect();

            // HEAD returns 200 with Content-Length and no body
            HttpURLConnection head = (HttpURLConnection) new URL(url).openConnection();
            head.setRequestMethod("HEAD");
            assertEquals(200, head.getResponseCode());
            assertEquals(v1.length, head.getContentLength());
            head.disconnect();

            // Other paths return 404 (no filesystem exposure)
            HttpURLConnection missing = (HttpURLConnection) new URL("http://127.0.0.1:" + http.port() + "/config.yml").openConnection();
            assertEquals(404, missing.getResponseCode());
            missing.disconnect();

            // Non-GET/HEAD returns 405
            HttpURLConnection post = (HttpURLConnection) new URL(url).openConnection();
            post.setRequestMethod("POST");
            assertEquals(405, post.getResponseCode());
            post.disconnect();

            // Hot update swaps served bytes in-place
            http.update(source2);
            HttpURLConnection updated = (HttpURLConnection) new URL(url).openConnection();
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
        PackHttpServer wildcard = new PackHttpServer("0.0.0.0", 8080, source);
        assertEquals("https://packs.example.com/custom.zip",
                wildcard.packUrl("ignored.host", "https://packs.example.com/custom.zip", "1.2.3.4"));
        assertEquals("http://play.example.com:8080/allium-core-pack.zip",
                wildcard.packUrl("play.example.com", "", "1.2.3.4"));
        assertEquals("http://1.2.3.4:8080/allium-core-pack.zip",
                wildcard.packUrl("", "", "1.2.3.4"));
        assertEquals("http://127.0.0.1:8080/allium-core-pack.zip",
                wildcard.packUrl("", "", ""));
        assertEquals("http://[::1]:8080/allium-core-pack.zip",
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
            String expectedUrl = "http://127.0.0.1:" + delivery.selfHostPort() + "/allium-core-pack.zip";
            assertEquals(expectedUrl, delivery.activeUrl());

            // Verify real HTTP download works against the running self-host server
            HttpURLConnection conn = (HttpURLConnection) new URL(expectedUrl).openConnection();
            assertEquals(200, conn.getResponseCode());
            assertArrayEquals(bytes1, conn.getInputStream().readAllBytes());
            conn.disconnect();

            // send(player) uses the self-hosted URL and SHA-1
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getName()).thenReturn("Alice");
            delivery.send(player);
            verify(player).setResourcePack(eq(expectedUrl), eq(HexFormat.of().parseHex(source1.sha1())), isNull(), eq(false));

            // Reload with a new pack keeps the same port and updates bytes in-place
            int portBefore = delivery.selfHostPort();
            byte[] bytes2 = "self-hosted-pack-2".getBytes(StandardCharsets.UTF_8);
            List<String> replies = new ArrayList<>();
            delivery.reload(settings, PackSource.bundled(bytes2), replies::add);
            assertEquals(portBefore, delivery.selfHostPort());
            assertTrue(replies.stream().anyMatch(r -> r.contains("Self-hosting")));

            HttpURLConnection conn2 = (HttpURLConnection) new URL(expectedUrl).openConnection();
            assertEquals(200, conn2.getResponseCode());
            assertArrayEquals(bytes2, conn2.getInputStream().readAllBytes());
            conn2.disconnect();
        } finally {
            delivery.disable();
        }
        assertEquals(-1, delivery.selfHostPort());
    }
}
