package codes.castled.allium.pack.delivery;

import codes.castled.allium.PluginStart;
import codes.castled.allium.pack.ResourcePackSettings;
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

    private final PluginStart plugin;
    private volatile ResourcePackSettings settings;
    private volatile String sha1;
    private volatile byte[] hash;
    private final TaskScheduler scheduler;
    private final @Nullable Method addResourcePack;
    private final @Nullable Method statusId;
    private volatile Hosted hosted = Hosted.OFF;
    private volatile String hostedDetail = "";
    private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();
    private final Set<UUID> sentTo = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastStatus = new ConcurrentHashMap<>();
    private ThreadPoolExecutor checkPool;

    public PackDelivery(PluginStart plugin, ResourcePackSettings settings, PackSource pack) {
        this.plugin = plugin;
        this.settings = settings;
        this.sha1 = pack.sha1();
        this.hash = HexFormat.of().parseHex(pack.sha1());
        this.scheduler = plugin.getScheduler();
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
        String serverPack = Bukkit.getResourcePack();
        if (!multiplePacks() && serverPack != null && !serverPack.isBlank()) {
            plugin.logger().warning("This server version gives each player one server resource pack, so it needs exactly one delivery "
                    + "owner. server.properties also sets resource-pack, and Allium's pack replaces it for every player. Clear "
                    + "resource-pack in server.properties, or set resource-pack.owner to none.");
        }
        if (!settings.verifyHostedFile()) {
            hosted = Hosted.UNVERIFIED;
            plugin.logger().info("Sending Allium's resource pack from " + settings.url() + " (SHA-1 " + sha1 + ", not verified: "
                    + "verify-hosted-file is off)" + mode() + ".");
            return;
        }
        startCheck(message -> { });
    }

    private void startCheck(java.util.function.Consumer<String> reply) {
        hosted = Hosted.VERIFYING;
        if (checkPool == null) {
            checkPool = scheduler.boundedPool("Allium-PackCheck", 1, 2);
        }
        checkPool.execute(() -> verifyHostedFile(reply));
    }

    /**
     * {@code /allium reload}: applies new URL, required, prompt and kick-message settings and the
     * (possibly new) pack, then checks the hosted file again. Until the check finishes, joining
     * players wait for its result. If it now matches, online players who do not have the pack yet
     * are sent it.
     */
    public void reload(ResourcePackSettings next, PackSource pack, java.util.function.Consumer<String> reply) {
        settings = next;
        if (!pack.sha1().equals(sha1)) {
            sentTo.clear(); // a different pack: everyone online gets it once it verifies
        }
        sha1 = pack.sha1();
        hash = HexFormat.of().parseHex(pack.sha1());
        if (!next.sendsUrl()) {
            hosted = Hosted.OFF;
            reply.accept("resource-pack.standalone.url is not set; Allium sends no pack until it is.");
            return;
        }
        if (!listening) {
            enable();
            return;
        }
        if (!next.verifyHostedFile()) {
            hosted = Hosted.UNVERIFIED;
            reply.accept("Sending " + next.url() + " (SHA-1 " + sha1 + ") without checking it: verify-hosted-file is off.");
            return;
        }
        reply.accept("Checking the hosted resource pack at " + next.url() + "...");
        startCheck(reply);
    }

    private String mode() {
        return (multiplePacks() ? ", added by UUID " + PACK_ID + " beside any other packs" : ", as the player's one server pack")
                + (settings.required() ? ", required" : ", optional");
    }

    private void verifyHostedFile(java.util.function.Consumer<String> reply) {
        Hosted result;
        String detail;
        ResourcePackSettings settings = this.settings;
        String sha1 = this.sha1;
        try {
            String found = download(settings.url());
            if (found.equals(sha1)) {
                result = Hosted.VERIFIED;
                detail = "the hosted file matches (SHA-1 " + sha1 + ")";
            } else {
                result = Hosted.MISMATCH;
                detail = "the file at " + settings.url() + " has SHA-1 " + found + ", but Allium's bundled pack has SHA-1 " + sha1
                        + ". Upload plugins/Allium/pack/" + PackExport.STANDALONE_FILE + " there again; until then Allium sends nothing";
            }
        } catch (IOException | RuntimeException e) {
            result = Hosted.UNREACHABLE;
            detail = "could not download " + settings.url() + " (" + e + "); Allium sends nothing until a restart finds it";
        }
        Hosted finalResult = result;
        String finalDetail = detail;
        scheduler.runOnServer(() -> {
            hosted = finalResult;
            hostedDetail = finalDetail;
            if (finalResult == Hosted.VERIFIED) {
                plugin.logger().info("Sending Allium's resource pack from " + settings.url() + ": " + finalDetail + mode() + ".");
                reply.accept("The hosted resource pack matches; sending it.");
                for (Player player : Bukkit.getOnlinePlayers()) {
                    // Players who joined during the check, and (after a reload) anyone online without it yet.
                    if (waiting.contains(player.getUniqueId()) || !sentTo.contains(player.getUniqueId())) {
                        send(player);
                    }
                }
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
            case VERIFIED, UNVERIFIED -> scheduler.runOnServerLater(() -> {
                if (player.isOnline()) {
                    send(player);
                }
            }, TaskScheduler.MILLIS_PER_TICK);
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
        ResourcePackSettings settings = this.settings;
        String prompt = settings.prompt() == null || settings.prompt().isBlank() ? null : TextFormatter.parse(settings.prompt()).toLegacy();
        try {
            if (addResourcePack != null) {
                addResourcePack.invoke(player, PACK_ID, settings.url(), hash, prompt, settings.required());
            } else {
                player.setResourcePack(settings.url(), hash, prompt, settings.required());
            }
            sentTo.add(player.getUniqueId());
            plugin.logger().debug("Sent Allium's resource pack to " + player.getName() + " (" + settings.url() + ", SHA-1 " + sha1 + ").");
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.logger().warning("Could not send Allium's resource pack to " + player.getName() + ": " + e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID pack = packOf(event);
        if (pack != null ? !pack.equals(PACK_ID) : !sentTo.contains(player.getUniqueId())) {
            return; // another plugin's or server.properties' pack
        }
        String status = event.getStatus().name();
        lastStatus.put(player.getUniqueId(), status);
        switch (status) {
            case "ACCEPTED", "DOWNLOADED" -> plugin.logger().debug(player.getName() + ": Allium's resource pack " + status + ".");
            case "SUCCESSFULLY_LOADED" -> plugin.logger().info(player.getName() + " loaded Allium's resource pack.");
            default -> {
                String what = "DECLINED".equals(status) ? "declined" : "could not load (" + status + ")";
                if (settings.required()) {
                    plugin.logger().info(player.getName() + " " + what + " Allium's required resource pack; kicking them.");
                    String message = TextFormatter.parse(settings.kickMessage()).toLegacy();
                    scheduler.runOnServer(() -> {
                        if (player.isOnline()) {
                            player.kickPlayer(message);
                        }
                    });
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

    public void disable() {
        HandlerList.unregisterAll(this);
        if (checkPool != null) {
            checkPool.shutdownNow();
        }
        waiting.clear();
        sentTo.clear();
    }
}
