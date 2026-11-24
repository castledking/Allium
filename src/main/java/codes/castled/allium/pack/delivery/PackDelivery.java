package codes.castled.allium.pack.delivery;

import codes.castled.allium.PluginStart;
import codes.castled.allium.pack.ResourcePackSettings;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.text.TextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Sends Allium's core pack when Allium is its delivery owner ({@code resource-pack.owner: standalone}).
 *
 * <ul>
 *   <li>From 1.20.3 a client can hold several server packs. Allium adds its pack under its own
 *       stable UUID ({@link #PACK_ID}), next to packs from server.properties or other plugins,
 *       and only ever reacts to statuses for that UUID.</li>
 *   <li>Before 1.20.3 a client holds one server pack, and Allium's replaces any other, so exactly
 *       one delivery owner is mandatory; Allium warns when server.properties also sets one.</li>
 *   <li>With {@code verify-hosted-file}, the URL is downloaded once at startup, off the server
 *       thread, and the pack is sent only if the hosted file's SHA-1 is the bundled pack's.</li>
 *   <li>A player who declines (or whose download fails) keeps the named vanilla fallback items,
 *       unless {@code required} is set; then they are kicked with {@code kick-message}.</li>
 * </ul>
 */
public final class PackDelivery implements Listener {

    /** Allium's pack identity on 1.20.3+ clients; the same across pack updates, so a new pack replaces only Allium's old one. */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("allium:core-pack".getBytes(StandardCharsets.UTF_8));
    private static final long MAX_HOSTED_BYTES = 256L * 1024 * 1024;

    public enum Hosted {
        /** Not sending: the owner is not standalone, or no URL is set. */
        OFF,
        VERIFYING,
        VERIFIED,
        /** verify-hosted-file is off: the URL is sent without checking it. */
        UNVERIFIED,
        MISMATCH,
        UNREACHABLE
    }

    @FunctionalInterface
    interface HostedHasher {
        String sha1OfUrl(String url) throws IOException;
    }

    private final PluginStart plugin;
    private volatile ResourcePackSettings settings;
    private volatile PackSource pack;
    private volatile String sha1;
    private volatile String activeUrl;
    private volatile PackOffer offer;
    private volatile long checkGeneration;
    private final TaskScheduler scheduler;
    private final HostedHasher hostedHasher;
    private final @Nullable java.util.concurrent.Executor checkExecutorOverride;
    private final @Nullable Method addResourcePack;
    private final @Nullable Method statusId;
    private volatile Hosted hosted = Hosted.OFF;
    private volatile String hostedDetail = "";
    private volatile boolean closed;
    private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PackOffer> sentTo = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastStatus = new ConcurrentHashMap<>();
    private ThreadPoolExecutor checkPool;
    private @Nullable PackHttpServer httpServer;

    private record PackOffer(ResourcePackSettings settings, String url, String sha1, byte[] hash) {}

    public PackDelivery(PluginStart plugin, PackGeneration generation) {
        this(plugin, Objects.requireNonNull(generation, "generation").settings(),
                Objects.requireNonNull(generation.coreArtifact(), "generation.coreArtifact"));
    }

    public PackDelivery(PluginStart plugin, ResourcePackSettings settings, PackSource pack) {
        this(plugin, settings, pack, PackDelivery::download, null);
    }

    PackDelivery(PluginStart plugin, ResourcePackSettings settings, PackSource pack,
                 HostedHasher hostedHasher, @Nullable java.util.concurrent.Executor checkExecutorOverride) {
        this.plugin = plugin;
        this.settings = settings;
        this.pack = pack;
        this.sha1 = pack.sha1();
        this.activeUrl = settings.url();
        this.scheduler = plugin.getScheduler();
        this.hostedHasher = Objects.requireNonNull(hostedHasher, "hostedHasher");
        this.checkExecutorOverride = checkExecutorOverride;
        this.addResourcePack = method(Player.class, "addResourcePack", UUID.class, String.class, byte[].class, String.class, boolean.class);
        this.statusId = method(PlayerResourcePackStatusEvent.class, "getID");
    }

    @Nullable
    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Whether this server adds packs by UUID (1.20.3+) instead of replacing the player's one server pack. */
    public boolean multiplePacks() {
        return addResourcePack != null;
    }

    private boolean listening;

    public void enable() {
        if (!settings.sendsUrl()) {
            return;
        }
        if (!listening) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            listening = true;
        }
        String serverPack = Bukkit.getServer() == null ? "" : Bukkit.getResourcePack();
        if (!multiplePacks() && serverPack != null && !serverPack.isBlank()) {
            plugin.logger().warning("This server version gives each player one server resource pack, so it needs exactly one delivery "
                    + "owner. server.properties also sets resource-pack, and Allium's pack replaces it for every player. Clear "
                    + "resource-pack in server.properties, or set resource-pack.owner to none.");
        }
        if (settings.selfHost().enabled()) {
            applySelfHost(settings, pack, message -> { });
            return;
        }
        stopSelfHost();
        activeUrl = settings.url();
        if (!settings.verifyHostedFile()) {
            publishOffer();
            hosted = Hosted.UNVERIFIED;
            plugin.logger().info("Sending Allium's resource pack from " + activeUrl + " (SHA-1 " + sha1 + ", not verified: "
                    + "verify-hosted-file is off)" + mode() + ".");
            return;
        }
        startCheck(message -> { });
    }

    private void startCheck(java.util.function.Consumer<String> reply) {
        hosted = Hosted.VERIFYING;
        java.util.concurrent.Executor executor = checkExecutorOverride;
        if (executor == null) {
            if (checkPool == null) {
                checkPool = scheduler.boundedPool("Allium-PackCheck", 1, 8);
            }
            executor = checkPool;
        }
        long generation = ++checkGeneration;
        String url = activeUrl;
        String expectedHash = sha1;
        try {
            executor.execute(() -> verifyHostedFile(generation, url, expectedHash, reply));
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            hosted = Hosted.UNREACHABLE;
            hostedDetail = "resource pack verification queue is busy; retry /allium reload";
            plugin.logger().warning("Not sending Allium's resource pack: " + hostedDetail + ".");
            reply.accept("Not sending the resource pack: " + hostedDetail + ".");
        }
    }

    /** {@code /allium reload}: applies a freshly assembled immutable {@link PackGeneration}. */
    public void reload(PackGeneration nextGeneration, java.util.function.Consumer<String> reply) {
        Objects.requireNonNull(nextGeneration, "nextGeneration");
        if (nextGeneration.coreArtifact() == null) {
            stopSelfHost();
            activeUrl = "";
            hosted = Hosted.OFF;
            reply.accept("No core resource pack artifact is available for standalone delivery.");
            return;
        }
        reload(nextGeneration.settings(), nextGeneration.coreArtifact(), reply);
    }

    /**
     * {@code /allium reload}: applies new URL, self-host, required, prompt and kick-message settings
     * and the (possibly new) pack, then checks or serves it again. Until verification finishes,
     * joining players wait for its result. If it now matches, online players who do not have the
     * pack yet are sent it.
     */
    public void reload(ResourcePackSettings next, PackSource nextPack, java.util.function.Consumer<String> reply) {
        String previousUrl = activeUrl;
        ++checkGeneration;
        offer = null;
        settings = next;
        pack = nextPack;
        if (!nextPack.sha1().equals(sha1)) {
            sentTo.clear(); // a different pack: everyone online gets it once ready
        }
        sha1 = nextPack.sha1();
        if (!next.sendsUrl()) {
            stopSelfHost();
            activeUrl = "";
            hosted = Hosted.OFF;
            reply.accept("Neither resource-pack.standalone.url nor standalone.self-host is enabled; Allium sends no pack until one is configured.");
            return;
        }
        if (!listening) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            listening = true;
        }
        if (next.selfHost().enabled()) {
            applySelfHost(next, nextPack, reply);
            if (!Objects.equals(previousUrl, activeUrl)) {
                sentTo.clear();
            }
            if (hosted == Hosted.VERIFIED) {
                sendPendingOnline();
            }
            return;
        }
        stopSelfHost();
        activeUrl = next.url();
        if (!Objects.equals(previousUrl, activeUrl)) {
            sentTo.clear();
        }
        if (!next.verifyHostedFile()) {
            publishOffer();
            hosted = Hosted.UNVERIFIED;
            reply.accept("Sending " + activeUrl + " (SHA-1 " + sha1 + ") without checking it: verify-hosted-file is off.");
            sendPendingOnline();
            return;
        }
        reply.accept("Checking the hosted resource pack at " + activeUrl + "...");
        startCheck(reply);
    }

    private void applySelfHost(ResourcePackSettings currentSettings, PackSource currentPack,
                               java.util.function.Consumer<String> reply) {
        ++checkGeneration;
        ResourcePackSettings.SelfHost sh = currentSettings.selfHost();
        try {
            if (httpServer != null && httpServer.isRunning() && httpServer.matchesBinding(sh.bindHost(), sh.port())) {
                httpServer.update(currentPack);
            } else {
                stopSelfHost();
                ThreadPoolExecutor httpPool = scheduler == null ? null : scheduler.boundedPool("Allium-PackHttp", 2, 32);
                PackHttpServer created = new PackHttpServer(sh.bindHost(), sh.port(), currentPack, httpPool);
                created.start();
                httpServer = created;
            }
            String serverIp = Bukkit.getServer() == null ? "" : Bukkit.getIp();
            activeUrl = httpServer.packUrl(sh.publicHost(), sh.publicUrl(), serverIp);
            publishOffer();
            hosted = Hosted.VERIFIED;
            hostedDetail = "self-hosting at " + activeUrl + " (SHA-1 " + sha1 + ")";
            plugin.logger().info("Self-hosting Allium's resource pack on " + sh.bindHost() + ":" + httpServer.port()
                    + " -> " + activeUrl + " (SHA-1 " + sha1 + ")" + mode() + ".");
            if (sh.publicUrl().isEmpty() && sh.publicHost().isEmpty()
                    && "127.0.0.1".equals(httpServer.resolveHost(sh.publicHost(), serverIp))) {
                plugin.logger().info("Self-host URL uses 127.0.0.1 (local testing only). For remote players, set "
                        + "resource-pack.standalone.self-host.public-host or public-url.");
            }
            reply.accept("Self-hosting Allium's resource pack at " + activeUrl + " (SHA-1 " + sha1 + ").");
        } catch (IOException | RuntimeException e) {
            stopSelfHost();
            activeUrl = "";
            hosted = Hosted.UNREACHABLE;
            hostedDetail = "could not bind self-host HTTP server on " + sh.bindHost() + ":" + sh.port() + " (" + e + ")";
            plugin.logger().severe("Not sending Allium's resource pack: " + hostedDetail + ".");
            reply.accept("Not sending the resource pack: " + hostedDetail + ".");
        }
    }

    private void stopSelfHost() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    private void sendPendingOnline() {
        if (Bukkit.getServer() == null) {
            return;
        }
        PackOffer current = offer;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PackOffer sent = sentTo.get(player.getUniqueId());
            if (waiting.contains(player.getUniqueId()) || sent == null || current == null
                    || !sent.url().equals(current.url()) || !sent.sha1().equals(current.sha1())) {
                if (BukkitServerThread.isFolia()) sendOnPlayer(player, () -> send(player), 0);
                else send(player);
            }
        }
        waiting.clear();
    }

    private void publishOffer() {
        offer = new PackOffer(settings, activeUrl, sha1, HexFormat.of().parseHex(sha1));
    }

    private void sendOnPlayer(Player player, Runnable task, long delayMillis) {
        BukkitServerThread.runFor(plugin, scheduler, player, () -> {
            if (player.isOnline()) task.run();
        }, () -> {}, delayMillis);
    }

    private String mode() {
        return (multiplePacks() ? ", added by UUID " + PACK_ID + " beside any other packs" : ", as the player's one server pack")
                + (settings.required() ? ", required" : ", optional");
    }

    private void verifyHostedFile(long generation, String url, String sha1, java.util.function.Consumer<String> reply) {
        if (closed || generation != checkGeneration) return;
        Hosted result;
        String detail;
        try {
            String found = hostedHasher.sha1OfUrl(url);
            if (closed || generation != checkGeneration) return;
            if (found.equals(sha1)) {
                result = Hosted.VERIFIED;
                detail = "the hosted file matches (SHA-1 " + sha1 + ")";
            } else {
                result = Hosted.MISMATCH;
                detail = "the file at " + url + " has SHA-1 " + found + ", but Allium's bundled pack has SHA-1 " + sha1
                        + ". Upload plugins/Allium/pack/" + PackExport.STANDALONE_FILE + " there again; until then Allium sends nothing";
            }
        } catch (IOException | RuntimeException e) {
            if (closed || generation != checkGeneration) return;
            result = Hosted.UNREACHABLE;
            detail = "could not download " + url + " (" + e + "); Allium sends nothing until a restart finds it";
        }
        Hosted finalResult = result;
        String finalDetail = detail;
        scheduler.runOnServer(() -> {
            if (closed || generation != checkGeneration) return;
            if (finalResult == Hosted.VERIFIED) publishOffer();
            hosted = finalResult;
            hostedDetail = finalDetail;
            if (finalResult == Hosted.VERIFIED) {
                plugin.logger().info("Sending Allium's resource pack from " + url + ": " + finalDetail + mode() + ".");
                reply.accept("The hosted resource pack matches; sending it.");
                sendPendingOnline();
            } else {
                plugin.logger().severe("Not sending Allium's resource pack: " + finalDetail + ".");
                reply.accept("Not sending the resource pack: " + finalDetail + ".");
            }
            waiting.clear();
        });
    }

    private static String download(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "Allium resource pack check");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            long total = 0;
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_HOSTED_BYTES) {
                        throw new IOException("larger than " + MAX_HOSTED_BYTES + " bytes");
                    }
                    digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            connection.disconnect();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        switch (hosted) {
            case VERIFIED, UNVERIFIED -> sendOnPlayer(player, () -> send(player), TaskScheduler.MILLIS_PER_TICK);
            case VERIFYING -> waiting.add(player.getUniqueId());
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        waiting.remove(id);
        sentTo.remove(id);
    }

    void send(Player player) {
        PackOffer current = offer;
        if (current == null || current.url() == null || current.url().isBlank()) return;
        ResourcePackSettings settings = current.settings();
        String url = current.url();
        String prompt = settings.prompt() == null || settings.prompt().isBlank() ? null : TextFormatter.parse(settings.prompt()).toLegacy();
        try {
            if (addResourcePack != null) {
                addResourcePack.invoke(player, PACK_ID, url, current.hash(), prompt, settings.required());
            } else {
                player.setResourcePack(url, current.hash(), prompt, settings.required());
            }
            sentTo.put(player.getUniqueId(), current);
            plugin.logger().debug("Sent Allium's resource pack to " + player.getName() + " (" + url + ", SHA-1 " + current.sha1() + ").");
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.logger().warning("Could not send Allium's resource pack to " + player.getName() + ": " + e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID pack = packOf(event);
        PackOffer current = offer;
        if (current == null || (pack != null ? !pack.equals(PACK_ID) : !sentTo.containsKey(player.getUniqueId()))) {
            return; // another plugin's or server.properties' pack
        }
        String status = event.getStatus().name();
        lastStatus.put(player.getUniqueId(), status);
        switch (status) {
            case "ACCEPTED", "DOWNLOADED" -> plugin.logger().debug(player.getName() + ": Allium's resource pack " + status + ".");
            case "SUCCESSFULLY_LOADED" -> plugin.logger().info(player.getName() + " loaded Allium's resource pack.");
            default -> {
                String what = "DECLINED".equals(status) ? "declined" : "could not load (" + status + ")";
                if (current.settings().required()) {
                    plugin.logger().info(player.getName() + " " + what + " Allium's required resource pack; kicking them.");
                    String message = TextFormatter.parse(current.settings().kickMessage()).toLegacy();
                    sendOnPlayer(player, () -> { if (offer == current) player.kickPlayer(message); }, 0);
                } else {
                    plugin.logger().info(player.getName() + " " + what + " Allium's resource pack; they keep the named vanilla items.");
                }
            }
        }
    }

    @Nullable
    private UUID packOf(PlayerResourcePackStatusEvent event) {
        if (statusId == null) {
            return null;
        }
        try {
            return (UUID) statusId.invoke(event);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public Hosted hosted() {
        return hosted;
    }

    public String hostedDetail() {
        return hostedDetail;
    }

    @Nullable
    public String lastStatus(UUID player) {
        return lastStatus.get(player);
    }

    public String activeUrl() {
        return activeUrl;
    }

    public int selfHostPort() {
        return httpServer == null ? -1 : httpServer.port();
    }

    public void disable() {
        closed = true;
        ++checkGeneration;
        offer = null;
        HandlerList.unregisterAll(this);
        stopSelfHost();
        if (checkPool != null) {
            checkPool.shutdownNow();
        }
        waiting.clear();
        sentTo.clear();
    }
}
