package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.economy.Money;
import codes.castled.allium.module.RestoreModule;
import codes.castled.allium.sign.SignIdentity;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff tooling gate helper (test only). It manages fixtures (permissions, items, a repair sign's
 * identity, faults) and reports live server state; every feature under test is driven by real
 * protocol clients.
 */
public final class StaffGate extends JavaPlugin implements org.bukkit.event.Listener {

    private PluginStart allium;
    private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();
    /** Nodes applied at login (before join listeners run), as a permissions plugin would. */
    private final Map<String, Map<String, Boolean>> sticky = new HashMap<>();

    @Override
    public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || getServer().getPort() < 25860 || getServer().getPort() > 25862) {
            throw new IllegalStateException("TEST ONLY: 127.0.0.1:25860-25862 required");
        }
        allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
        try {
            Files.createDirectories(getDataFolder().toPath());
            try {
                Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),
                        json(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8);
            } catch (ReflectiveOperationException e) {
                getLogger().info("STG legacy protocol client required");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("STG-GATE READY " + getServer().getBukkitVersion());
    }

    /** A rejoining player is a new Player object: its old attachment no longer applies. */
    @org.bukkit.event.EventHandler
    public void quit(org.bukkit.event.player.PlayerQuitEvent event) {
        permissions.remove(event.getPlayer().getUniqueId());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void login(org.bukkit.event.player.PlayerLoginEvent event) {
        Map<String, Boolean> nodes = sticky.get(event.getPlayer().getName());
        if (nodes == null || nodes.isEmpty()) return;
        PermissionAttachment p = event.getPlayer().addAttachment(this);
        nodes.forEach(p::setPermission);
        permissions.put(event.getPlayer().getUniqueId(), p);
    }

    private Player online(String name) {
        Player p = getServer().getPlayerExact(name);
        if (p == null) throw new IllegalArgumentException("not online: " + name);
        return p;
    }

    private static Map<String, Object> describe(ItemStack s) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null || s.getType().isAir()) return null;
        out.put("type", s.getType().name());
        out.put("amount", s.getAmount());
        ItemMeta meta = s.getItemMeta();
        if (meta instanceof Damageable d) out.put("damage", d.getDamage());
        if (meta != null && meta.hasCustomModelData()) out.put("cmd", meta.getCustomModelData());
        if (meta != null && meta.hasDisplayName()) out.put("name", meta.getDisplayName());
        if (meta != null && meta.hasLore()) out.put("lore", meta.getLore());
        if (meta != null) {
            List<String> keys = new ArrayList<>();
            for (NamespacedKey k : meta.getPersistentDataContainer().getKeys()) keys.add(k.toString());
            if (!keys.isEmpty()) out.put("pdc", keys);
        }
        return out;
    }

    private static Map<String, Object> slots(Inventory inv, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            Map<String, Object> d = describe(inv.getItem(i));
            if (d != null) out.put(Integer.toString(i), d);
        }
        return out;
    }

    private Object query(String sql, Object... args) throws Exception {
        try (Connection c = allium.getDatabaseManager().connectionSource().connection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                int n = r.getMetaData().getColumnCount();
                while (r.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        Object v = r.getObject(i);
                        row.put(r.getMetaData().getColumnLabel(i), v instanceof Number || v instanceof Boolean || v == null ? v : v.toString());
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
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
                    Player target = online(args[1]);
                    PermissionAttachment p = permissions.computeIfAbsent(target.getUniqueId(), id -> target.addAttachment(this));
                    p.setPermission(args[2], Boolean.parseBoolean(args[3]));
                    target.updateCommands();
                    out.put("permission", target.hasPermission(args[2]));
                }
                case "addperm" -> {
                    if (getServer().getPluginManager().getPermission(args[1]) == null) {
                        getServer().getPluginManager().addPermission(new org.bukkit.permissions.Permission(args[1]));
                    }
                    out.put("registered", getServer().getPluginManager().getPermission(args[1]) != null);
                }
                case "sticky" -> {
                    sticky.computeIfAbsent(args[1], k -> new HashMap<>()).put(args[2], Boolean.parseBoolean(args[3]));
                    out.put("sticky", sticky.get(args[1]).size());
                }
                case "inv" -> {
                    Player p = online(args[1]);
                    out.put("gamemode", p.getGameMode().name());
                    out.put("dead", p.isDead());
                    out.put("slots", slots(p.getInventory(), 41));
                    out.put("ender", slots(p.getEnderChest(), 27));
                    out.put("cursor", describe(p.getItemOnCursor()));
                    out.put("level", p.getLevel());
                    out.put("held", p.getInventory().getHeldItemSlot());
                    Map<String, Object> pdc = new LinkedHashMap<>();
                    for (NamespacedKey k : p.getPersistentDataContainer().getKeys()) {
                        String v = p.getPersistentDataContainer().has(k, PersistentDataType.STRING)
                                ? p.getPersistentDataContainer().get(k, PersistentDataType.STRING) : "<binary>";
                        pdc.put(k.toString(), v);
                    }
                    out.put("pdc", pdc);
                    Inventory top = p.getOpenInventory().getTopInventory();
                    out.put("topType", top.getType().name());
                    out.put("topSize", top.getSize());
                    out.put("top", top.getType().name().equals("CRAFTING") ? Map.of() : slots(top, top.getSize()));
                }
                case "give" -> {
                    Player p = online(args[1]);
                    ItemStack stack = new ItemStack(Material.valueOf(args[3]), Integer.parseInt(args[4]));
                    if (args.length > 5 && stack.getItemMeta() instanceof Damageable d) {
                        d.setDamage(Integer.parseInt(args[5]));
                        stack.setItemMeta(d);
                    }
                    p.getInventory().setItem(Integer.parseInt(args[2]), stack);
                    out.put("slot", describe(p.getInventory().getItem(Integer.parseInt(args[2]))));
                }
                case "fill" -> {
                    Player p = online(args[1]);
                    for (int i = Integer.parseInt(args[2]); i <= Integer.parseInt(args[3]); i++) p.getInventory().setItem(i, new ItemStack(Material.valueOf(args[4]), 64));
                    out.put("filled", true);
                }
                case "clear" -> {
                    Player p = online(args[1]);
                    p.getInventory().clear();
                    p.getEnderChest().clear();
                    p.setItemOnCursor(null);
                    out.put("cleared", true);
                }
                case "held" -> {
                    Player p = online(args[1]);
                    p.getInventory().setHeldItemSlot(Integer.parseInt(args[2]));
                    out.put("held", p.getInventory().getHeldItemSlot());
                }
                case "kill" -> {
                    Player p = online(args[1]);
                    p.getWorld().setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, false);
                    p.getWorld().setGameRule(GameRule.KEEP_INVENTORY, true);
                    p.setHealth(0.0);
                    out.put("dead", p.isDead());
                }
                case "respawn" -> {
                    Player p = online(args[1]);
                    p.spigot().respawn();
                    out.put("dead", p.isDead());
                }
                case "fault" -> {
                    FaultInjection.arm(args[1], Integer.parseInt(args[2]));
                    out.put("armed", FaultInjection.armed(args[1]));
                }
                case "snapshot" -> {
                    Player p = online(args[1]);
                    RestoreModule module = (RestoreModule) allium.getModuleManager().getModule("restore");
                    module.restore().snapshot(p, args[2], "StaffGate");
                    out.put("taken", true);
                }
                case "snapcount" -> {
                    UUID id = getServer().getOfflinePlayer(args[1]).getUniqueId();
                    out.put("snapshots", query("SELECT COUNT(*) AS n FROM allium_inventory_snapshots WHERE player = ?", id.toString()));
                    out.put("pending", query("SELECT COUNT(*) AS n FROM allium_restore_pending WHERE player = ?", id.toString()));
                    out.put("newest", query("SELECT reason, created_by FROM allium_inventory_snapshots WHERE player = ? ORDER BY created_at DESC LIMIT 3", id.toString()));
                }
                case "mailbox" -> {
                    UUID id = getServer().getOfflinePlayer(args[1]).getUniqueId();
                    out.put("rows", query("SELECT kind, state, box_number AS number, body FROM allium_mail WHERE recipient = ? ORDER BY kind, box_number", id.toString()));
                }
                case "econ" -> {
                    UUID id = getServer().getOfflinePlayer(args[1]).getUniqueId();
                    out.put("account", query("SELECT balance_minor, reserved_minor FROM allium_economy_accounts WHERE account_uuid = ?", id.toString()));
                    out.put("repairs", query("SELECT workflow_id, state, amount_minor FROM allium_economy_reservations WHERE workflow_kind = 'REPAIR' AND account_uuid = ? ORDER BY created_at", id.toString()));
                }
                case "alts" -> {
                    UUID id = getServer().getOfflinePlayer(args[1]).getUniqueId();
                    out.put("ips", query("SELECT ip, first_seen FROM allium_ip_history WHERE uuid = ?", id.toString()));
                    out.put("exempt", query("SELECT actor, reason FROM allium_alt_exemptions WHERE uuid = ?", id.toString()));
                }
                case "sign" -> {
                    // x z mode priceMinor: a standing repair sign on the ground, whose identity a real client then uses.
                    var world = getServer().getWorlds().get(0);
                    int x = Integer.parseInt(args[1]), z = Integer.parseInt(args[2]);
                    int y = world.getHighestBlockYAt(x, z) + 1;
                    Block block = world.getBlockAt(x, y, z);
                    block.setType(Material.OAK_SIGN);
                    Sign sign = (Sign) block.getState();
                    String identity = new SignIdentity(UUID.randomUUID(), SignIdentity.Type.REPAIR, args[3], false,
                            new Money(Long.parseLong(args[4]))).encode();
                    sign.getPersistentDataContainer().set(new NamespacedKey(allium, "sign_front"), PersistentDataType.STRING, identity);
                    sign.getPersistentDataContainer().set(new NamespacedKey(allium, "sign_back"), PersistentDataType.STRING, identity);
                    sign.setLine(0, "[Repair]");
                    sign.setLine(1, args[3].equals("all") ? "All" : "Hand");
                    sign.update(true, false);
                    out.put("y", y);
                }
                case "tp" -> {
                    Player p = online(args[1]);
                    // An eighth argument names the world; the first world otherwise.
                    var world = args.length > 7 ? getServer().getWorld(args[7]) : getServer().getWorlds().get(0);
                    p.teleport(new Location(world, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                            Float.parseFloat(args[5]), Float.parseFloat(args[6])));
                    out.put("at", p.getLocation().toVector().toString());
                }
                case "scale-spawn" -> {
                    var location = online(args[1]).getLocation().add(2, 0, 0);
                    var mob = location.getWorld().spawn(location, org.bukkit.entity.Cow.class);
                    mob.setAI(false);
                    out.put("id", mob.getUniqueId().toString());
                }
                case "scale-select" -> {
                    var mob = getServer().getEntity(UUID.fromString(args[2]));
                    getServer().getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEntityEvent(
                            online(args[1]), mob, org.bukkit.inventory.EquipmentSlot.HAND));
                }
                case "scale-value" -> {
                    var target = args[1].length() == 36
                            ? (org.bukkit.entity.LivingEntity) getServer().getEntity(UUID.fromString(args[1]))
                            : online(args[1]);
                    var registry = org.bukkit.Registry.ATTRIBUTE;
                    var scale = registry.get(org.bukkit.NamespacedKey.minecraft("scale"));
                    if (scale == null) scale = registry.get(org.bukkit.NamespacedKey.minecraft("generic.scale"));
                    var instance = scale == null ? null : target.getAttribute(scale);
                    out.put("scale", instance == null ? null : instance.getBaseValue());
                }
                case "creativemark" -> {
                    // "creativemark <x> <y> <z>": whether Allium marked the block as placed in creative.
                    var world = getServer().getWorlds().get(0);
                    int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]), z = Integer.parseInt(args[3]);
                    int[] marks = world.getBlockAt(x, y, z).getChunk().getPersistentDataContainer()
                            .get(new NamespacedKey("allium", "creative_blocks"), PersistentDataType.INTEGER_ARRAY);
                    int packed = (y << 8) | ((z & 15) << 4) | (x & 15);
                    out.put("marked", marks != null && java.util.Arrays.binarySearch(marks, packed) >= 0);
                }
                case "items" -> {
                    // "items <x> <y> <z> [remove]": dropped items within two blocks.
                    var world = getServer().getWorlds().get(0);
                    Location at = new Location(world, Integer.parseInt(args[1]) + .5, Integer.parseInt(args[2]) + .5, Integer.parseInt(args[3]) + .5);
                    var found = world.getNearbyEntities(at, 2, 2, 2).stream().filter(e -> e instanceof org.bukkit.entity.Item).toList();
                    out.put("count", found.size());
                    out.put("types", found.stream().map(e -> ((org.bukkit.entity.Item) e).getItemStack().getType().name()).toList());
                    if (args.length > 4) found.forEach(org.bukkit.entity.Entity::remove);
                }
                case "effects" -> {
                    // "effects <name> [add <TYPE>]"
                    Player p = online(args[1]);
                    if (args.length > 3) p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.getByName(args[3]), 20 * 120, 0));
                    out.put("effects", p.getActivePotionEffects().stream().map(e -> e.getType().getName()).toList());
                }
                case "armor" -> {
                    Player p = online(args[1]);
                    List<String> worn = new ArrayList<>();
                    for (ItemStack piece : p.getInventory().getArmorContents()) {
                        worn.add(piece == null ? "AIR" : piece.getType().name() + (piece.getItemMeta() != null && piece.getItemMeta().hasDisplayName()
                                ? "|" + org.bukkit.ChatColor.stripColor(piece.getItemMeta().getDisplayName()) : ""));
                    }
                    out.put("armor", worn);
                }
                case "setblock" -> {
                    var world = getServer().getWorlds().get(0);
                    Block b = world.getBlockAt(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                    b.setType(Material.valueOf(args[4]));
                    out.put("type", b.getType().name());
                }
                case "blockat" -> {
                    var world = getServer().getWorlds().get(0);
                    out.put("type", world.getBlockAt(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3])).getType().name());
                }
                case "firearrow" -> {
                    // "firearrow <shooter> <x> <y> <z>": a burning arrow shot by the player, falling onto that block.
                    Player shooter = online(args[1]);
                    var world = getServer().getWorlds().get(0);
                    Location from = new Location(world, Integer.parseInt(args[2]) + .5, Integer.parseInt(args[3]) + 3, Integer.parseInt(args[4]) + .5);
                    var arrow = world.spawnArrow(from, new org.bukkit.util.Vector(0, -1, 0), 1.5f, 0f);
                    arrow.setShooter(shooter);
                    arrow.setFireTicks(200);
                    out.put("arrow", arrow.getUniqueId().toString());
                }
                case "entities" -> {
                    // "entities <TYPE> [player]": living entities of a type in the first world, and the farthest one from the player.
                    var world = getServer().getWorlds().get(0);
                    var type = org.bukkit.entity.EntityType.valueOf(args[1]);
                    var found = world.getEntities().stream().filter(e -> e.getType() == type && e.isValid()).toList();
                    out.put("count", found.size());
                    if (args.length > 2) {
                        Location at = online(args[2]).getLocation();
                        out.put("farthest", found.stream().filter(e -> e.getWorld() == at.getWorld())
                                .mapToDouble(e -> e.getLocation().distance(at)).max().orElse(-1));
                    }
                    if (args.length > 3 && args[3].equals("remove")) found.forEach(org.bukkit.entity.Entity::remove);
                }
                case "world" -> {
                    // "world <name>": creates (or loads) a small flat world.
                    var world = getServer().getWorld(args[1]);
                    if (world == null) world = getServer().createWorld(new org.bukkit.WorldCreator(args[1]).type(org.bukkit.WorldType.FLAT)
                            .generatorSettings("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},"
                                    + "{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\",\"structure_overrides\":[]}")
                            .generateStructures(false));
                    out.put("world", world.getName());
                    out.put("permission", getServer().getPluginManager().getPermission("allium.worlds." + world.getName()) != null);
                }
                case "compass" -> {
                    Location c = online(args[1]).getCompassTarget();
                    out.put("x", c.getX());
                    out.put("z", c.getZ());
                }
                case "flight" -> {
                    // "flight <name> fly" lifts an allowed player into flight, as a double jump would.
                    Player p = online(args[1]);
                    if (args.length > 2 && args[2].equals("fly") && p.getAllowFlight()) p.setFlying(true);
                    out.put("allow", p.getAllowFlight());
                    out.put("flying", p.isFlying());
                }
                case "bedspawn" -> {
                    // Forced, so no bed block is needed; "none" clears it.
                    Player p = online(args[1]);
                    if (args[2].equals("none")) p.setBedSpawnLocation(null, true);
                    else p.setBedSpawnLocation(new Location(p.getWorld(), Double.parseDouble(args[2]), Double.parseDouble(args[3]),
                            Double.parseDouble(args[4])), true);
                    out.put("set", !args[2].equals("none"));
                }
                case "xp" -> {
                    Player p = online(args[1]);
                    out.put("level", p.getLevel());
                    out.put("exp", p.getExp());
                }
                case "where" -> {
                    Player p = online(args[1]);
                    Location l = p.getLocation();
                    out.put("x", l.getX());
                    out.put("y", l.getY());
                    out.put("z", l.getZ());
                    out.put("yaw", l.getYaw());
                    out.put("pitch", l.getPitch());
                    out.put("world", l.getWorld().getName());
                }
                case "potion" -> {
                    // Base potion, custom effects and every name field, read reflectively so one
                    // fixture serves Spigot 1.20.1 and Paper 26.3.
                    Player p = online(args[1]);
                    ItemStack stack = p.getInventory().getItem(Integer.parseInt(args[2]));
                    if (stack == null || !(stack.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta)) throw new IllegalArgumentException("no potion in slot");
                    out.put("type", stack.getType().name());
                    out.put("amount", stack.getAmount());
                    try {
                        Object base = meta.getClass().getMethod("getBasePotionType").invoke(meta);
                        out.put("base", base == null ? null : ((Enum<?>) base).name());
                    } catch (NoSuchMethodException older) {
                        @SuppressWarnings("deprecation") var data = meta.getBasePotionData();
                        out.put("base", data.getType().name() + (data.isExtended() ? "+long" : "") + (data.isUpgraded() ? "+strong" : ""));
                    }
                    List<String> effects = new ArrayList<>();
                    for (var e : meta.getCustomEffects()) effects.add(e.getType().getKey().getKey() + ":" + e.getAmplifier() + ":" + e.getDuration());
                    out.put("custom", effects);
                    out.put("displayName", meta.hasDisplayName() ? meta.getDisplayName() : null);
                    try {
                        out.put("customPotionName", meta.getClass().getMethod("hasCustomPotionName").invoke(meta).equals(Boolean.TRUE)
                                ? meta.getClass().getMethod("getCustomPotionName").invoke(meta) : null);
                    } catch (NoSuchMethodException older) {
                        out.put("customPotionName", "<unsupported>");
                    }
                    for (Class<?> t = meta.getClass(); t != null; t = t.getSuperclass()) {
                        try {
                            var f = t.getDeclaredField("displayName");
                            if (f.getType() == String.class) { f.setAccessible(true); out.put("displayNameJson", f.get(meta)); }
                            break;
                        } catch (NoSuchFieldException next) { /* superclass */ }
                    }
                }
                case "chunk" -> {
                    // Generates a chunk, saves it and unloads it: a destination that exists only on disk.
                    var world = getServer().getWorlds().get(0);
                    int x = Integer.parseInt(args[1]), z = Integer.parseInt(args[2]);
                    if (!world.isChunkGenerated(x, z)) world.getChunkAt(x, z);
                    // A freshly generated chunk keeps a load ticket for a moment: request the unload
                    // and let the harness poll until the server really unloaded it.
                    if (world.isChunkLoaded(x, z)) out.put("requested", world.unloadChunkRequest(x, z));
                    out.put("loaded", world.isChunkLoaded(x, z));
                    out.put("generated", world.isChunkGenerated(x, z));
                }
                case "travel" -> {
                    // Read-only view of an owner's imported destinations and the import ledger.
                    out.put("homes", query("SELECT name, world_name, x, y, z, yaw, pitch FROM allium_travel_homes WHERE owner = ? ORDER BY name", args[1]));
                    out.put("logout", query("SELECT world_name, x, y, z FROM allium_teleport_locations WHERE owner = ? AND slot = 'logout'", args[1]));
                    out.put("warps", query("SELECT name, world_name, x, y, z FROM allium_travel_warps ORDER BY name"));
                    out.put("ledger", query("SELECT domain, COUNT(*) AS n FROM allium_import_records GROUP BY domain ORDER BY domain"));
                    out.put("runs", query("SELECT run_id, result FROM allium_import_runs ORDER BY run_id"));
                }
                case "save" -> {
                    Player p = online(args[1]);
                    p.saveData();
                    out.put("saved", true);
                }
                case "blanksign" -> {
                    var world = getServer().getWorlds().get(0);
                    Player editor = online(args[1]);
                    int x = Integer.parseInt(args[2]), z = Integer.parseInt(args[3]);
                    int y = world.getHighestBlockYAt(x, z) + 1;
                    Block block = world.getBlockAt(x, y, z);
                    block.setType(Material.OAK_SIGN);
                    Sign sign = (Sign) block.getState();
                    editor.openSign(sign, org.bukkit.block.sign.Side.FRONT);
                    out.put("x", x);
                    out.put("y", y);
                    out.put("z", z);
                }
                case "opensign" -> {
                    var world = getServer().getWorlds().get(0);
                    Player editor = online(args[1]);
                    Block block = world.getBlockAt(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                    editor.openSign((Sign) block.getState(), org.bukkit.block.sign.Side.FRONT);
                    out.put("opened", true);
                }
                case "signstate" -> {
                    var world = getServer().getWorlds().get(0);
                    Block block = world.getBlockAt(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                    Sign sign = (Sign) block.getState();
                    out.put("lines", java.util.Arrays.asList(sign.getLines()));
                    out.put("frontPdc", sign.getPersistentDataContainer().get(new NamespacedKey(allium, "sign_front"), PersistentDataType.STRING));
                }
                case "health" -> {
                    Player p = online(args[1]);
                    if (args.length > 2) p.setHealth(Double.parseDouble(args[2]));
                    out.put("health", p.getHealth());
                }
                default -> throw new IllegalArgumentException("unknown action: " + args[0]);
            }
            getLogger().info("STG-DONE " + text + " => " + json(out));
        } catch (Throwable e) {
            getLogger().info("STG-FAIL " + text + " => " + e);
        }
        return true;
    }

    private static String json(Object v) {
        if (v == null) return "null";
        if (v instanceof Map<?, ?> m) {
            return "{" + String.join(",", m.entrySet().stream()
                    .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (v instanceof Collection<?> c) {
            return "[" + String.join(",", c.stream().map(StaffGate::json).toList()) + "]";
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
