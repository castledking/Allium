package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.module.ModerationModule;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S25 parity utilities live-gate helper. Only manages fixtures and inspects Bukkit state;
 * all tested utility commands execute via real protocol clients and console.
 */
public final class UtilitiesGate extends JavaPlugin implements Listener {

    private PluginStart allium;
    private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();
    private final Map<UUID, Inventory> trackedDisposals = new HashMap<>();

    @Override
    public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || (getServer().getPort() < 25820 || getServer().getPort() > 25822)) {
            throw new IllegalStateException("TEST ONLY: 127.0.0.1:25820-25822 required");
        }
        allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
        try {
            Files.createDirectories(getDataFolder().toPath());
            try {
                Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),
                        json(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8);
            } catch (ReflectiveOperationException e) {
                getLogger().info("S25G legacy protocol client required");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("S25G-GATE READY " + getServer().getBukkitVersion());
    }

    private ModerationService moderation() {
        ModerationModule mod = (ModerationModule) allium.getModuleManager().getModule("moderation");
        return mod == null ? null : mod.moderation();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            return true;
        }
        String text = String.join(" ", args);
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            switch (args[0]) {
                case "online" -> out.put("players", getServer().getOnlinePlayers().stream().map(Player::getName).toList());
                case "perm" -> {
                    Player target = requireOnline(args[1]);
                    PermissionAttachment p = permissions.computeIfAbsent(target.getUniqueId(), id -> target.addAttachment(this));
                    p.setPermission(args[2], Boolean.parseBoolean(args[3]));
                    target.updateCommands();
                    ModerationService mod = moderation();
                    if (mod != null) {
                        mod.syncAllOnlineVisibility();
                    }
                    out.put("permission", target.hasPermission(args[2]));
                }
                case "seedrepair" -> {
                    Player target = requireOnline(args[1]);
                    PlayerInventory inv = target.getInventory();
                    inv.clear();
                    ItemStack pick = new ItemStack(Material.DIAMOND_PICKAXE, 1);
                    ItemMeta meta = pick.getItemMeta();
                    meta.setDisplayName("Custom Pick");
                    meta.setLore(List.of("Keeper Line"));
                    meta.addEnchant(Enchantment.DIG_SPEED, 2, true);
                    meta.getPersistentDataContainer().set(new NamespacedKey(this, "identity"), PersistentDataType.STRING, "keep-me");
                    ((Damageable) meta).setDamage(120);
                    pick.setItemMeta(meta);

                    ItemStack sword = new ItemStack(Material.IRON_SWORD, 1);
                    ItemMeta swordMeta = sword.getItemMeta();
                    ((Damageable) swordMeta).setDamage(40);
                    sword.setItemMeta(swordMeta);

                    ItemStack intact = new ItemStack(Material.GOLDEN_PICKAXE, 1);

                    inv.setHeldItemSlot(0);
                    inv.setItem(0, pick);
                    inv.setItem(1, sword);
                    inv.setItem(2, intact);
                    out.put("seeded", "repair");
                }
                case "seedstack" -> {
                    Player target = requireOnline(args[1]);
                    PlayerInventory inv = target.getInventory();
                    inv.clear();
                    inv.setItem(0, new ItemStack(Material.DIRT, 10));
                    inv.setItem(1, new ItemStack(Material.STONE, 5));
                    inv.setItem(2, new ItemStack(Material.GOLDEN_APPLE, 1));
                    inv.setHeldItemSlot(2);
                    inv.setHelmet(new ItemStack(Material.DIAMOND_HELMET, 1));
                    out.put("seeded", "stack");
                }
                case "seedsword" -> {
                    Player target = requireOnline(args[1]);
                    PlayerInventory inv = target.getInventory();
                    inv.clear();
                    ItemStack sword = new ItemStack(Material.DIAMOND_SWORD, 1);
                    ItemMeta meta = sword.getItemMeta();
                    ((Damageable) meta).setDamage(5);
                    sword.setItemMeta(meta);
                    inv.setItem(0, sword);
                    inv.setHeldItemSlot(0);
                    out.put("seeded", "sword");
                }
                case "place_disposal" -> {
                    Player target = requireOnline(args[1]);
                    Inventory top = target.getOpenInventory().getTopInventory();
                    top.setItem(0, new ItemStack(Material.COBBLESTONE, 4));
                    trackedDisposals.put(target.getUniqueId(), top);
                    out.put("topSize", top.getSize());
                    out.put("topZero", summarize(top.getItem(0)));
                }
                case "disposal_cleared" -> {
                    Player target = requireOnline(args[1]);
                    Inventory top = trackedDisposals.get(target.getUniqueId());
                    boolean empty = top != null && (top.getItem(0) == null || top.getItem(0).getType().isAir());
                    out.put("cleared", empty);
                    out.put("slot0", top == null ? "NONE" : summarize(top.getItem(0)));
                }
                case "state" -> {
                    Player target = requireOnline(args[1]);
                    PlayerInventory inv = target.getInventory();
                    World world = target.getWorld();
                    out.put("walkSpeed", target.getWalkSpeed());
                    out.put("flySpeed", target.getFlySpeed());
                    out.put("fireTicks", target.getFireTicks());
                    PotionEffect nv = target.getPotionEffect(PotionEffectType.NIGHT_VISION);
                    out.put("hasNightVision", nv != null);
                    out.put("nightVisionDuration", nv == null ? 0 : nv.getDuration());

                    ItemStack held = inv.getItemInMainHand();
                    out.put("heldType", held == null ? "AIR" : held.getType().name());
                    out.put("heldAmount", held == null ? 0 : held.getAmount());
                    if (held != null && held.getItemMeta() != null) {
                        ItemMeta m = held.getItemMeta();
                        out.put("heldDisplayName", m.hasDisplayName() ? m.getDisplayName() : "");
                        out.put("heldLore", m.hasLore() ? String.join("|", m.getLore()) : "");
                        out.put("heldDamage", m instanceof Damageable d ? d.getDamage() : -1);
                        out.put("heldPdc", m.getPersistentDataContainer().get(new NamespacedKey(this, "identity"), PersistentDataType.STRING));
                        Map<String, Integer> ench = new LinkedHashMap<>();
                        m.getEnchants().forEach((k, v) -> ench.put(k.getKey().getKey(), v));
                        out.put("heldEnchants", ench);
                    }
                    ItemStack slot1 = inv.getItem(1);
                    out.put("slot1Damage", slot1 != null && slot1.getItemMeta() instanceof Damageable d ? d.getDamage() : -1);
                    ItemStack helmet = inv.getHelmet();
                    out.put("helmetType", helmet == null ? "AIR" : helmet.getType().name());
                    out.put("helmetAmount", helmet == null ? 0 : helmet.getAmount());

                    int dirt = 0, stone = 0, heads = 0;
                    String skullOwner = "";
                    for (ItemStack s : inv.getContents()) {
                        if (s == null || s.getType().isAir()) continue;
                        if (s.getType() == Material.DIRT) dirt += s.getAmount();
                        if (s.getType() == Material.STONE) stone += s.getAmount();
                        if (s.getType() == Material.PLAYER_HEAD) {
                            heads += s.getAmount();
                            if (s.getItemMeta() instanceof SkullMeta sm && sm.getOwnerProfile() != null) {
                                skullOwner = String.valueOf(sm.getOwnerProfile().getName());
                            }
                        }
                    }
                    out.put("dirtCount", dirt);
                    out.put("stoneCount", stone);
                    out.put("headCount", heads);
                    out.put("skullOwner", skullOwner);

                    out.put("world", world.getName());
                    out.put("worldTime", world.getTime());
                    out.put("daylightCycle", Boolean.TRUE.equals(world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE)));
                    out.put("storm", world.hasStorm());
                    out.put("thundering", world.isThundering());
                }
                default -> throw new IllegalArgumentException("Unknown s25g subcommand: " + text);
            }
            getLogger().info("S25G-DONE " + text + " => " + json(out));
        } catch (Throwable error) {
            getLogger().warning("S25G-FAIL " + text + " => " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        return true;
    }

    private Player requireOnline(String name) {
        Player target = getServer().getPlayerExact(name);
        if (target == null || !target.isOnline()) {
            throw new IllegalStateException("player offline: " + name);
        }
        return target;
    }

    private static String summarize(ItemStack s) {
        if (s == null || s.getType().isAir() || s.getAmount() <= 0) {
            return "AIR";
        }
        return s.getAmount() + "x" + s.getType().name();
    }

    private static String json(Object v) {
        if (v == null) return "null";
        if (v instanceof Map<?, ?> m) {
            return "{" + String.join(",", m.entrySet().stream()
                    .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (v instanceof Collection<?> c) {
            return "[" + String.join(",", c.stream().map(UtilitiesGate::json).toList()) + "]";
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
