package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.moderation.ModerationAuditRecord;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.moderation.MuteRecord;
import codes.castled.allium.module.ModerationModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Only prepares fixtures and observes server/Bukkit state; all tested moderation actions run over real clients/console. */
public final class ModGate extends JavaPlugin implements Listener {

    private PluginStart allium;
    private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();

    @Override
    public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || (getServer().getPort() < 25770 || getServer().getPort() > 25771)) {
            throw new IllegalStateException("TEST ONLY: 127.0.0.1:25770-25771 required");
        }
        allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
        try {
            Files.createDirectories(getDataFolder().toPath());
            try {
                Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),
                        json(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8);
            } catch (ReflectiveOperationException e) {
                getLogger().info("S17G legacy protocol client required");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("S17G-GATE READY " + getServer().getBukkitVersion());
    }

    private ModerationService moderation() {
        ModerationModule mod = (ModerationModule) allium.getModuleManager().getModule("moderation");
        return mod.moderation();
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
                case "give" -> {
                    Player target = getServer().getPlayerExact(args[1]);
                    if (target == null) throw new IllegalArgumentException("online player required: " + args[1]);
                    Material mat = Material.valueOf(args[2]);
                    int amount = Integer.parseInt(args[3]);
                    target.getInventory().setItemInMainHand(new ItemStack(mat, amount));
                    out.put("hand", target.getInventory().getItemInMainHand().getType().name());
                }
                case "state" -> {
                    Player staff = getServer().getPlayerExact("ModStaff");
                    Player norm = getServer().getPlayerExact("ModNorm");
                    Player obs = getServer().getPlayerExact("ModObs");
                    out.put("staffOnline", staff != null && staff.isOnline());
                    out.put("staffVanished", staff != null && moderation().isVanished(staff));
                    out.put("staffMetadata", staff != null && staff.hasMetadata("vanished"));
                    out.put("staffSleepingIgnored", staff != null && staff.isSleepingIgnored());
                    out.put("normCanSeeStaff", norm != null && staff != null && norm.canSee(staff));
                    out.put("obsCanSeeStaff", obs != null && staff != null && obs.canSee(staff));
                    out.put("normMuted", norm != null && moderation().isMuted(norm.getUniqueId()));
                }
                case "effects" -> {
                    Player staff = getServer().getPlayerExact(args[1]);
                    if (staff == null) throw new IllegalArgumentException("online player required: " + args[1]);
                    World world = staff.getWorld();
                    int groundY = world.getHighestBlockYAt(100, 100);
                    Location base = new Location(world, 100.5, groundY + 1.0, 100.5);
                    staff.teleport(base);

                    // 1. Mob targeting: spawn Zombie, set target to staff, then test EntityTargetEvent & vanish target clearing
                    Zombie zombie = (Zombie) world.spawnEntity(base.clone().add(2, 0, 0), EntityType.ZOMBIE);
                    EntityTargetEvent targetEvent = new EntityTargetEvent(zombie, staff, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
                    Bukkit.getPluginManager().callEvent(targetEvent);
                    out.put("mobTargetCancelled", targetEvent.isCancelled());
                    out.put("mobTargetAfterEvent", targetEvent.getTarget() == null ? null : targetEvent.getTarget().getName());
                    zombie.remove();

                    // 2. Item pickup: drop emerald and fire EntityPickupItemEvent for vanished staff
                    Item dropped = world.dropItem(base, new ItemStack(Material.EMERALD, 1));
                    EntityPickupItemEvent pickupEvent = new EntityPickupItemEvent(staff, dropped, 0);
                    Bukkit.getPluginManager().callEvent(pickupEvent);
                    out.put("itemPickupCancelled", pickupEvent.isCancelled());
                    dropped.remove();

                    // 3. Pressure plate (Action.PHYSICAL): place STONE_PRESSURE_PLATE and fire PlayerInteractEvent
                    Block plateBlock = world.getBlockAt(102, groundY + 1, 100);
                    plateBlock.setType(Material.STONE_PRESSURE_PLATE);
                    PlayerInteractEvent plateEvent = new PlayerInteractEvent(staff, Action.PHYSICAL, null, plateBlock, null);
                    Bukkit.getPluginManager().callEvent(plateEvent);
                    out.put("pressurePlateCancelled", plateEvent.isCancelled());
                    plateBlock.setType(Material.AIR);

                    // 4. Silent Chest open (Action.RIGHT_CLICK_BLOCK on CHEST)
                    Block chestBlock = world.getBlockAt(104, groundY + 1, 100);
                    chestBlock.setType(Material.CHEST);
                    Chest chestState = (Chest) chestBlock.getState();
                    chestState.getBlockInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
                    PlayerInteractEvent chestEvent = new PlayerInteractEvent(staff, Action.RIGHT_CLICK_BLOCK, null, chestBlock, null);
                    Bukkit.getPluginManager().callEvent(chestEvent);
                    InventoryView view = staff.getOpenInventory();
                    boolean silentHolder = view != null
                            && view.getTopInventory() != null
                            && view.getTopInventory().getHolder() instanceof ModerationService.SilentContainerHolder;
                    int mirrorDiamonds = (view != null && view.getTopInventory() != null && view.getTopInventory().getItem(0) != null)
                            ? view.getTopInventory().getItem(0).getAmount() : 0;
                    out.put("chestInteractCancelled", chestEvent.isCancelled());
                    out.put("silentContainerOpened", silentHolder);
                    out.put("silentContainerDiamonds", mirrorDiamonds);
                    staff.closeInventory();
                    chestBlock.setType(Material.AIR);
                }
                case "db" -> {
                    var repo = moderation().repository();
                    out.put("vanishedUuids", repo.loadVanishedPlayers().stream().map(UUID::toString).toList());
                    List<Object> mutes = new ArrayList<>();
                    for (MuteRecord m : repo.loadActiveMutes()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("playerUuid", m.playerUuid().toString());
                        row.put("playerName", m.playerName());
                        row.put("actorName", m.actorName());
                        row.put("reason", m.reason());
                        row.put("permanent", m.isPermanent());
                        mutes.add(row);
                    }
                    out.put("activeMutes", mutes);
                    List<Object> audits = new ArrayList<>();
                    for (ModerationAuditRecord a : repo.loadRecentAudit(25)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("action", a.action());
                        row.put("actorName", a.actorName());
                        row.put("targetName", a.targetName());
                        row.put("detail", a.detail());
                        audits.add(row);
                    }
                    out.put("audits", audits);
                }
                default -> throw new IllegalArgumentException("unknown action: " + args[0]);
            }
            getLogger().info("S17G-DONE " + text + " => " + json(out));
        } catch (Throwable e) {
            getLogger().info("S17G-FAIL " + text + " => " + e);
        }
        return true;
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
            return "[" + String.join(",", c.stream().map(ModGate::json).toList()) + "]";
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
