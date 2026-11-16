package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.mail.MailService;
import codes.castled.allium.mail.PlayerDataFiles;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.moderation.CraftBukkitPlayerDataCodec;
import codes.castled.allium.moderation.InspectionLayout;
import codes.castled.allium.moderation.InspectionService;
import codes.castled.allium.moderation.ModerationAuditRecord;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.moderation.OfflinePlayerDataStore;
import codes.castled.allium.module.MailModule;
import codes.castled.allium.module.ModerationModule;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * S24 inspection test-gate helper. Only manages fixtures, observes live Bukkit state and
 * reads/manipulates player .dat files for negative/CAS tests. All inspection commands and GUI
 * interactions run through real protocol clients.
 */
public final class InspectGate extends JavaPlugin implements Listener {

    private PluginStart allium;
    private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();
    private final Map<UUID, byte[]> datBackups = new HashMap<>();

    private volatile UUID probeSameTickTargetUuid;
    private volatile UUID probeDeathOnClickTargetUuid;
    private volatile Map<String, Object> lastSameTickGuardResult;
    private volatile Map<String, Object> lastDeathOnClickResult;
    private volatile boolean captureNextDeathDrops;
    private volatile List<String> lastDeathDrops = List.of();

    @Override
    public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || (getServer().getPort() < 25800 || getServer().getPort() > 25802)) {
            throw new IllegalStateException("TEST ONLY: 127.0.0.1:25800-25802 required");
        }
        allium = (PluginStart) getServer().getPluginManager().getPlugin("Allium");
        try {
            Files.createDirectories(getDataFolder().toPath());
            try {
                Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"),
                        json(Map.of("wirePackets", WireIds.dump())), StandardCharsets.UTF_8);
            } catch (ReflectiveOperationException e) {
                getLogger().info("S24G legacy protocol client required");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("S24G-GATE READY " + getServer().getBukkitVersion());
    }

    private ModerationModule modModule() {
        return (ModerationModule) allium.getModuleManager().getModule("moderation");
    }

    private ModerationService moderation() {
        return modModule().moderation();
    }

    private InspectionService inspection() {
        return modModule().inspection();
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
                    moderation().syncAllOnlineVisibility();
                    out.put("permission", target.hasPermission(args[2]));
                }
                case "seedinv" -> {
                    Player target = requireOnline(args[1]);
                    PlayerInventory inv = target.getInventory();
                    inv.clear();
                    target.getEnderChest().clear();
                    inv.setItem(0, new ItemStack(Material.DIAMOND, 3));
                    inv.setItem(9, new ItemStack(Material.EMERALD, 5));
                    inv.setItem(35, new ItemStack(Material.GOLD_INGOT, 7));
                    inv.setBoots(new ItemStack(Material.DIAMOND_BOOTS, 1));
                    inv.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS, 1));
                    inv.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE, 1));
                    inv.setHelmet(new ItemStack(Material.DIAMOND_HELMET, 1));
                    inv.setItemInOffHand(new ItemStack(Material.SHIELD, 1));

                    Inventory ec = target.getEnderChest();
                    ec.setItem(0, new ItemStack(Material.NETHERITE_INGOT, 2));
                    ec.setItem(26, new ItemStack(Material.ENDER_PEARL, 4));

                    PersistentDataContainer pdc = target.getPersistentDataContainer();
                    pdc.set(new NamespacedKey("allium", "s24_proof"), PersistentDataType.STRING, "allium-keep-s24");
                    pdc.set(new NamespacedKey("inspgate", "marker"), PersistentDataType.STRING, "keep-me-s24");

                    out.put("uuid", target.getUniqueId().toString());
                    out.put("slots", describePlayerSlots(target));
                }
                case "seedreceipts" -> {
                    Player target = requireOnline(args[1]);
                    UUID id = target.getUniqueId();
                    PersistentDataContainer pdc = target.getPersistentDataContainer();
                    String mailReceipt = "S/" + id + ";C/" + id + "/" + id + "/1,2;FUTURE/mail-s24";
                    String kitReceipt = "K/" + id + "/" + id + "/starter;FUTURE/kit-s24";
                    pdc.set(NamespacedKey.fromString(PlayerDataReceiptProof.MAIL_RECEIPTS), PersistentDataType.STRING, mailReceipt);
                    pdc.set(NamespacedKey.fromString(PlayerDataReceiptProof.KIT_RECEIPTS), PersistentDataType.STRING, kitReceipt);
                    pdc.set(new NamespacedKey("unknownmod", "custom_marker"), PersistentDataType.STRING, "opaque-unknown-s24");
                    out.put("seededReceipts", true);
                    out.put("mailReceipt", mailReceipt);
                    out.put("kitReceipt", kitReceipt);
                }
                case "setslot" -> {
                    Player target = requireOnline(args[1]);
                    String which = args[2];
                    Material mat = Material.valueOf(args[3]);
                    int amount = Integer.parseInt(args[4]);
                    ItemStack stack = (mat == Material.AIR || amount <= 0) ? null : new ItemStack(mat, amount);
                    switch (which) {
                        case "offhand" -> target.getInventory().setItemInOffHand(stack);
                        case "helmet" -> target.getInventory().setHelmet(stack);
                        case "boots" -> target.getInventory().setBoots(stack);
                        case "ec0" -> target.getEnderChest().setItem(0, stack);
                        default -> {
                            int slot = Integer.parseInt(which);
                            target.getInventory().setItem(slot, stack);
                        }
                    }
                    out.put("slots", describePlayerSlots(target));
                }
                case "clearinv" -> {
                    Player target = requireOnline(args[1]);
                    target.getInventory().clear();
                    target.getEnderChest().clear();
                    out.put("slots", describePlayerSlots(target));
                }
                case "inv" -> {
                    Player target = requireOnline(args[1]);
                    out.put("uuid", target.getUniqueId().toString());
                    out.put("slots", describePlayerSlots(target));
                    out.put("pdc", describePdc(target));
                    InventoryView view = target.getOpenInventory();
                    if (view != null && view.getTopInventory() != null) {
                        Inventory top = view.getTopInventory();
                        out.put("openTopType", top.getType().name());
                        out.put("openTopSize", top.getSize());
                        out.put("openTopHolder", top.getHolder() == null ? null : top.getHolder().getClass().getName());
                        out.put("openTopNonEmpty", describeInventory(top));
                    }
                }
                case "closegui" -> {
                    Player target = requireOnline(args[1]);
                    target.closeInventory();
                    out.put("closed", true);
                }
                case "savedat" -> {
                    Player target = requireOnline(args[1]);
                    target.saveData();
                    UUID id = target.getUniqueId();
                    Path dat = playerDat(id);
                    out.put("exists", Files.isRegularFile(dat));
                    out.put("size", Files.isRegularFile(dat) ? Files.size(dat) : 0);
                }
                case "datinfo" -> {
                    UUID id = resolveUuid(args[1]);
                    Path dat = playerDat(id);
                    out.put("uuid", id.toString());
                    out.put("exists", Files.isRegularFile(dat));
                    if (Files.isRegularFile(dat)) {
                        byte[] raw = Files.readAllBytes(dat);
                        out.put("size", raw.length);
                        out.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
                        PlayerDataReceiptProof.Snapshot proof = PlayerDataReceiptProof.inspect(raw);
                        out.put("dataVersion", proof.dataVersion());
                        out.put("bukkitValues", proof.bukkitValues());
                        CraftBukkitPlayerDataCodec codec = new CraftBukkitPlayerDataCodec(getServer());
                        ItemStack[] inv = codec.read(raw, InspectionLayout.InspectionType.INVSEE).slots();
                        ItemStack[] ec = codec.read(raw, InspectionLayout.InspectionType.ENDERCHEST).slots();
                        out.put("inv", summarizeSlotArray(inv));
                        out.put("ec", summarizeSlotArray(ec));
                    }
                }
                case "touchdat" -> {
                    UUID id = resolveUuid(args[1]);
                    Path dat = playerDat(id);
                    byte[] raw = Files.readAllBytes(dat);
                    datBackups.put(id, raw);
                    CraftBukkitPlayerDataCodec codec = new CraftBukkitPlayerDataCodec(getServer());
                    ItemStack[] ec = codec.read(raw, InspectionLayout.InspectionType.ENDERCHEST).slots();
                    ec[25] = new ItemStack(Material.APPLE, 9);
                    byte[] updated = codec.write(raw, InspectionLayout.InspectionType.ENDERCHEST, ec);
                    Files.write(dat, updated);
                    out.put("touched", true);
                    out.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(updated)));
                }
                case "corruptdat" -> {
                    UUID id = resolveUuid(args[1]);
                    Path dat = playerDat(id);
                    if (Files.isRegularFile(dat)) {
                        datBackups.put(id, Files.readAllBytes(dat));
                    }
                    Files.writeString(dat, "NOT_A_VALID_GZIP_PLAYERDATA", StandardCharsets.UTF_8);
                    out.put("corrupted", true);
                }
                case "newerdat" -> {
                    UUID id = resolveUuid(args[1]);
                    Path dat = playerDat(id);
                    if (Files.isRegularFile(dat)) {
                        datBackups.put(id, Files.readAllBytes(dat));
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (GZIPOutputStream gz = new GZIPOutputStream(baos);
                         DataOutputStream dos = new DataOutputStream(gz)) {
                        dos.writeByte(10); // TAG_Compound
                        dos.writeUTF("");
                        dos.writeByte(3); // TAG_Int
                        dos.writeUTF("DataVersion");
                        dos.writeInt(9_999_999);
                        dos.writeByte(0); // TAG_End
                    }
                    Files.write(dat, baos.toByteArray());
                    out.put("newerDataVersion", 9_999_999);
                }
                case "olderdat" -> {
                    UUID id = resolveUuid(args[1]);
                    Path dat = playerDat(id);
                    byte[] existing = Files.readAllBytes(dat);
                    datBackups.put(id, existing);
                    int oldVersion = PlayerDataReceiptProof.inspect(existing).dataVersion() - 1;
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (GZIPOutputStream gz = new GZIPOutputStream(baos);
                         DataOutputStream dos = new DataOutputStream(gz)) {
                        dos.writeByte(10); // TAG_Compound
                        dos.writeUTF("");
                        dos.writeByte(3); // TAG_Int
                        dos.writeUTF("DataVersion");
                        dos.writeInt(oldVersion);
                        dos.writeByte(0); // TAG_End
                    }
                    byte[] older = baos.toByteArray();
                    Files.write(dat, older);
                    out.put("olderDataVersion", oldVersion);
                    out.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(older)));
                }
                case "restoredat" -> {
                    UUID id = resolveUuid(args[1]);
                    byte[] backup = datBackups.remove(id);
                    if (backup == null) {
                        throw new IllegalStateException("No backup for " + id);
                    }
                    Path dat = playerDat(id);
                    Files.write(dat, backup);
                    out.put("restored", true);
                }
                case "sethold" -> {
                    UUID id = resolveUuid(args[1]);
                    boolean held = Boolean.parseBoolean(args[2]);
                    try (var conn = allium.getDatabaseManager().connectionSource().connection()) {
                        if (held) {
                            try (var ps = conn.prepareStatement(
                                    "INSERT OR REPLACE INTO allium_mail (id, recipient, sender, sender_name, kind, body, items, item_count, state, origin, unread, created_at, expires_at, source, source_ref, version) "
                                            + "VALUES ('s24-gate-hold', ?, ?, 'Gate', 'GIFT', NULL, NULL, 1, 'PENDING', 'gate', 1, 0, 0, 'gate', NULL, 1)")) {
                                ps.setString(1, id.toString());
                                ps.setString(2, id.toString());
                                ps.executeUpdate();
                            }
                        } else {
                            try (var ps = conn.prepareStatement("DELETE FROM allium_mail WHERE id = 's24-gate-hold'")) {
                                ps.executeUpdate();
                            }
                        }
                    }
                    out.put("pendingHold", modModule().repository().hasPendingWorkflowHold(id));
                }
                case "mailbusylock" -> {
                    Player target = requireOnline(args[1]);
                    boolean lock = Boolean.parseBoolean(args[2]);
                    MailModule mailModule = (MailModule) allium.getModuleManager().getModule("mail");
                    MailService mail = mailModule.mail();
                    Field f = MailService.class.getDeclaredField("locks");
                    f.setAccessible(true);
                    Object locks = f.get(mail);
                    if (lock) {
                        locks.getClass().getMethod("lock", Player.class).invoke(locks, target);
                    } else {
                        locks.getClass().getMethod("unlock", Player.class).invoke(locks, target);
                    }
                    out.put("busy", mail.busy(target));
                }
                case "armsametick" -> {
                    Player target = requireOnline(args[1]);
                    probeSameTickTargetUuid = target.getUniqueId();
                    lastSameTickGuardResult = null;
                    out.put("armed", true);
                }
                case "getsametick" -> out.put("result", lastSameTickGuardResult);
                case "armdeathclick" -> {
                    Player target = requireOnline(args[1]);
                    target.getWorld().setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
                    target.getWorld().setGameRule(GameRule.KEEP_INVENTORY, false);
                    probeDeathOnClickTargetUuid = target.getUniqueId();
                    lastDeathOnClickResult = null;
                    out.put("armed", true);
                }
                case "getdeathclick" -> out.put("result", lastDeathOnClickResult);
                case "armdeathdrops" -> {
                    captureNextDeathDrops = true;
                    lastDeathDrops = List.of();
                    out.put("armed", true);
                }
                case "death" -> {
                    Player target = requireOnline(args[1]);
                    target.getWorld().setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
                    target.setHealth(0.0);
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (target.isOnline() && target.isDead()) {
                            target.spigot().respawn();
                        }
                    }, 1L);
                    out.put("killed", true);
                }
                case "alive" -> {
                    Player target = requireOnline(args[1]);
                    out.put("alive", !target.isDead() && target.getHealth() > 0.0);
                }
                case "getdeathdrops" -> out.put("drops", lastDeathDrops);
                case "session" -> {
                    UUID id = resolveUuid(args[1]);
                    out.put("invseeActive", inspection().hasActiveMutableSession(id, InspectionLayout.InspectionType.INVSEE));
                    out.put("enderchestActive", inspection().hasActiveMutableSession(id, InspectionLayout.InspectionType.ENDERCHEST));
                    out.put("fenced", inspection().offlineStore() != null && inspection().offlineStore().isOnlineOrFenced(id));
                    out.put("offlineEditingEnabled", inspection().isOfflineEditingEnabled());
                }
                case "db" -> {
                    var repo = modModule().repository();
                    List<Object> audits = new ArrayList<>();
                    for (ModerationAuditRecord a : repo.loadRecentAudit(60)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("action", a.action());
                        row.put("actorName", a.actorName());
                        row.put("targetUuid", a.targetUuid().toString());
                        row.put("targetName", a.targetName());
                        row.put("detail", a.detail());
                        audits.add(row);
                    }
                    out.put("audits", audits);
                }
                default -> throw new IllegalArgumentException("unknown action: " + args[0]);
            }
            getLogger().info("S24G-DONE " + text + " => " + json(out));
        } catch (Throwable e) {
            getLogger().info("S24G-FAIL " + text + " => " + e);
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuiClickMonitor(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof InspectionService.InspectionHolder)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }
        UUID guardTargetId = probeSameTickTargetUuid;
        if (guardTargetId != null) {
            probeSameTickTargetUuid = null;
            Player target = getServer().getPlayer(guardTargetId);
            if (target != null && target.isOnline()) {
                ItemStack dropItem = new ItemStack(Material.STICK, 1);
                var droppedEntity = target.getWorld().dropItem(target.getLocation(), dropItem);
                PlayerDropItemEvent dropEvent = new PlayerDropItemEvent(target, droppedEntity);
                Bukkit.getPluginManager().callEvent(dropEvent);
                droppedEntity.remove();

                PlayerSwapHandItemsEvent swapEvent = new PlayerSwapHandItemsEvent(target, target.getInventory().getItemInMainHand(), target.getInventory().getItemInOffHand());
                Bukkit.getPluginManager().callEvent(swapEvent);

                Map<String, Object> r = new LinkedHashMap<>();
                r.put("dropCancelled", dropEvent.isCancelled());
                r.put("swapCancelled", swapEvent.isCancelled());
                lastSameTickGuardResult = r;
            }
        }
        UUID deathTargetId = probeDeathOnClickTargetUuid;
        if (deathTargetId != null) {
            probeDeathOnClickTargetUuid = null;
            Player target = getServer().getPlayer(deathTargetId);
            if (target != null && target.isOnline()) {
                captureNextDeathDrops = true;
                lastDeathDrops = List.of();
                target.setHealth(0.0);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("deadAfter", target.isDead() || target.getHealth() <= 0.0);
                r.put("drops", lastDeathDrops);
                lastDeathOnClickResult = r;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDeathMonitor(PlayerDeathEvent event) {
        if (captureNextDeathDrops) {
            captureNextDeathDrops = false;
            List<String> list = new ArrayList<>();
            for (ItemStack s : event.getDrops()) {
                if (s != null && !s.getType().isAir()) {
                    list.add(s.getType().name() + ":" + s.getAmount());
                }
            }
            lastDeathDrops = list;
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        permissions.remove(e.getPlayer().getUniqueId());
    }

    private Player requireOnline(String name) {
        Player target = getServer().getPlayerExact(name);
        if (target == null) {
            throw new IllegalArgumentException("online player required: " + name);
        }
        return target;
    }

    private Path playerDat(UUID id) {
        return PlayerDataFiles.directory(getServer()).resolve(id + ".dat");
    }

    private UUID resolveUuid(String name) {
        Player online = getServer().getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer off = getServer().getOfflinePlayer(name);
        return off.getUniqueId();
    }

    private static Map<String, Object> describePlayerSlots(Player p) {
        PlayerInventory inv = p.getInventory();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hotbar0", summarize(inv.getItem(0)));
        m.put("hotbar1", summarize(inv.getItem(1)));
        m.put("storage9", summarize(inv.getItem(9)));
        m.put("storage35", summarize(inv.getItem(35)));
        m.put("boots", summarize(inv.getBoots()));
        m.put("leggings", summarize(inv.getLeggings()));
        m.put("chestplate", summarize(inv.getChestplate()));
        m.put("helmet", summarize(inv.getHelmet()));
        m.put("offhand", summarize(inv.getItemInOffHand()));
        m.put("ec0", summarize(p.getEnderChest().getItem(0)));
        m.put("ec25", summarize(p.getEnderChest().getItem(25)));
        m.put("ec26", summarize(p.getEnderChest().getItem(26)));
        Map<String, String> allNonEmptyInv = new LinkedHashMap<>();
        for (int i = 0; i <= 35; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && !s.getType().isAir()) {
                allNonEmptyInv.put(String.valueOf(i), summarize(s));
            }
        }
        m.put("allInv", allNonEmptyInv);
        m.put("allEc", describeInventory(p.getEnderChest()));
        return m;
    }

    private static Map<String, String> describeInventory(Inventory inv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && !s.getType().isAir()) {
                m.put(String.valueOf(i), summarize(s));
            }
        }
        return m;
    }

    private static Map<String, String> summarizeSlotArray(ItemStack[] slots) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < slots.length; i++) {
            ItemStack s = slots[i];
            if (s != null && !s.getType().isAir()) {
                m.put(String.valueOf(i), summarize(s));
            }
        }
        return m;
    }

    private static Map<String, String> describePdc(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : List.of(
                PlayerDataReceiptProof.MAIL_RECEIPTS,
                PlayerDataReceiptProof.KIT_RECEIPTS,
                "allium:s24_proof",
                "inspgate:marker")) {
            NamespacedKey nk = NamespacedKey.fromString(key);
            if (nk != null && pdc.has(nk, PersistentDataType.STRING)) {
                out.put(key, pdc.get(nk, PersistentDataType.STRING));
            }
        }
        return out;
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
            return "[" + String.join(",", c.stream().map(InspectGate::json).toList()) + "]";
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
