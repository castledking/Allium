package codes.castled.allium.modguard;

import codes.castled.allium.scheduler.TaskScheduler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Asks Lunar Client for its installed mods over Apollo's JSON channel, and reads its handshake. */
final class LunarWire implements AutoCloseable {
    static final String CHANNEL = "apollo:json";
    static final String OFFER = "lunar:apollo";
    static final String REQUEST = "type.googleapis.com/lunarclient.apollo.modsetting.v1.InstalledModsRequest";
    static final String HANDSHAKE = "lunarclient.apollo.player.v1.PlayerHandshakeMessage";
    static final String RESPONSE = "lunarclient.apollo.modsetting.v1.InstalledModsResponse";

    private final TaskScheduler scheduler;
    private final ModGuard modGuard;
    private final Map<UUID, Pending> pending = new HashMap<>();
    boolean enabled = true;
    BiConsumer<Player, byte[]> sender;

    LunarWire(Plugin plugin, TaskScheduler scheduler, ModGuard modGuard) {
        this.scheduler = scheduler;
        this.modGuard = modGuard;
        this.sender = (player, data) -> player.sendPluginMessage(plugin, CHANNEL, data);
    }

    boolean offered(Player player) {
        ModGuardSession session = modGuard.session(player.getUniqueId());
        return session != null && (session.channels.contains(OFFER) || player.getListeningPluginChannels().contains(OFFER));
    }

    void request(Player player, Consumer<List<Detection.Mod>> success, Consumer<String> failure) {
        if (!enabled) return;
        ModGuardSession session = modGuard.session(player.getUniqueId());
        if (session == null || !player.isOnline() || player.isOp() || !offered(player)) return;
        Pending request = new Pending(session.token, success, failure);
        pending.put(session.uuid, request);
        JsonObject packet = new JsonObject();
        packet.addProperty("@type", REQUEST);
        packet.addProperty("request_id", request.id.toString());
        try {
            sender.accept(player, packet.toString().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException failed) {
            pending.remove(session.uuid, request);
            return;
        }
        scheduler.runOnServerLater(() -> {
            if (pending.remove(session.uuid, request)) failure.accept("Lunar mod-list request timed out");
        }, 200 * TaskScheduler.MILLIS_PER_TICK);
    }

    void receive(Player player, byte[] bytes) {
        Pending request = pending.get(player.getUniqueId());
        ModGuardSession session = modGuard.session(player.getUniqueId());
        if (session == null || player.isOp() || bytes.length > Wire.MAX_PAYLOAD) return;
        JsonObject packet;
        try {
            packet = JsonParser.parseString(Wire.utf8(bytes, Wire.MAX_PAYLOAD)).getAsJsonObject();
        } catch (Exception malformed) {
            return;
        }
        try {
            String type = packet.has("@type") ? packet.get("@type").getAsString() : "";
            if (type.startsWith("type.googleapis.com/")) type = type.substring(20);
            if (HANDSHAKE.equals(type)) {
                if (packet.has("installed_mods")) {
                    List<Detection.Mod> mods = new ArrayList<>();
                    for (JsonElement element : packet.getAsJsonArray("installed_mods")) {
                        if (mods.size() > Wire.MAX_MODS) break;
                        mods.add(mod(element.getAsJsonObject(), "LUNAR"));
                    }
                    modGuard.reportedMods(player, mods.stream().filter(m -> !m.id().isBlank()).toList(), "LUNAR_HANDSHAKE");
                }
                return;
            }
            if (request == null || !session.token.equals(request.session) || !RESPONSE.equals(type)) return;
            if (!UUID.fromString(packet.get("request_id").getAsString()).equals(request.id)) return;
            int page = packet.has("page") ? packet.get("page").getAsInt() : 0;
            int total = packet.has("total_pages") ? packet.get("total_pages").getAsInt() : 1;
            if (total < 1 || total > 32 || page < 0 || page >= total || request.count != -1 && request.count != total
                    || request.pages.containsKey(page)) return;
            List<Detection.Mod> entries = new ArrayList<>();
            if (packet.has("mod_groups")) {
                for (JsonElement element : packet.getAsJsonArray("mod_groups")) {
                    JsonObject group = element.getAsJsonObject();
                    String kind = group.has("type") ? group.get("type").getAsString() : "LUNAR";
                    if (!group.has("mods")) continue;
                    JsonArray mods = group.getAsJsonArray("mods");
                    for (JsonElement mod : mods) {
                        if (entries.size() > Wire.MAX_MODS) break;
                        Detection.Mod read = mod(mod.getAsJsonObject(), kind);
                        if (!read.id().isBlank()) entries.add(read);
                    }
                }
            }
            request.count = total;
            request.pages.put(page, entries);
            if (request.pages.size() == total) {
                pending.remove(session.uuid, request);
                List<Detection.Mod> all = new ArrayList<>();
                request.pages.values().forEach(all::addAll);
                request.success.accept(List.copyOf(all));
            }
        } catch (Exception malformed) {
            // A malformed response is ignored; the request times out.
        }
    }

    private static Detection.Mod mod(JsonObject mod, String fallbackKind) {
        return new Detection.Mod(text(mod, "id"), text(mod, "name"), text(mod, "version"),
                mod.has("type") ? mod.get("type").getAsString() : fallbackKind);
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    void quit(UUID id) {
        pending.remove(id);
    }

    @Override
    public void close() {
        pending.clear();
    }

    private static final class Pending {
        final UUID id = UUID.randomUUID();
        final UUID session;
        final Consumer<List<Detection.Mod>> success;
        final Consumer<String> failure;
        int count = -1;
        final Map<Integer, List<Detection.Mod>> pages = new TreeMap<>();

        Pending(UUID session, Consumer<List<Detection.Mod>> success, Consumer<String> failure) {
            this.session = session;
            this.success = success;
            this.failure = failure;
        }
    }
}
