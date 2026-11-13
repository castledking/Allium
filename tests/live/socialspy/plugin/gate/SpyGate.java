package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.module.MessagingModule;
import codes.castled.allium.module.PreferencesModule;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** S07's permission/vanish/wire controls, reused for S11. Never sends or spies on messages. */
public final class SpyGate extends JavaPlugin implements Listener {
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || !Set.of(25710, 25711).contains(getServer().getPort())) {
            throw new IllegalStateException("TEST ONLY: require 127.0.0.1 port 25710 or 25711");
        }
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),
                    json(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8);
        } catch (ReflectiveOperationException e) {
            getLogger().info("S11G wire catalog unavailable; use the S07 protocol-library client: " + e);
        } catch (Exception e) {
            throw new IllegalStateException("cannot write wire catalog", e);
        }
        MessagingModule module = (MessagingModule) ((PluginStart) getServer().getPluginManager().getPlugin("Allium"))
                .getModuleManager().getModule("messaging");
        module.messaging().setMuteSource(muted::contains);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("S11G-GATE READY " + getServer().getBukkitVersion());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        String text = String.join(" ", args);
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (args[0].equals("sound")) {
                JavaPlugin allium = (JavaPlugin) getServer().getPluginManager().getPlugin("Allium");
                java.io.File file = new java.io.File(allium.getDataFolder(), "lang/en_US.yml");
                YamlConfiguration lang = YamlConfiguration.loadConfiguration(file);
                String key = "messaging.socialspy.message";
                String template = lang.isString(key) ? lang.getString(key) : lang.getString(key + ".text");
                if (template == null) throw new IllegalStateException("missing spy lang entry");
                lang.set(key, null);
                lang.set(key + ".text", template);
                if (args[1].equals("on")) {
                    lang.set(key + ".sound", "minecraft:entity.experience_orb.pickup");
                    lang.set(key + ".volume", 0.6);
                    lang.set(key + ".pitch", 1.0);
                }
                lang.save(file);
                getLogger().info("S11G-DONE " + text + " => {}");
                return true;
            }
            if (args[0].equals("config")) {
                if (!Set.of("true", "false").contains(args[2])) throw new IllegalArgumentException("boolean required");
                JavaPlugin allium = (JavaPlugin) getServer().getPluginManager().getPlugin("Allium");
                out.put("before", allium.getConfig().getBoolean(args[1], false));
                allium.getConfig().set(args[1], Boolean.parseBoolean(args[2]));
                allium.saveConfig();
                out.put("after", allium.getConfig().getBoolean(args[1]));
                getLogger().info("S11G-DONE " + text + " => " + json(out));
                return true;
            }
            Player target = args.length > 1 ? getServer().getPlayerExact(args[1]) : null;
            if (target == null) throw new IllegalArgumentException("online player required");
            switch (args[0]) {
                case "vanish" -> {
                    if (args[2].equals("on")) target.setMetadata("vanished", new FixedMetadataValue(this, true));
                    else target.removeMetadata("vanished", this);
                    out.put("vanished", target.getMetadata("vanished").stream().anyMatch(v -> v.asBoolean()));
                }
                case "hide" -> {
                    Player hidden = getServer().getPlayerExact(args[2]);
                    if (hidden == null) throw new IllegalArgumentException("online hidden player required");
                    if (args[3].equals("on")) target.hidePlayer(this, hidden);
                    else target.showPlayer(this, hidden);
                    out.put("canSee", target.canSee(hidden));
                }
                case "mute" -> {
                    if (args[2].equals("on")) muted.add(target.getUniqueId());
                    else muted.remove(target.getUniqueId());
                    out.put("muted", muted.contains(target.getUniqueId()));
                }
                case "perm" -> {
                    PermissionAttachment attachment = attachments.computeIfAbsent(target.getUniqueId(), id -> target.addAttachment(this));
                    if (args[3].equals("unset")) attachment.unsetPermission(args[2]);
                    else attachment.setPermission(args[2], Boolean.parseBoolean(args[3]));
                    target.updateCommands();
                    out.put("permission", target.hasPermission(args[2]));
                }
                case "inspect" -> {
                    Permission permission = getServer().getPluginManager().getPermission(args[2]);
                    out.put("registered", permission != null);
                    out.put("default", permission == null ? null : permission.getDefault().name());
                    out.put("permission", target.hasPermission(args[2]));
                    out.put("op", target.isOp());
                }
                case "state" -> {
                    PluginStart allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
                    PreferencesModule preferences = (PreferencesModule) allium.getModuleManager().getModule("preferences");
                    var current = preferences.effective(target.getUniqueId());
                    out.put("loaded", current.isPresent());
                    out.put("socialSpy", current.map(p -> p.socialSpy()).orElse(false));
                }
                case "op" -> {
                    target.setOp(Boolean.parseBoolean(args[2]));
                    out.put("op", target.isOp());
                }
                default -> throw new IllegalArgumentException("unknown action " + args[0]);
            }
            getLogger().info("S11G-DONE " + text + " => " + json(out));
        } catch (Throwable e) {
            getLogger().info("S11G-FAIL " + text + " => " + e);
        }
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        attachments.remove(event.getPlayer().getUniqueId());
        muted.remove(event.getPlayer().getUniqueId());
    }

    // Only primitives/maps are needed for the protocol catalog and command acknowledgements.
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            return "{" + String.join(",", map.entrySet().stream()
                    .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    /** Unchanged packet discovery from tests/live/messaging's MsgGate; no NMS compile dependency. */
    static final class WireIds {
        static Map<String, Object> dump() throws ReflectiveOperationException {
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
                                if (method.getName().equals("accept")) ids.put(packetType.getMethod("id").invoke(a[0]).toString(), (Integer) a[1]);
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
