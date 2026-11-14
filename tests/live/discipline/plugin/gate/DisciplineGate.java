package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.moderation.DisciplineService;
import codes.castled.allium.module.ModerationModule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Fixture preparation and read-only observations only. No discipline mutation or command dispatch. */
public final class DisciplineGate extends JavaPlugin implements Listener {
    private PluginStart allium;
    private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();
    private final Map<UUID, Integer> moves = new HashMap<>(), teleports = new HashMap<>(), blocked = new HashMap<>();

    @Override public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || getServer().getPort() < 25790 || getServer().getPort() > 25792)
            throw new IllegalStateException("TEST ONLY: 127.0.0.1:25790-25792 required");
        allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
        try {
            Files.createDirectories(getDataFolder().toPath());
            try { Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"), json(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8); }
            catch (ReflectiveOperationException legacy) { getLogger().info("S19G legacy protocol client required"); }
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("S19G-GATE READY " + getServer().getBukkitVersion());
    }

    private DisciplineService discipline() {
        return ((ModerationModule) allium.getModuleManager().getModule("moderation")).discipline();
    }
    private Player player(String name) {
        Player p = getServer().getPlayerExact(name);
        if (p == null) throw new IllegalArgumentException("online player required: " + name);
        return p;
    }
    private UUID uuid(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        String text = String.join(" ", args);
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            switch (args[0]) {
                case "online" -> out.put("players", getServer().getOnlinePlayers().stream().map(Player::getName).toList());
                case "perm" -> {
                    Player p = player(args[1]);
                    permissions.computeIfAbsent(p.getUniqueId(), id -> p.addAttachment(this)).setPermission(args[2], Boolean.parseBoolean(args[3]));
                    p.updateCommands();
                    out.put("permission", p.hasPermission(args[2]));
                }
                case "prep" -> {
                    Player p = player(args[1]);
                    if (discipline().isFrozen(p.getUniqueId()) || discipline().jailed(p.getUniqueId())) throw new IllegalStateException("cannot prepare constrained player");
                    double x = Double.parseDouble(args[2]), z = Double.parseDouble(args[3]);
                    World world = p.getWorld();
                    for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
                        world.getBlockAt((int)Math.floor(x)+dx, 79, (int)Math.floor(z)+dz).setType(Material.STONE, false);
                        for (int y = 80; y < 84; y++) world.getBlockAt((int)Math.floor(x)+dx, y, (int)Math.floor(z)+dz).setType(Material.AIR, false);
                    }
                    p.setAllowFlight(true); p.setFlying(true);
                    out.put("teleported", p.teleport(new Location(world, x, 80, z)));
                }
                case "state" -> {
                    UUID id = uuid(args[1]); Player p = getServer().getPlayer(id);
                    out.put("uuid", id.toString()); out.put("online", p != null && p.isOnline());
                    out.put("ready", discipline().ready(id)); out.put("frozen", discipline().isFrozen(id));
                    out.put("jailed", discipline().jailed(id)); out.put("banned", discipline().isBanned(id));
                    out.put("pendingWrites", discipline().pendingWrites());
                    out.put("moves", moves.getOrDefault(id,0)); out.put("teleports", teleports.getOrDefault(id,0));
                    out.put("blockedTeleports", blocked.getOrDefault(id,0));
                    if (p != null) {
                        Location l = p.getLocation(); out.put("world", l.getWorld().getName());
                        out.put("x", l.getX()); out.put("y", l.getY()); out.put("z", l.getZ());
                        out.put("yaw", l.getYaw()); out.put("pitch", l.getPitch());
                    }
                }
                case "owners" -> {
                    Object map = getServer().getClass().getMethod("getCommandMap").invoke(getServer());
                    for (String name : List.of("kick","ban","tempban","unban","pardon","banlist","ipban","freeze","setjail","jail","unjail","warn","notes","minecraft:ban","minecraft:kick","minecraft:pardon")) {
                        Command c = (Command) map.getClass().getMethod("getCommand", String.class).invoke(map, name);
                        out.put(name, c == null ? "missing" : (c instanceof org.bukkit.command.PluginCommand pc ? pc.getPlugin().getName()+":" : "native:")+c.getClass().getName()+":"+c.getName());
                    }
                }
                default -> throw new IllegalArgumentException("unknown fixture: " + args[0]);
            }
            getLogger().info("S19G-DONE " + text + " => " + json(out));
        } catch (Throwable error) { getLogger().info("S19G-FAIL " + text + " => " + error); }
        return true;
    }
    @EventHandler(priority=EventPriority.MONITOR) public void move(PlayerMoveEvent event) {
        if (!(event instanceof PlayerTeleportEvent)) moves.merge(event.getPlayer().getUniqueId(),1,Integer::sum);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void teleport(PlayerTeleportEvent event) {
        teleports.merge(event.getPlayer().getUniqueId(),1,Integer::sum);
        if (event.isCancelled()) blocked.merge(event.getPlayer().getUniqueId(),1,Integer::sum);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { permissions.remove(event.getPlayer().getUniqueId()); }
    private static String json(Object v) {
        if (v == null) return "null";
        if (v instanceof Map<?, ?> m) {
            return "{" + String.join(",", m.entrySet().stream()
                    .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (v instanceof Collection<?> c) {
            return "[" + String.join(",", c.stream().map(DisciplineGate::json).toList()) + "]";
        }
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return "\"" + v.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

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
