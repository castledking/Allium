package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.item.ItemProviderAdapter;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.item.delivery.ItemGiveEvent;
import codes.castled.allium.kit.KitDefinition;
import codes.castled.allium.kit.KitRepository;
import codes.castled.allium.kit.KitService;
import codes.castled.allium.mail.PlayerDataFiles;
import codes.castled.allium.module.KitsModule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Only prepares fixtures and observes state; all tested kit operations arrive over real clients. */
public final class KitGate extends JavaPlugin implements Listener {
    private static final NamespacedKey NEXO_ID_KEY = new NamespacedKey("nexo", "id");
    private PluginStart allium;
    private KitService initialKits;
    private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();
    private final Set<UUID> cancelled = new HashSet<>();
    private final Map<UUID, Integer> giveEvents = new HashMap<>();

    @Override
    public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || (getServer().getPort() < 25760 || getServer().getPort() > 25762)) {
            throw new IllegalStateException("TEST ONLY: 127.0.0.1:25760-25762 required");
        }
        allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
        try {
            Files.createDirectories(getDataFolder().toPath());
            try {
                Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),
                        json(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8);
            } catch (ReflectiveOperationException e) {
                getLogger().info("S16G legacy protocol client required");
            }
            registerTestNexoAdapter();
            initialKits = kits();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("S16G-GATE READY " + getServer().getBukkitVersion());
    }

    private void registerTestNexoAdapter() {
        allium.getItemService().registerProvider(new ItemProviderAdapter() {
            @Override
            public String providerName() {
                return ProviderKey.NEXO;
            }

            @Override
            public ProviderStatus status() {
                return ProviderStatus.READY;
            }

            @Override
            public ResolutionResult createItem(String id) {
                String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
                Material mat = switch (normalized) {
                    case "ruby_sword" -> Material.DIAMOND_SWORD;
                    case "emerald_charm" -> Material.EMERALD;
                    default -> null;
                };
                if (mat == null) {
                    return ResolutionResult.unknownId(
                            new ProviderKey(ProviderKey.NEXO, normalized),
                            "Unknown test Nexo item '" + normalized + "'");
                }
                ItemStack stack = new ItemStack(mat, 1);
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("\u00a7cNexo " + normalized);
                    meta.getPersistentDataContainer().set(NEXO_ID_KEY, PersistentDataType.STRING, normalized);
                    stack.setItemMeta(meta);
                }
                return ResolutionResult.resolved(new ProviderKey(ProviderKey.NEXO, normalized), stack);
            }

            @Override
            public Optional<String> identifyItemId(ItemStack stack) {
                if (stack == null || stack.getType().isAir()) return Optional.empty();
                ItemMeta meta = stack.getItemMeta();
                if (meta == null) return Optional.empty();
                return Optional.ofNullable(meta.getPersistentDataContainer().get(NEXO_ID_KEY, PersistentDataType.STRING));
            }

            @Override
            public boolean hasAuthoritativeNativeIdCheck() {
                return true;
            }
        });
    }

    private KitService kits() {
        KitsModule module = (KitsModule) allium.getModuleManager().getModule("kits");
        return module.kits();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return true;
        String text = String.join(" ", args);
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            switch (args[0]) {
                case "online" -> out.put("players", getServer().getOnlinePlayers().stream().map(Player::getName).toList());
                case "arm" -> {
                    FaultInjection.arm(args[1], Integer.parseInt(args[2]));
                    out.put("armed", FaultInjection.armed(args[1]));
                }
                case "fault" -> {
                    out.put("tripped", FaultInjection.tripped(args[1]));
                    out.put("armed", FaultInjection.armed(args[1]));
                }
                case "reset" -> FaultInjection.reset();
                case "defs" -> {
                    Map<String, Object> defs = new LinkedHashMap<>();
                    for (KitDefinition def : kits().definitions().values()) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("enabled", def.enabled());
                        d.put("cooldownSeconds", def.cooldownSeconds());
                        d.put("oneTime", def.oneTime());
                        d.put("cost", def.cost());
                        d.put("paid", def.paid());
                        d.put("items", def.items());
                        defs.put(def.name(), d);
                    }
                    out.put("definitions", defs);
                    out.put("sameService", initialKits == kits());
                    out.put("serverId", kits().serverId());
                }
                case "foreigncheck" -> {
                    UUID deliveryId = UUID.fromString(args[1]);
                    UUID token = UUID.fromString(args[2]);
                    KitRepository repo = new KitRepository(
                            allium.getDatabaseManager().connectionSource(),
                            allium.getDatabaseManager().dialect(),
                            System::currentTimeMillis);
                    String myServerId = kits().serverId();
                    out.put("serverId", myServerId);
                    out.put("releaseAllowed", repo.releaseClaim(deliveryId, token, myServerId));
                    out.put("finishAllowed", repo.finishClaim(deliveryId, token, myServerId, System.currentTimeMillis()));
                }
                case "db" -> {
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + args[1]).getBytes(StandardCharsets.UTF_8));
                    List<Object> usages = new ArrayList<>();
                    List<Object> deliveries = new ArrayList<>();
                    List<Object> receipts = new ArrayList<>();
                    try (Connection c = allium.getDatabaseManager().getConnection()) {
                        try (PreparedStatement s = c.prepareStatement(
                                "SELECT kit_name, last_claimed_at, one_time_claimed, claim_count FROM allium_kit_usages WHERE player_uuid=? ORDER BY kit_name")) {
                            s.setString(1, id.toString());
                            try (ResultSet r = s.executeQuery()) {
                                while (r.next()) {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put("kit_name", r.getString("kit_name"));
                                    row.put("last_claimed_at", r.getLong("last_claimed_at"));
                                    row.put("one_time_claimed", r.getBoolean("one_time_claimed"));
                                    row.put("claim_count", r.getInt("claim_count"));
                                    usages.add(row);
                                }
                            }
                        }
                        try (PreparedStatement s = c.prepareStatement(
                                "SELECT id, kit_name, claim_token, claim_owner, item_count, cooldown_millis, one_time, bypass_cooldown FROM allium_kit_deliveries WHERE player_uuid=? ORDER BY created_at")) {
                            s.setString(1, id.toString());
                            try (ResultSet r = s.executeQuery()) {
                                while (r.next()) {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put("id", r.getString("id"));
                                    row.put("kit_name", r.getString("kit_name"));
                                    row.put("claim_token", r.getString("claim_token"));
                                    row.put("claim_owner", r.getString("claim_owner"));
                                    row.put("item_count", r.getInt("item_count"));
                                    row.put("cooldown_millis", r.getLong("cooldown_millis"));
                                    row.put("one_time", r.getBoolean("one_time"));
                                    row.put("bypass_cooldown", r.getBoolean("bypass_cooldown"));
                                    deliveries.add(row);
                                }
                            }
                        }
                        try (PreparedStatement s = c.prepareStatement(
                                "SELECT delivery_id, token, kit_name FROM allium_kit_claim_receipts WHERE player_uuid=? ORDER BY delivered_at")) {
                            s.setString(1, id.toString());
                            try (ResultSet r = s.executeQuery()) {
                                while (r.next()) {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put("delivery_id", r.getString("delivery_id"));
                                    row.put("claim_token", r.getString("token"));
                                    row.put("kit_name", r.getString("kit_name"));
                                    receipts.add(row);
                                }
                            }
                        }
                    }
                    out.put("usages", usages);
                    out.put("deliveries", deliveries);
                    out.put("receipts", receipts);
                }
                default -> {
                    Player target = getServer().getPlayerExact(args[1]);
                    if (target == null) throw new IllegalArgumentException("online player required");
                    switch (args[0]) {
                        case "perm" -> {
                            PermissionAttachment p = permissions.computeIfAbsent(target.getUniqueId(), id -> target.addAttachment(this));
                            p.setPermission(args[2], Boolean.parseBoolean(args[3]));
                            target.updateCommands();
                            out.put("permission", target.hasPermission(args[2]));
                        }
                        case "cancel" -> {
                            if (args[2].equals("on")) cancelled.add(target.getUniqueId());
                            else cancelled.remove(target.getUniqueId());
                            out.put("cancelled", cancelled.contains(target.getUniqueId()));
                        }
                        case "empty" -> {
                            target.getInventory().clear();
                            target.saveData();
                        }
                        case "room" -> {
                            target.getInventory().clear();
                            for (int i = 0; i < 36; i++) {
                                target.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
                            }
                            int freeSlots = Integer.parseInt(args[2]);
                            for (int i = 0; i < freeSlots && i < 36; i++) {
                                target.getInventory().setItem(35 - i, null);
                            }
                            target.saveData();
                        }
                        case "givecustom" -> {
                            target.getInventory().clear();
                            var nexoRes = allium.getItemService().create(ItemRef.parse("nexo:ruby_sword"));
                            if (!nexoRes.isResolved()) throw new IllegalStateException(nexoRes.diagnosticMessage());
                            var signedRes = allium.getItemService().createForBehavior("allium:phantom_obliterator", 1);
                            if (!signedRes.isResolved()) throw new IllegalStateException(signedRes.diagnosticMessage());
                            target.getInventory().setItem(0, nexoRes.itemStack().orElseThrow());
                            target.getInventory().setItem(1, signedRes.itemStack().orElseThrow());
                            target.getInventory().setItem(2, new ItemStack(Material.GOLDEN_APPLE, 3));
                            target.saveData();
                            out.put("prepared", 3);
                        }
                        case "givelossy" -> {
                            target.getInventory().clear();
                            ItemStack book = new ItemStack(Material.WRITTEN_BOOK, 1);
                            BookMeta bm = (BookMeta) book.getItemMeta();
                            if (bm != null) {
                                bm.setTitle("Secret Tome");
                                bm.setAuthor("Admin");
                                bm.addPage("Page 1");
                                book.setItemMeta(bm);
                            }
                            target.getInventory().setItem(3, book);
                            target.saveData();
                            out.put("prepared", 1);
                        }
                        case "state" -> {
                            out.put("kitReady", kits().ready(target) && !kits().busy(target));
                            out.put("kitBusy", kits().busy(target));
                            out.put("sameService", initialKits == kits());
                            out.put("playerDataDirectory", PlayerDataFiles.directory(getServer()).toString());
                            out.put("giveEvents", giveEvents.getOrDefault(target.getUniqueId(), 0));
                            out.put("receipts", target.getPersistentDataContainer().get(
                                    new NamespacedKey(allium, "kit_receipts"), PersistentDataType.STRING));
                            List<Object> stacks = new ArrayList<>();
                            for (int i = 0; i < 36; i++) {
                                ItemStack s = target.getInventory().getItem(i);
                                if (s != null && !s.getType().isAir()) {
                                    Map<String, Object> row = describe(s);
                                    row.put("slot", i);
                                    stacks.add(row);
                                }
                            }
                            out.put("stacks", stacks);
                        }
                        default -> throw new IllegalArgumentException("unknown action: " + args[0]);
                    }
                }
            }
            getLogger().info("S16G-DONE " + text + " => " + json(out));
        } catch (Throwable e) {
            getLogger().info("S16G-FAIL " + text + " => " + e);
        }
        return true;
    }

    private Map<String, Object> describe(ItemStack s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("material", s.getType().name());
        out.put("amount", s.getAmount());
        ItemRef ref = allium.getItemService().identify(s).orElse(null);
        out.put("provider", ref == null ? null : ref.appearance().provider());
        out.put("itemId", ref == null ? null : ref.appearance().id());
        out.put("behavior", ref == null ? null : ref.behaviorId().orElse(null));
        out.put("state", ref == null ? null : ref.stateData());
        var p = s.getItemMeta().getPersistentDataContainer();
        for (String k : List.of("signature", "instance_uuid")) {
            out.put(k, p.get(new NamespacedKey("allium", k), PersistentDataType.STRING));
        }
        return out;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void give(ItemGiveEvent e) {
        giveEvents.merge(e.getTarget().getUniqueId(), 1, Integer::sum);
        if (cancelled.contains(e.getTarget().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        permissions.remove(e.getPlayer().getUniqueId());
        cancelled.remove(e.getPlayer().getUniqueId());
    }

    private static String json(Object v) {
        if (v == null) return "null";
        if (v instanceof Map<?, ?> m) {
            return "{" + String.join(",", m.entrySet().stream()
                    .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (v instanceof Collection<?> c) {
            return "[" + String.join(",", c.stream().map(KitGate::json).toList()) + "]";
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
