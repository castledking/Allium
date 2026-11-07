package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ResolutionResult;
import com.google.gson.GsonBuilder;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S06b validation fixture. Records the resource pack statuses a real protocol client reports, and
 * snapshots what Allium stamps on real built-in Mob Disarmers in each charge state. It never sends
 * a pack itself: the disposable lane's server.properties does.
 */
public final class PackGate extends JavaPlugin implements Listener {

    private static final String BOT = "GateBot";
    private static final String BEHAVIOR = "allium:mob_disarmer";
    private final List<Map<String, Object>> statuses = new ArrayList<>();
    private ItemService items;

    @Override
    public void onEnable() {
        items = ((PluginStart) getServer().getPluginManager().getPlugin("Allium")).getItemService();
        getServer().getPluginManager().registerEvents(this, this);
        try {
            write("wire-ids", Map.of("wirePackets", WireIds.dump()));
        } catch (Throwable e) {
            getLogger().info("S06B wire catalog unavailable on this version (protocol library client is used): " + e);
        }
        getLogger().info("S06B-GATE READY " + getServer().getBukkitVersion());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("console only");
            return true;
        }
        try {
            switch (args[0]) {
                case "status" -> write("status-" + args[1], status());
                case "stamps" -> write("stamps", stamps(BOT));
                case "give" -> write("give-" + args[1], stamps(args[1]));
                case "arena" -> arena(args[1]);
                default -> throw new IllegalArgumentException("unknown " + args[0]);
            }
            getLogger().info("S06B-DONE " + String.join(" ", args));
        } catch (Exception e) {
            getLogger().severe("S06B-FAIL " + String.join(" ", args) + " " + e);
            e.printStackTrace();
        }
        return true;
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!event.getPlayer().getName().equals(BOT)) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", event.getStatus().name());
        row.put("packId", call(PlayerResourcePackStatusEvent.class, event, "getID"));
        row.put("time", System.currentTimeMillis());
        statuses.add(row);
        getLogger().info("S06B-STATUS " + event.getStatus().name());
    }

    private Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Player bot = getServer().getPlayerExact(BOT);
        out.put("botOnline", bot != null && bot.isOnline());
        out.put("statuses", new ArrayList<>(statuses));
        // Paper answers server.properties packs during configuration: no Bukkit event fires, but the
        // connection keeps the last status the client sent (Paper API; absent on Spigot).
        Object finalStatus = bot == null ? null : call(Player.class, bot, "getResourcePackStatus");
        out.put("finalStatus", finalStatus == null ? null : String.valueOf(finalStatus));
        return out;
    }

    /** Real built-in disarmers created through ItemService in each state, as Allium stamps them. */
    private Map<String, Object> stamps(String playerName) {
        Map<String, Map<String, String>> states = new LinkedHashMap<>();
        states.put("full", Map.of());
        states.put("two_thirds", Map.of("charges", "2", "recharge_at", "0", "broken", "false", "uses", "1"));
        states.put("one_third", Map.of("charges", "1", "recharge_at", "0", "broken", "false", "uses", "2"));
        states.put("empty", Map.of("charges", "0", "recharge_at", Long.toString(System.currentTimeMillis() + 3_600_000L), "broken", "false", "uses", "3"));
        states.put("broken", Map.of("charges", "0", "recharge_at", "0", "broken", "true", "uses", "3"));
        Player bot = getServer().getPlayerExact(playerName);
        if (bot == null && !playerName.equals(BOT)) throw new IllegalStateException(playerName + " is not online");
        Map<String, Object> out = new LinkedHashMap<>();
        int slot = 0;
        for (Map.Entry<String, Map<String, String>> state : states.entrySet()) {
            ResolutionResult result = items.create(new ItemRef(ProviderKey.allium("mob_disarmer"), BEHAVIOR, 1, state.getValue()));
            if (!result.isResolved()) throw new IllegalStateException(state.getKey() + ": " + result.diagnosticMessage());
            ItemStack stack = result.itemStack().orElseThrow();
            ItemMeta meta = stack.getItemMeta();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("material", stack.getType().name());
            row.put("customModelData", meta.hasCustomModelData() ? meta.getCustomModelData() : null);
            row.put("displayName", meta.hasDisplayName() ? meta.getDisplayName() : null);
            row.put("itemModel", itemModel(meta));
            row.put("behavior", items.identify(stack).flatMap(ItemRef::behaviorId).orElse(null));
            row.put("metaString", metaString(meta));
            row.put("slot", slot);
            out.put(state.getKey(), row);
            if (bot != null) bot.getInventory().setItem(slot, stack);
            slot++;
        }
        return out;
    }

    /** Manual checks: four armed zombies with no AI in front of the player, to disarm with a real client. */
    private void arena(String playerName) {
        Player player = getServer().getPlayerExact(playerName);
        if (player == null) throw new IllegalStateException(playerName + " is not online");
        org.bukkit.Location base = player.getLocation();
        org.bukkit.util.Vector ahead = base.getDirection().setY(0).normalize().multiply(3);
        for (int i = 0; i < 4; i++) {
            org.bukkit.Location at = base.clone().add(ahead).add(ahead.clone().crossProduct(new org.bukkit.util.Vector(0, 1, 0)).normalize().multiply(i * 1.5 - 2.25));
            org.bukkit.entity.Zombie zombie = (org.bukkit.entity.Zombie) player.getWorld().spawnEntity(at, org.bukkit.entity.EntityType.ZOMBIE);
            zombie.setAI(false);
            zombie.setSilent(true);
            zombie.setPersistent(true);
            zombie.setAdult();
            org.bukkit.inventory.EntityEquipment eq = zombie.getEquipment();
            eq.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
            eq.setHelmet(new ItemStack(Material.IRON_HELMET));
            eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        }
    }

    /** 1.21.2+ API only; absent on 1.20.x. */
    private static Object itemModel(ItemMeta meta) {
        try {
            // Look methods up on the public API interface; implementation classes may not be public.
            Method has = ItemMeta.class.getMethod("hasItemModel");
            if (!(Boolean) has.invoke(meta)) return "none";
            return String.valueOf(ItemMeta.class.getMethod("getItemModel").invoke(meta));
        } catch (NoSuchMethodException e) {
            return "api-absent";
        } catch (ReflectiveOperationException e) {
            return "error " + e;
        }
    }

    private static String metaString(ItemMeta meta) {
        for (String name : List.of("getAsComponentString", "getAsString")) {
            Object value = call(ItemMeta.class, meta, name);
            if (value != null) return name + ": " + value;
        }
        return "toString: " + meta;
    }

    private static Object call(Class<?> api, Object target, String name) {
        try {
            return api.getMethod(name).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
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
