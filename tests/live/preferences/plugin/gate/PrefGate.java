package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.MessageSoundRules;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceRepository;
import codes.castled.allium.preference.PreferenceSessions;
import codes.castled.allium.preference.PreferenceStore;
import codes.castled.allium.preference.StoredPreferences;
import codes.castled.allium.storage.SqlDialect;
import com.google.gson.GsonBuilder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * S03 preferences gate fixture: drives Allium's preference sessions from the console and reports
 * what they hold. It stands in for the commands S07/S08 will add; it never touches the database
 * except through Allium's repository, or through an independent store over its own connection
 * (the "second writer" for SQLite).
 */
public final class PrefGate extends JavaPlugin implements Listener {

    private PreferencesModule module;
    private final List<Map<String, Object>> joins = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onEnable() {
        PluginStart allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
        module = (PreferencesModule) allium.getModuleManager().getModule("preferences");
        getServer().getPluginManager().registerEvents(this, this);
        try {
            write("wire-ids", Map.of("wirePackets", WireIds.dump()));
        } catch (Throwable e) {
            getLogger().info("S03G wire catalog unavailable on this version (protocol library client is used): " + e);
        }
        getLogger().info("S03G-GATE READY " + getServer().getBukkitVersion() + " module=" + (module != null && module.isEnabled()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Map<String, Object> join = new LinkedHashMap<>();
        join.put("name", event.getPlayer().getName());
        join.put("at", System.currentTimeMillis());
        module.sessions().view(event.getPlayer().getUniqueId()).ifPresentOrElse(v -> {
            join.put("state", v.state().name());
            join.put("generation", v.generation());
        }, () -> join.put("state", "NONE"));
        joins.add(join);
    }

    static UUID offline(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("console only");
            return true;
        }
        String text = String.join(" ", args);
        try {
            run(text, args);
        } catch (Throwable e) {
            fail(text, e);
        }
        return true;
    }

    private void run(String text, String[] args) throws Exception {
        PreferenceSessions sessions = module.sessions();
        switch (args[0]) {
            case "view" -> done(text, view(args[1]));
            case "joins" -> {
                synchronized (joins) {
                    done(text, new ArrayList<>(joins));
                }
            }
            case "stats" -> done(text, sessions.stats());
            case "fault" -> {
                FaultInjection.arm(args[1], Integer.parseInt(args[2]));
                done(text, Map.of("armed", args[1], "count", args[2]));
            }
            case "tripped" -> done(text, Map.of("point", args[1], "tripped", FaultInjection.tripped(args[1])));
            case "set" -> {
                PreferenceKey key = PreferenceKey.fromName(args[2]).orElseThrow(() -> new IllegalArgumentException("unknown key " + args[2]));
                PreferenceChange change = args[3].equals("unset") ? PreferenceChange.unset(key)
                        : PreferenceChange.set(key, key == PreferenceKey.ACCEPT_MESSAGES || key == PreferenceKey.ACCEPT_PAYMENTS
                        || key == PreferenceKey.ACCEPT_TELEPORT_REQUESTS ? (Object) Boolean.parseBoolean(args[3]) : (Object) Double.parseDouble(args[3]));
                track(text, sessions.change(offline(args[1]), change));
            }
            case "volume" -> {
                UUID uuid = offline(args[1]);
                PreferenceSessions.View view = sessions.view(uuid).orElseThrow(() -> new IllegalStateException("no session"));
                if (view.state() != PreferenceSessions.State.READY) {
                    throw new IllegalStateException("preferences for " + uuid + " are " + view.state());
                }
                track(text, sessions.change(uuid, MessageSoundRules.setVolume(view.stored(), Double.parseDouble(args[2]))));
            }
            case "reset" -> track(text, sessions.change(offline(args[1]), MessageSoundRules.reset()));
            case "toggle" -> {
                ack(text);
                sessions.toggleSound(offline(args[1])).whenComplete((toggle, error) -> {
                    if (error != null) {
                        fail(text, error);
                    } else {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("volume", toggle.volume());
                        out.put("outcome", toggle.outcome().name());
                        out.put("stillMuted", toggle.stillMuted());
                        out.put("view", view(args[1]));
                        done(text, out);
                    }
                });
            }
            case "db" -> {
                ack(text);
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return module.repository().load(offline(args[1])).orElse(null);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).whenComplete((row, error) -> {
                    if (error != null) {
                        fail(text, error);
                    } else {
                        done(text, row == null ? Map.of("row", "none") : row);
                    }
                });
            }
            case "burst" -> burst(text, args);
            case "burst-pair" -> burstPair(text, args);
            default -> throw new IllegalArgumentException("unknown action " + args[0]);
        }
    }

    /**
     * Writer A flips accept-messages (ending true after an even count), writer B flips
     * accept-payments (ending false), and both write the shared message-sound-volume (A ends at
     * 0.001 x count, B at 0.5 + 0.001 x count).
     */
    private static PreferenceChange patch(boolean a, int i) {
        return PreferenceChange.set(a ? PreferenceKey.ACCEPT_MESSAGES : PreferenceKey.ACCEPT_PAYMENTS, a ? i % 2 == 1 : i % 2 == 0)
                .andSet(PreferenceKey.MESSAGE_SOUND_VOLUME, Math.round(((a ? 0.0D : 0.5D) + 0.001D * (i + 1)) * 1000.0D) / 1000.0D);
    }

    /** {@code burst <name> <A|B> <count>}: submits the writer's patches through Allium's session from the server thread. */
    private void burst(String text, String[] args) {
        UUID uuid = offline(args[1]);
        boolean a = args[2].equals("A");
        int count = Integer.parseInt(args[3]);
        ack(text);
        List<CompletableFuture<StoredPreferences>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(module.sessions().change(uuid, patch(a, i)));
        }
        report(text, futures);
    }

    /**
     * {@code burst-pair <name> <count>} (SQLite): writer A through Allium's session, writer B through
     * a second, independent store over its own connection to the same file (like another process),
     * released together once B's connection is open.
     */
    private void burstPair(String text, String[] args) throws Exception {
        UUID uuid = offline(args[1]);
        int count = Integer.parseInt(args[2]);
        if (!(getServer().getPluginManager().getPlugin("Allium") instanceof PluginStart allium)
                || allium.getDatabaseManager().dialect() != SqlDialect.SQLITE) {
            throw new IllegalStateException("burst-pair is for SQLite lanes");
        }
        ack(text);
        String url = "jdbc:sqlite:" + allium.getDataFolder().toPath().resolve("allium.db").toAbsolutePath();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        Thread thread = new Thread(() -> {
            PreferenceRepository repository = new PreferenceRepository(() -> {
                Connection connection = DriverManager.getConnection(url);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA busy_timeout = 10000");
                }
                return connection;
            }, SqlDialect.SQLITE, System::currentTimeMillis);
            PreferenceStore store = new PreferenceStore(repository, 1, 1024, u -> { });
            try {
                store.load(uuid).get();
                ready.countDown();
                go.await();
            } catch (Exception e) {
                fail(text + " B", e);
                return;
            }
            List<CompletableFuture<StoredPreferences>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(store.apply(uuid, patch(false, i)));
            }
            report(text + " B", futures);
            store.close(30_000L);
        }, "PrefGate-external-writer");
        thread.start();
        if (!ready.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("external writer did not open its connection");
        }
        go.countDown();
        List<CompletableFuture<StoredPreferences>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(module.sessions().change(uuid, patch(true, i)));
        }
        report(text + " A", futures);
    }

    private void report(String text, List<CompletableFuture<StoredPreferences>> futures) {
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).handle((ignored, error) -> {
            long failed = futures.stream().filter(CompletableFuture::isCompletedExceptionally).count();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", futures.size() - failed);
            out.put("failed", failed);
            futures.stream().filter(f -> !f.isCompletedExceptionally()).reduce((x, y) -> y).ifPresent(f -> out.put("last", f.join()));
            futures.stream().filter(CompletableFuture::isCompletedExceptionally).findFirst().ifPresent(f -> f.handle((x, e) -> out.put("error", String.valueOf(e))));
            done(text, out);
            return null;
        });
    }

    private Map<String, Object> view(String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        UUID uuid = offline(name);
        out.put("uuid", uuid.toString());
        out.put("online", getServer().getPlayer(uuid) != null);
        module.sessions().view(uuid).ifPresentOrElse(v -> {
            out.put("state", v.state().name());
            out.put("generation", v.generation());
            out.put("stored", v.stored());
            EffectivePreferences effective = v.effective();
            out.put("effective", effective);
            out.put("failure", v.failure());
        }, () -> out.put("state", "NONE"));
        return out;
    }

    private void track(String text, CompletableFuture<StoredPreferences> future) {
        ack(text);
        future.whenComplete((row, error) -> {
            if (error != null) {
                fail(text, error);
            } else {
                done(text, row);
            }
        });
    }

    private void ack(String text) {
        getLogger().info("S03G-ACK " + text);
    }

    private void done(String text, Object value) {
        getLogger().info("S03G-DONE " + text + " => " + new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().create().toJson(value));
    }

    private void fail(String text, Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
        getLogger().info("S03G-FAIL " + text + " => " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    private void write(String name, Object value) throws IOException {
        Path dir = getDataFolder().toPath();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name + ".json.tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(value), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(name + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Packet IDs for the raw 26.3 client, from the running server's protocol catalog. */
    static final class WireIds {
        static Map<String, Object> dump() throws Exception {
            Map<String, Object> result = new LinkedHashMap<>();
            Class<?> provider = Class.forName("net.minecraft.network.ProtocolInfo$DetailsProvider");
            Class<?> detailsType = Class.forName("net.minecraft.network.ProtocolInfo$Details");
            Class<?> visitor = Class.forName("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
            Class<?> packetType = Class.forName("net.minecraft.network.protocol.PacketType");
            for (String phase : List.of("game", "configuration", "login")) {
                String capital = phase.substring(0, 1).toUpperCase() + phase.substring(1);
                Class<?> protocols = Class.forName("net.minecraft.network.protocol." + phase + "." + capital + "Protocols");
                for (String flow : List.of("CLIENTBOUND", "SERVERBOUND")) {
                    Object template = protocols.getField(flow + "_TEMPLATE").get(null);
                    Object details = provider.getMethod("details").invoke(template);
                    Map<String, Integer> ids = new LinkedHashMap<>();
                    Object callback = java.lang.reflect.Proxy.newProxyInstance(visitor.getClassLoader(), new Class[]{visitor},
                            (proxy, method, a) -> {
                                if (method.getName().equals("accept")) {
                                    ids.put(packetType.getMethod("id").invoke(a[0]).toString(), (Integer) a[1]);
                                }
                                return null;
                            });
                    detailsType.getMethod("listPackets", visitor).invoke(details, callback);
                    result.put(phase + "_" + flow, ids);
                }
            }
            return result;
        }
    }
}
