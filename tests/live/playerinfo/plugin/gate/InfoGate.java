package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.info.PlayerInfoRecord;
import codes.castled.allium.info.PlayerInfoRepository;
import codes.castled.allium.info.PlayerInfoService;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.module.PlayerInfoModule;
import codes.castled.allium.naming.PlayerNameRepository;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Only prepares fixtures and inspects Bukkit/DB state; tested commands run over real bots and console. */
public final class InfoGate extends JavaPlugin implements Listener {

    private PluginStart allium;
    private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();
    private volatile int nightSkipCount;
    private volatile String lastSkipReason;

    @Override
    public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || (getServer().getPort() < 25780 || getServer().getPort() > 25782)) {
            throw new IllegalStateException("TEST ONLY: 127.0.0.1:25780-25782 required");
        }
        allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
        try {
            Files.createDirectories(getDataFolder().toPath());
            try {
                Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),
                        json(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8);
            } catch (ReflectiveOperationException e) {
                getLogger().info("S18G legacy protocol client required");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("S18G-GATE READY " + getServer().getBukkitVersion());
    }

    private ModerationService moderation() {
        ModerationModule mod = (ModerationModule) allium.getModuleManager().getModule("moderation");
        return mod.moderation();
    }

    private PlayerInfoService info() {
        PlayerInfoModule mod = (PlayerInfoModule) allium.getModuleManager().getModule("info");
        return mod.info();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        String text = String.join(" ", args);
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            switch (args[0]) {
                case "online" -> out.put("players", getServer().getOnlinePlayers().stream().map(Player::getName).toList());
                case "perm" -> {
                    Player target = getServer().getPlayerExact(args[1]);
                    if (target == null) throw new IllegalArgumentException("online player required: " + args[1]);
                    PermissionAttachment p = permissions.computeIfAbsent(target.getUniqueId(), id -> target.addAttachment(this));
                    p.setPermission(args[2], Boolean.parseBoolean(args[3]));
                    target.updateCommands();
                    moderation().syncAllOnlineVisibility();
                    out.put("permission", target.hasPermission(args[2]));
                }
                case "tppos" -> {
                    Player target = getServer().getPlayerExact(args[1]);
                    if (target == null) throw new IllegalArgumentException("online player required: " + args[1]);
                    World world = target.getWorld();
                    int bx = (int) Math.floor(Double.parseDouble(args[2]));
                    int bz = (int) Math.floor(Double.parseDouble(args[3]));
                    int groundY = world.getHighestBlockYAt(bx, bz);
                    Location loc = new Location(world, Double.parseDouble(args[2]), groundY + 1.0, Double.parseDouble(args[3]));
                    target.teleport(loc);
                    out.put("world", world.getName());
                    out.put("x", target.getLocation().getBlockX());
                    out.put("y", target.getLocation().getBlockY());
                    out.put("z", target.getLocation().getBlockZ());
                }
                case "seedname" -> {
                    String currentName = args[1];
                    String oldName = args[2];
                    UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + currentName).getBytes(StandardCharsets.UTF_8));
                    var db = allium.getDatabaseManager();
                    PlayerNameRepository nameRepo = new PlayerNameRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
                    nameRepo.migrate();
                    nameRepo.record(uuid, oldName, System.currentTimeMillis() - 120_000L);
                    out.put("uuid", uuid.toString());
                    out.put("seededOldName", oldName);
                }
                case "state" -> {
                    Player staff = getServer().getPlayerExact("InfoStaff");
                    Player norm = getServer().getPlayerExact("InfoNorm");
                    Player obs = getServer().getPlayerExact("InfoObs");
                    out.put("staffOnline", staff != null && staff.isOnline());
                    out.put("staffVanished", staff != null && moderation().isVanished(staff));
                    out.put("staffAfk", staff != null && info().isAfk(staff));
                    out.put("staffSleepingIgnored", staff != null && staff.isSleepingIgnored());
                    out.put("normOnline", norm != null && norm.isOnline());
                    out.put("normAfk", norm != null && info().isAfk(norm));
                    out.put("normSleepingIgnored", norm != null && norm.isSleepingIgnored());
                    out.put("obsOnline", obs != null && obs.isOnline());
                    out.put("obsAfk", obs != null && info().isAfk(obs));
                    out.put("obsSleepingIgnored", obs != null && obs.isSleepingIgnored());
                    out.put("worldTime", Bukkit.getWorlds().get(0).getTime());
                    out.put("nightSkipCount", nightSkipCount);
                    out.put("lastSkipReason", lastSkipReason);
                }
                case "sleepprep" -> {
                    Player norm = getServer().getPlayerExact(args[1]);
                    if (norm == null) throw new IllegalArgumentException("online player required: " + args[1]);
                    World world = norm.getWorld();
                    int groundY = world.getHighestBlockYAt(100, 131);
                    int bedY = groundY + 1;
                    Block foot = world.getBlockAt(100, bedY, 132);
                    Block head = world.getBlockAt(100, bedY, 131);
                    Bed footData = (Bed) Material.RED_BED.createBlockData();
                    footData.setFacing(BlockFace.NORTH);
                    footData.setPart(Bed.Part.FOOT);
                    Bed headData = (Bed) Material.RED_BED.createBlockData();
                    headData.setFacing(BlockFace.NORTH);
                    headData.setPart(Bed.Part.HEAD);
                    foot.setBlockData(footData, false);
                    head.setBlockData(headData, false);
                    norm.teleport(new Location(world, 101.5, bedY, 131.5));
                    world.setStorm(false);
                    world.setThundering(false);
                    world.setTime(14000L);
                    nightSkipCount = 0;
                    lastSkipReason = null;
                    boolean slept = norm.sleep(head.getLocation(), true);
                    out.put("slept", slept);
                    out.put("isSleeping", norm.isSleeping());
                    out.put("worldTime", world.getTime());
                }
                case "inv" -> {
                    Player target = getServer().getPlayerExact(args[1]);
                    if (target == null) throw new IllegalArgumentException("online player required: " + args[1]);
                    List<Object> items = new ArrayList<>();
                    ItemStack[] contents = target.getInventory().getContents();
                    for (int i = 0; i < contents.length; i++) {
                        ItemStack stack = contents[i];
                        if (stack != null && !stack.getType().isAir()) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("slot", i);
                            item.put("type", stack.getType().name());
                            item.put("amount", stack.getAmount());
                            ItemMeta meta = stack.getItemMeta();
                            if (meta != null && meta.hasDisplayName()) {
                                item.put("displayName", meta.getDisplayName());
                            }
                            items.add(item);
                        }
                    }
                    out.put("items", items);
                }
                case "clearinv" -> {
                    Player target = getServer().getPlayerExact(args[1]);
                    if (target == null) throw new IllegalArgumentException("online player required: " + args[1]);
                    target.getInventory().clear();
                    out.put("cleared", true);
                }
                case "fillinv" -> {
                    Player target = getServer().getPlayerExact(args[1]);
                    if (target == null) throw new IllegalArgumentException("online player required: " + args[1]);
                    target.getInventory().clear();
                    for (int i = 0; i < 36; i++) {
                        target.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
                    }
                    out.put("filledSlots", 36);
                }
                case "db" -> {
                    String playerName = args[1];
                    UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
                    var db = allium.getDatabaseManager();
                    PlayerInfoRepository infoRepo = new PlayerInfoRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
                    PlayerNameRepository nameRepo = new PlayerNameRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
                    Optional<PlayerInfoRecord> recOpt = infoRepo.findByUuid(offlineUuid);
                    if (recOpt.isPresent()) {
                        PlayerInfoRecord rec = recOpt.get();
                        out.put("uuid", rec.uuid().toString());
                        out.put("lastName", rec.lastName());
                        out.put("firstJoin", rec.firstJoin());
                        out.put("lastJoin", rec.lastJoin());
                        out.put("lastSeen", rec.lastSeen());
                    } else {
                        out.put("found", false);
                    }
                    out.put("history", nameRepo.nameHistory(offlineUuid).stream()
                            .map(PlayerNameRepository.NameHistoryEntry::name).toList());
                }
                default -> throw new IllegalArgumentException("unknown action: " + args[0]);
            }
            getLogger().info("S18G-DONE " + text + " => " + json(out));
        } catch (Throwable e) {
            getLogger().info("S18G-FAIL " + text + " => " + e);
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTimeSkip(TimeSkipEvent event) {
        try {
            Object reason = event.getClass().getMethod("getSkipReason").invoke(event);
            String reasonName = String.valueOf(reason);
            if ("NIGHT_SKIP".equals(reasonName)) {
                nightSkipCount++;
                lastSkipReason = reasonName;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        permissions.remove(e.getPlayer().getUniqueId());
    }

    private static String json(Object v) {
        if (v == null) return "null";
        if (v instanceof Map<?, ?> m) {
            return "{" + String.join(",", m.entrySet().stream()
                    .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (v instanceof Collection<?> c) {
            return "[" + String.join(",", c.stream().map(InfoGate::json).toList()) + "]";
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
