package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.delivery.ItemGiveEvent;
import com.google.gson.GsonBuilder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S04 give gate fixture. Prepares inventories, arms test-only faults and records what the server
 * holds after a console /give. It never gives items itself.
 */
public final class GiveGate extends JavaPlugin implements Listener {

    private static final String BOT = "GateBot";
    private ItemService items;
    private boolean cancelNext;
    private final List<Map<String, Object>> events = new ArrayList<>();

    @Override
    public void onEnable() {
        items = ((PluginStart) getServer().getPluginManager().getPlugin("Allium")).getItemService();
        getServer().getPluginManager().registerEvents(this, this);
        try {
            write("wire-ids", Map.of("wirePackets", WireIds.dump()));
        } catch (Throwable e) {
            getLogger().info("S04G wire catalog unavailable on this version (protocol library client is used): " + e);
        }
        getLogger().info("S04G-GATE READY " + getServer().getBukkitVersion());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("ggwho")) {
            StringBuilder out = new StringBuilder("S04G-WHO class=" + sender.getClass().getName() + " name=" + sender.getName());
            if (sender instanceof org.bukkit.command.ProxiedCommandSender proxied) {
                out.append(" caller=").append(proxied.getCaller().getName()).append(" callee=").append(proxied.getCallee().getName());
            }
            if (sender instanceof Entity entity) {
                out.append(" at=").append(entity.getLocation().getBlockX()).append(',').append(entity.getLocation().getBlockZ());
            }
            try {
                out.append(" @s=").append(getServer().selectEntities(sender, "@s").stream().map(Entity::getName).toList());
                out.append(" @p=").append(getServer().selectEntities(sender, "@p").stream().map(Entity::getName).toList());
            } catch (IllegalArgumentException e) {
                out.append(" selectors=").append(e.getMessage());
            }
            getLogger().info(out.toString());
            return true;
        }
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("console only");
            return true;
        }
        try {
            switch (args[0]) {
                case "fill" -> fill(Integer.parseInt(args[1]));
                case "clear" -> clear();
                case "snapshot" -> write("snapshot-" + args[1], snapshot(args.length > 2 ? player(args[2]) : bot()));
                case "tp" -> tp(player(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                case "cmdblock" -> commandBlock(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                        String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
                case "perm" -> permission(player(args[1]), args[2], Boolean.parseBoolean(args[3]));
                case "wear" -> player(args[1]).getInventory().setItem(org.bukkit.inventory.EquipmentSlot.valueOf(args[2]),
                        new ItemStack(Material.valueOf(args[3])));
                case "skullstats" -> write("skullstats", skullStats());
                case "cancel-next" -> cancelNext = true;
                case "fault" -> FaultInjection.arm(args[1], Integer.parseInt(args[2]));
                case "status" -> {
                    Map<String, Object> status = new LinkedHashMap<>();
                    status.put("botOnline", getServer().getPlayerExact(BOT) != null);
                    for (String provider : List.of("nexo", "craftengine", "oraxen")) {
                        status.put(provider, items.getProvider(provider).map(a -> a.status().name()).orElse("UNREGISTERED"));
                    }
                    write("status", status);
                }
                default -> throw new IllegalArgumentException("unknown " + args[0]);
            }
            getLogger().info("S04G-DONE " + String.join(" ", args));
        } catch (Exception e) {
            getLogger().severe("S04G-FAIL " + String.join(" ", args) + " " + e);
            e.printStackTrace();
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onGive(ItemGiveEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("target", event.getTarget().getName());
        row.put("items", event.getItems().size());
        row.put("equip", event.isEquipRequested());
        row.put("cancelled", cancelNext);
        events.add(row);
        if (cancelNext) {
            event.setCancelled(true);
            cancelNext = false;
        }
    }

    private Player bot() {
        return player(BOT);
    }

    private Player player(String name) {
        Player player = getServer().getPlayerExact(name);
        if (player == null) throw new IllegalStateException(name + " is not online");
        return player;
    }

    private void tp(Player player, int x, int z) {
        org.bukkit.World world = player.getWorld();
        player.teleport(new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5));
    }

    /** A command block at (x, ground, z) with this command, powered by a redstone block on top: it runs once. */
    private void commandBlock(int x, int z, String command) {
        org.bukkit.World world = getServer().getWorlds().get(0);
        int y = world.getHighestBlockYAt(x, z) + 1;
        org.bukkit.block.Block block = world.getBlockAt(x, y, z);
        block.setType(Material.COMMAND_BLOCK);
        org.bukkit.block.CommandBlock state = (org.bukkit.block.CommandBlock) block.getState();
        state.setCommand(command);
        state.update(true);
        world.getBlockAt(x, y + 1, z).setType(Material.REDSTONE_BLOCK);
        getServer().getScheduler().runTaskLater(this, () -> {
            world.getBlockAt(x, y + 1, z).setType(Material.AIR);
            block.setType(Material.AIR);
        }, 20L);
    }

    private final Map<String, org.bukkit.permissions.PermissionAttachment> attachments = new java.util.HashMap<>();

    private void permission(Player player, String node, boolean value) {
        org.bukkit.permissions.PermissionAttachment attachment =
                attachments.computeIfAbsent(player.getName(), n -> player.addAttachment(this));
        attachment.setPermission(node, value);
        player.recalculatePermissions();
        player.updateCommands();
    }

    private Map<String, Object> skullStats() throws ReflectiveOperationException {
        PluginStart allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
        Object module = allium.getModuleManager().getModule("items");
        Object stats = module.getClass().getMethod("skullStats").invoke(module);
        Map<String, Object> out = new LinkedHashMap<>();
        for (java.lang.reflect.RecordComponent c : stats.getClass().getRecordComponents()) {
            out.put(c.getName(), c.getAccessor().invoke(stats));
        }
        return out;
    }

    /** Fills every storage slot with 64 stone except the last {@code free} ones; clears dropped items. */
    private void fill(int free) {
        clear();
        PlayerInventory inventory = bot().getInventory();
        for (int slot = 0; slot < 36 - free; slot++) {
            inventory.setItem(slot, new ItemStack(Material.STONE, 64));
        }
    }

    private void clear() {
        for (Player player : getServer().getOnlinePlayers()) {
            player.getInventory().clear();
        }
        for (Entity entity : getServer().getWorlds().get(0).getEntities()) {
            if (entity instanceof Item) entity.remove();
        }
        events.clear();
    }

    private Map<String, Object> snapshot(Player bot) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> slots = new ArrayList<>();
        ItemStack[] contents = bot.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir() || (stack.getType() == Material.STONE && stack.getAmount() == 64)) continue;
            Map<String, Object> row = describe(stack);
            row.put("slot", slot);
            slots.add(row);
        }
        out.put("slots", slots);
        int stone = 0;
        for (ItemStack stack : bot.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == Material.STONE) stone += stack.getAmount();
        }
        out.put("stone", stone);
        List<Map<String, Object>> ground = new ArrayList<>();
        Location at = bot.getLocation();
        for (Entity entity : bot.getWorld().getEntities()) {
            if (entity instanceof Item item && entity.getLocation().distanceSquared(at) < 100) {
                ground.add(Map.of("material", item.getItemStack().getType().name(), "amount", item.getItemStack().getAmount()));
            }
        }
        out.put("ground", ground);
        out.put("events", new ArrayList<>(events));
        Map<String, Object> armor = new LinkedHashMap<>();
        for (org.bukkit.inventory.EquipmentSlot slot : List.of(org.bukkit.inventory.EquipmentSlot.HEAD, org.bukkit.inventory.EquipmentSlot.CHEST,
                org.bukkit.inventory.EquipmentSlot.LEGS, org.bukkit.inventory.EquipmentSlot.FEET)) {
            ItemStack worn = bot.getInventory().getItem(slot);
            armor.put(slot.name(), worn == null || worn.getType().isAir() ? null : worn.getType().name());
        }
        out.put("armor", armor);
        out.put("op", bot.isOp());
        out.put("player", bot.getName());
        return out;
    }

    @SuppressWarnings("deprecation")
    private Map<String, Object> describe(ItemStack stack) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("material", stack.getType().name());
        row.put("amount", stack.getAmount());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return row;
        row.put("name", meta.hasDisplayName() ? meta.getDisplayName() : null);
        row.put("lore", meta.hasLore() ? meta.getLore() : null);
        row.put("unbreakable", meta.isUnbreakable());
        row.put("customModelData", meta.hasCustomModelData() ? meta.getCustomModelData() : null);
        Map<String, Integer> enchants = new LinkedHashMap<>();
        for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) enchants.put(e.getKey().getKey().getKey(), e.getValue());
        row.put("enchants", enchants);
        if (meta instanceof SkullMeta skull) {
            row.put("skullOwner", skull.getOwnerProfile() == null ? null : skull.getOwnerProfile().getName());
            row.put("skullTextured", skull.getOwnerProfile() != null && skull.getOwnerProfile().getTextures().getSkin() != null);
        }
        if (meta instanceof PotionMeta potion) {
            row.put("potion", basePotion(potion));
        }
        ItemRef ref = items.identify(stack).orElse(null);
        row.put("appearance", ref == null ? null : ref.appearance().toString());
        row.put("behavior", ref == null ? null : ref.behaviorId().orElse(null));
        row.put("state", ref == null ? null : ref.stateData());
        row.put("instanceUuid", meta.getPersistentDataContainer().get(new NamespacedKey("allium", "instance_uuid"), PersistentDataType.STRING));
        row.put("signature", meta.getPersistentDataContainer().get(new NamespacedKey("allium", "signature"), PersistentDataType.STRING));
        return row;
    }

    @SuppressWarnings("deprecation")
    private static String basePotion(PotionMeta potion) {
        try {
            Object type = PotionMeta.class.getMethod("getBasePotionType").invoke(potion);
            return String.valueOf(type);
        } catch (ReflectiveOperationException e) {
            return String.valueOf(potion.getBasePotionData().getType());
        }
    }

    private void write(String name, Object value) throws IOException {
        Path dir = getDataFolder().toPath();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name + ".json.tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(value), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(name + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

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
                            (proxy, method, args) -> {
                                if (method.getName().equals("accept")) {
                                    ids.put(packetType.getMethod("id").invoke(args[0]).toString(), (Integer) args[1]);
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
