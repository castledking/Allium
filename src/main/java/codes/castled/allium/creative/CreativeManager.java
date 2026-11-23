package codes.castled.allium.creative;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.creative.CreativeInventoryPolicy.Group;
import codes.castled.allium.mail.MailItemCodec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.tool.BukkitParticles;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The creative manager (legacy Allium's {@code CreativeManager}, rebuilt).
 *
 * <p><b>Restrictions.</b> A player in creative mode needs {@code allium.gamemode.creative.<action>}
 * to place, break, interact, take items from the creative menu ({@code use}), drop, pick up, open
 * containers, and use spawn eggs; {@code .blacklist} bypasses {@code creative-manager.blacklist}.
 * Every node defaults to op.
 *
 * <p><b>Separate inventories.</b> Creative and survival/adventure inventories are kept apart unless
 * the player holds {@code allium.gamemode.creative.inventory}. The inventory not in use is stored in
 * the player's own persistent data, so it is saved in the same player file, at the same moment, as
 * the inventory in use: a crash can never keep one and lose the other, and no database write sits
 * between taking items and storing them. A swap is prepared (encoded, and the stored inventory
 * decoded) before the gamemode changes; if either fails, the change is cancelled and nothing moves.
 * The swap itself happens at MONITOR, once no plugin can cancel the change any more.
 */
public final class CreativeManager implements Listener {

    public static final String PERMISSION_PREFIX = "allium.gamemode.creative.";
    public static final String PERMISSION_INVENTORY = PERMISSION_PREFIX + "inventory";
    public static final String PERMISSION_SPECTATOR_TELEPORT = "allium.gamemode.spectator.teleport";
    public static final String PERMISSION_ALERTS = "allium.alerts";

    private static final Set<String> CONTAINER_TYPES = Set.of("CHEST", "BARREL", "DISPENSER", "DROPPER", "HOPPER",
            "FURNACE", "BLAST_FURNACE", "SMOKER", "BREWING", "ENCHANTING", "ANVIL", "GRINDSTONE", "LOOM", "CARTOGRAPHY",
            "SMITHING", "SMITHING_NEW", "STONECUTTER", "BEACON", "SHULKER_BOX", "WORKBENCH", "ENDER_CHEST", "CRAFTER",
            "LECTERN", "CHISELED_BOOKSHELF", "DECORATED_POT", "MERCHANT");

    private final PluginStart plugin;
    private final codes.castled.allium.item.SlotCodec slots = new codes.castled.allium.item.SlotCodec();
    private final NamespacedKey groupKey;
    private final NamespacedKey lastModeKey;
    private final Map<Group, NamespacedKey> stashKeys = new HashMap<>();
    private final NamespacedKey creativeItemKey;
    private final NamespacedKey armorKey;
    private final NamespacedKey savedArmorKey;
    private volatile CreativeItems.Settings items;
    @Nullable
    private volatile CreativePlacedBlocks placedBlocks;
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();
    /** Swaps prepared at HIGHEST and applied at MONITOR, by player, for the event being dispatched. */
    private final Map<UUID, PreparedSwap> prepared = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private volatile Settings settings;

    record Settings(boolean separateInventories, boolean restoreOnJoin, boolean audit, long cooldownMillis,
                    Set<Material> blockedBlocks, Set<EntityType> blockedEntities, Set<Material> blockedItems,
                    List<String> blockedCommands, boolean protectTnt) {
        static Settings read(@Nullable ConfigurationSection section, java.util.logging.Logger log) {
            ConfigurationSection c = section;
            Set<Material> blocks = EnumSet.noneOf(Material.class);
            Set<EntityType> entities = EnumSet.noneOf(EntityType.class);
            Set<Material> items = EnumSet.noneOf(Material.class);
            List<String> commands = new ArrayList<>();
            if (c != null) {
                for (String name : c.getStringList("blacklist.blocks")) {
                    Material m = Material.matchMaterial(name);
                    if (m == null) log.warning("creative-manager.blacklist.blocks: unknown block " + name);
                    else blocks.add(m);
                }
                for (String name : c.getStringList("blacklist.entities")) {
                    EntityType type = entityType(name);
                    if (type == null) log.warning("creative-manager.blacklist.entities: unknown entity " + name);
                    else entities.add(type);
                }
                for (String name : c.getStringList("blacklist.items")) {
                    Material m = Material.matchMaterial(name);
                    if (m == null) log.warning("creative-manager.blacklist.items: unknown item " + name);
                    else items.add(m);
                }
                for (String raw : c.getStringList("blacklist.commands")) {
                    String command = commandLine(raw);
                    if (!command.isEmpty()) commands.add(command);
                }
            }
            return new Settings(c == null || c.getBoolean("separate-inventories", true),
                    c == null || c.getBoolean("restore-gamemode-on-join", true),
                    c == null || c.getBoolean("audit", true),
                    Math.max(0, c == null ? 10 : c.getInt("message-cooldown-seconds", 10)) * 1000L,
                    Set.copyOf(blocks), Set.copyOf(entities), Set.copyOf(items), List.copyOf(commands),
                    c == null || c.getBoolean("protect-tnt", true));
        }
    }

    /** A command line without its slash, namespace and extra spaces, lowercase: "/minecraft:Give  Bob" is "give bob". */
    static String commandLine(@Nullable String raw) {
        if (raw == null) return "";
        String line = raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (line.startsWith("/")) line = line.substring(1);
        int space = line.indexOf(' ');
        String label = space < 0 ? line : line.substring(0, space);
        int colon = label.lastIndexOf(':');
        return colon < 0 ? line : line.substring(colon + 1);
    }

    /** Whether a typed command is one of {@code blocked}: the same command, or it with more arguments. */
    static boolean commandBlocked(List<String> blocked, String typed) {
        String line = commandLine(typed);
        for (String entry : blocked) {
            if (line.equals(entry) || line.startsWith(entry + " ")) return true;
        }
        return false;
    }

    /** Whether an item places a container (chest, shulker box, barrel...) that already holds items. */
    static boolean filledContainer(@Nullable ItemStack item) {
        return item != null && item.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta meta
                && meta.hasBlockState() && meta.getBlockState() instanceof org.bukkit.block.Container container
                && !container.getInventory().isEmpty();
    }

    /** The creative player who lit TNT: directly, or by shooting the burning projectile. */
    @Nullable
    static Player primingPlayer(@Nullable Entity primer) {
        if (primer instanceof Player player) return player;
        if (primer instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    private record PreparedSwap(GameMode to, Group from, Group target, byte[] outgoing, ItemStack[] incoming) {
    }

    public CreativeManager(PluginStart plugin) {
        this.plugin = plugin;
        this.groupKey = new NamespacedKey(plugin, "creative_group");
        this.lastModeKey = new NamespacedKey(plugin, "last_gamemode");
        this.creativeItemKey = new NamespacedKey(plugin, "creative_item");
        this.armorKey = new NamespacedKey(plugin, "creative_armor");
        this.savedArmorKey = new NamespacedKey(plugin, "creative_saved_armor");
        for (Group group : Group.values()) {
            stashKeys.put(group, new NamespacedKey(plugin, "creative_stash_" + group.id()));
        }
        reload();
    }

    public void reload() {
        settings = Settings.read(plugin.getConfig().getConfigurationSection("creative-manager"), plugin.getLogger());
        items = CreativeItems.Settings.read(plugin.getConfig().getConfigurationSection("creative-manager"), plugin.getLogger()::warning);
        if (placedBlocks != null) placedBlocks.reload();
    }

    public void enable() {
        closed = false;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        placedBlocks = new CreativePlacedBlocks(plugin);
        placedBlocks.enable();
        hookPickBlock();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UtilitySupport.onOwner(plugin, player, () -> {
                if (!closed) reconcile(player);
            });
        }
    }

    public void disable() {
        closed = true;
        HandlerList.unregisterAll(this);
        if (placedBlocks != null) placedBlocks.disable();
        prepared.clear();
        lastMessage.clear();
    }

    // -- Separate inventories ----------------------------------------------------------------------

    private boolean separates(Player player) {
        return settings.separateInventories() && !player.hasPermission(PERMISSION_INVENTORY);
    }

    @Nullable
    private Group activeGroup(Player player) {
        return Group.parse(player.getPersistentDataContainer().get(groupKey, PersistentDataType.STRING)).orElse(null);
    }

    private void markActive(Player player, Group group) {
        player.getPersistentDataContainer().set(groupKey, PersistentDataType.STRING, group.id());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void prepareSwap(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        prepared.remove(player.getUniqueId());
        if (!player.isOnline() || !separates(player)) {
            return;
        }
        Optional<Group> target = CreativeInventoryPolicy.swapTo(activeGroup(player), player.getGameMode().name(),
                event.getNewGameMode().name());
        if (target.isEmpty()) {
            return;
        }
        Group recorded = activeGroup(player);
        Group from = recorded != null ? recorded : CreativeInventoryPolicy.groupOf(player.getGameMode().name()).orElseThrow();
        // Items in a crafting grid or on the cursor go back into the inventory first, so they are
        // stored with the inventory they came from (overflow drops, as on any close).
        player.closeInventory();
        try {
            byte[] outgoing = encode(player.getInventory().getContents());
            ItemStack[] incoming = decode(player.getPersistentDataContainer().get(stashKeys.get(target.get()),
                    PersistentDataType.BYTE_ARRAY), player.getInventory().getContents().length);
            prepared.put(player.getUniqueId(), new PreparedSwap(event.getNewGameMode(), from, target.get(), outgoing, incoming));
        } catch (IOException | RuntimeException failure) {
            event.setCancelled(true);
            plugin.getLogger().warning("Refused " + player.getName() + "'s gamemode change to " + event.getNewGameMode()
                    + ": their " + target.get().id() + " inventory could not be swapped (" + failure.getMessage() + ")");
            notify(event.getPlayer(), Messages.CREATIVE_INVENTORY_FAILED, "{group}", target.get().id());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void applySwap(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        PreparedSwap swap = prepared.remove(player.getUniqueId());
        if (event.isCancelled()) {
            return;
        }
        swap(player, swap, event.getNewGameMode());
        // The player is still in the old mode until the event returns.
        afterSwitch(player, player.getGameMode(), event.getNewGameMode());
    }

    private void swap(Player player, @Nullable PreparedSwap swap, GameMode to) {
        if (swap == null || swap.to() != to) {
            // No swap: still record which inventory the player holds in the new mode.
            CreativeInventoryPolicy.groupOf(to.name()).ifPresent(group -> {
                if (activeGroup(player) == null || !separates(player)) markActive(player, group);
            });
            return;
        }
        PersistentDataContainer data = player.getPersistentDataContainer();
        PlayerInventory inventory = player.getInventory();
        data.set(stashKeys.get(swap.from()), PersistentDataType.BYTE_ARRAY, swap.outgoing());
        inventory.setContents(swap.incoming());
        data.remove(stashKeys.get(swap.target()));
        markActive(player, swap.target());
        player.updateInventory();
        notify(player, Messages.CREATIVE_INVENTORY_SWAPPED, "{group}", swap.target().id());
    }

    /** Potion effects and the creative armour, when a player enters or leaves creative. */
    private void afterSwitch(Player player, GameMode from, GameMode to) {
        boolean entering = to == GameMode.CREATIVE && from != GameMode.CREATIVE;
        boolean leaving = from == GameMode.CREATIVE && to != GameMode.CREATIVE;
        if (!entering && !leaving) return;
        if (items.clearEffects() && !can(player, "keep-effects")) {
            for (var effect : List.copyOf(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        }
        if (leaving) takeOffCreativeArmor(player);
        if (entering && items.armorEnabled() && !can(player, "own-armor")) putOnCreativeArmor(player);
    }

    private void putOnCreativeArmor(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (!separates(player)) {
            // One shared inventory: keep the player's own armour to give back when they leave creative.
            ItemStack[] own = inventory.getArmorContents();
            boolean any = false;
            for (ItemStack piece : own) any |= piece != null && !piece.getType().isAir() && !CreativeItems.creativeArmor(piece, armorKey);
            if (any) {
                try {
                    player.getPersistentDataContainer().set(savedArmorKey, PersistentDataType.BYTE_ARRAY, encode(own));
                } catch (IOException | RuntimeException failure) {
                    plugin.getLogger().warning("Kept " + player.getName() + "'s own armour in creative: it could not be saved ("
                            + failure.getMessage() + ")");
                    return;
                }
            }
        }
        // Armour slots run boots, leggings, chestplate, helmet.
        List<CreativeItems.ArmorPiece> pieces = items.armor();
        inventory.setArmorContents(new ItemStack[]{CreativeItems.armorItem(pieces.get(3), armorKey),
                CreativeItems.armorItem(pieces.get(2), armorKey), CreativeItems.armorItem(pieces.get(1), armorKey),
                CreativeItems.armorItem(pieces.get(0), armorKey)});
        player.updateInventory();
    }

    private void takeOffCreativeArmor(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (CreativeItems.creativeArmor(contents[i], armorKey)) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) inventory.setContents(contents);
        byte[] saved = player.getPersistentDataContainer().get(savedArmorKey, PersistentDataType.BYTE_ARRAY);
        if (saved != null) {
            try {
                ItemStack[] own = decode(saved, 4);
                ItemStack[] worn = inventory.getArmorContents();
                for (int i = 0; i < worn.length && i < own.length; i++) {
                    if (worn[i] == null || worn[i].getType().isAir()) worn[i] = own[i];
                }
                inventory.setArmorContents(worn);
                player.getPersistentDataContainer().remove(savedArmorKey);
            } catch (IOException | RuntimeException failure) {
                plugin.getLogger().warning("Could not give back " + player.getName() + "'s own armour: " + failure.getMessage());
            }
        }
        if (changed || saved != null) player.updateInventory();
    }

    /** Paper 1.21.4+ handles pick block on the server ({@code PlayerPickItemEvent}); older servers leave it to the client. */
    @SuppressWarnings("unchecked")
    private void hookPickBlock() {
        Class<? extends org.bukkit.event.Event> type;
        try {
            type = (Class<? extends org.bukkit.event.Event>) Class.forName("io.papermc.paper.event.player.PlayerPickItemEvent");
        } catch (ClassNotFoundException | LinkageError absent) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvent(type, this, EventPriority.HIGH, (listener, event) -> {
            if (!(event instanceof org.bukkit.event.player.PlayerEvent playerEvent) || !(event instanceof org.bukkit.event.Cancellable cancellable)) return;
            Player player = playerEvent.getPlayer();
            if (!creative(player) || can(player, "pickblock")) return;
            cancellable.setCancelled(true);
            deny(player, "use pick block", null);
        }, plugin, true);
    }

    /**
     * On join (and when the module starts) the recorded inventory and the gamemode can disagree:
     * the server forced a gamemode, or the mode changed while Allium was not running.
     */
    private void reconcile(Player player) {
        Optional<Group> current = CreativeInventoryPolicy.groupOf(player.getGameMode().name());
        if (current.isEmpty()) {
            return;
        }
        Group active = activeGroup(player);
        if (!separates(player) || active == null) {
            markActive(player, current.get());
            return;
        }
        if (active == current.get()) {
            return;
        }
        try {
            byte[] outgoing = encode(player.getInventory().getContents());
            ItemStack[] incoming = decode(player.getPersistentDataContainer().get(stashKeys.get(current.get()),
                    PersistentDataType.BYTE_ARRAY), player.getInventory().getContents().length);
            player.getPersistentDataContainer().set(stashKeys.get(active), PersistentDataType.BYTE_ARRAY, outgoing);
            player.getInventory().setContents(incoming);
            player.getPersistentDataContainer().remove(stashKeys.get(current.get()));
            markActive(player, current.get());
            player.updateInventory();
            plugin.getLogger().info("Loaded " + player.getName() + "'s " + current.get().id() + " inventory for their "
                    + player.getGameMode().name().toLowerCase(Locale.ROOT) + " mode on join.");
        } catch (IOException | RuntimeException failure) {
            // Leave everything where it is; the next gamemode change tries again.
            plugin.getLogger().warning("Could not load " + player.getName() + "'s " + current.get().id()
                    + " inventory on join: " + failure.getMessage());
        }
    }

    private byte[] encode(ItemStack[] contents) throws IOException {
        String text = slots.encode(contents);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private ItemStack[] decode(byte @Nullable [] stored, int size) throws IOException {
        if (stored == null) {
            return new ItemStack[size];
        }
        String text;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(stored))) {
            text = new String(gzip.readNBytes(MailItemCodec.MAX_CHARS * 4 + 4096), StandardCharsets.UTF_8);
        }
        return slots.decode(text, size);
    }

    // -- Joining, quitting, audits -----------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        reconcile(player);
        GameMode last = parseMode(player.getPersistentDataContainer().get(lastModeKey, PersistentDataType.STRING));
        GameMode joined = player.getGameMode();
        // Staff keep creative or spectator across sessions where the server resets gamemodes
        // (force-gamemode), as long as they still hold that mode's node.
        if (settings.restoreOnJoin() && last != null && (last == GameMode.CREATIVE || last == GameMode.SPECTATOR)
                && (joined == GameMode.SURVIVAL || joined == GameMode.ADVENTURE)
                && player.hasPermission("allium.gamemode." + last.name().toLowerCase(Locale.ROOT))) {
            player.setGameMode(last);
            plugin.getLogger().info("Restored " + player.getName() + " to " + last.name().toLowerCase(Locale.ROOT)
                    + " from their last session (joined in " + joined.name().toLowerCase(Locale.ROOT) + ").");
        } else if (joined == GameMode.CREATIVE || joined == GameMode.SPECTATOR) {
            audit(player, "joined in", joined, joined);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        player.getPersistentDataContainer().set(lastModeKey, PersistentDataType.STRING, player.getGameMode().name());
        lastMessage.remove(player.getUniqueId());
        prepared.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void auditChange(PlayerGameModeChangeEvent event) {
        GameMode to = event.getNewGameMode();
        if (to == GameMode.CREATIVE || to == GameMode.SPECTATOR) {
            audit(event.getPlayer(), "switched to", event.getPlayer().getGameMode(), to);
        }
    }

    /** Alerts staff when a player is in creative or spectator without that mode's node. */
    private void audit(Player player, String cause, GameMode from, GameMode to) {
        String mode = to.name().toLowerCase(Locale.ROOT);
        if (!settings.audit() || player.hasPermission("allium.gamemode." + mode)) {
            return;
        }
        String shownMode = codes.castled.allium.command.essentials.UtilitySupport.titleCase(mode);
        // Replacements apply in order: the node inside the text ("allium.gamemode.{to}") stays
        // lowercase before {to} becomes the shown "Creative", in stock and customized text alike.
        String[] replacements = {"allium.gamemode.{to}", "allium.gamemode." + mode,
                "{player}", player.getName(), "{cause}", cause + " " + shownMode,
                "{from}", codes.castled.allium.command.essentials.UtilitySupport.titleCase(from.name()), "{to}", shownMode};
        plugin.getLogger().warning("[GamemodeAudit] " + player.getName() + " " + cause + " " + mode + " (from "
                + from.name().toLowerCase(Locale.ROOT) + ") without allium.gamemode." + mode);
        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            UtilitySupport.onOwner(plugin, staff, () -> {
                if (!closed && staff.hasPermission(PERMISSION_ALERTS)) {
                    plugin.getMessageManager().send(staff, Messages.CREATIVE_AUDIT, replacements);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectatorTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                && !event.getPlayer().hasPermission(PERMISSION_SPECTATOR_TELEPORT)) {
            event.setCancelled(true);
            notify(event.getPlayer(), Messages.SPECTATOR_TELEPORT_DENIED);
        }
    }

    // -- Restrictions ------------------------------------------------------------------------------

    private static boolean creative(Player player) {
        return player.getGameMode() == GameMode.CREATIVE;
    }

    private static boolean can(Player player, String action) {
        return player.hasPermission(PERMISSION_PREFIX + action);
    }

    private void deny(Player player, String action, @Nullable Location at) {
        fizzle(at != null ? at : player.getLocation());
        notify(player, Messages.CREATIVE_RESTRICTED, "{action}", action);
    }

    private void denyBlacklisted(Player player, String target, @Nullable Location at) {
        fizzle(at != null ? at : player.getLocation());
        notify(player, Messages.CREATIVE_BLACKLISTED, "{target}", target);
    }

    /** One message per player per cooldown, so held-down clicks do not flood chat. */
    private void notify(Player player, Messages message, String... replacements) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (message != Messages.CREATIVE_INVENTORY_SWAPPED && message != Messages.CREATIVE_INVENTORY_FAILED
                && last != null && now - last < settings.cooldownMillis()) {
            return;
        }
        if (message != Messages.CREATIVE_INVENTORY_SWAPPED) {
            lastMessage.put(player.getUniqueId(), now);
        }
        plugin.getMessageManager().send(player, message, replacements);
    }

    private static void fizzle(Location at) {
        if (at.getWorld() == null) return;
        BukkitParticles.resolve("dust").ifPresent(particle -> {
            try {
                at.getWorld().spawnParticle(particle, at.clone().add(0.5, 0.5, 0.5), 15, 0.5, 0.5, 0.5, 0,
                        new Particle.DustOptions(Color.RED, 1.0f));
            } catch (RuntimeException ignored) {
                // Cosmetic only.
            }
        });
    }

    private static String pretty(String name) {
        return codes.castled.allium.command.essentials.UtilitySupport.titleCase(name);
    }

    @Nullable
    private static EntityType entityType(String name) {
        String normalized = name.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        try {
            return EntityType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private static EntityType spawnEggType(@Nullable ItemStack item) {
        if (item == null || !item.getType().name().endsWith("_SPAWN_EGG")) {
            return null;
        }
        String name = item.getType().name();
        EntityType type = entityType(name.substring(0, name.length() - "_SPAWN_EGG".length()));
        if (type == null && name.equals("MOOSHROOM_SPAWN_EGG")) type = entityType("MUSHROOM_COW");
        return type;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!creative(player)) return;
        if (!can(player, "place")) {
            event.setCancelled(true);
            deny(player, "place that", event.getBlock().getLocation());
        } else if (settings.blockedBlocks().contains(event.getBlock().getType()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, pretty(event.getBlock().getType().name()), event.getBlock().getLocation());
        } else if (!can(player, "filled-containers") && filledContainer(event.getItemInHand())) {
            // A chest or shulker box copied with its contents (pick block with Ctrl) would bring items into the world.
            event.setCancelled(true);
            deny(player, "place containers that hold items", event.getBlock().getLocation());
        }
    }

    /** TNT lit by a creative player, with any igniter (flint and steel, fire charge, a burning arrow...). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrime(TNTPrimeEvent event) {
        Player player = primingPlayer(event.getPrimingEntity());
        if (player == null || !settings.protectTnt() || !creative(player) || can(player, "blacklist")) return;
        event.setCancelled(true);
        deny(player, "light TNT", event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (creative(player) && settings.blockedItems().contains(event.getBucket()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, pretty(event.getBucket().name()), event.getBlockClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!creative(player) || settings.blockedCommands().isEmpty() || can(player, "blacklist")) return;
        if (commandBlocked(settings.blockedCommands(), event.getMessage())) {
            event.setCancelled(true);
            String line = commandLine(event.getMessage());
            denyBlacklisted(player, "/" + (line.contains(" ") ? line.substring(0, line.indexOf(' ')) : line), null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!creative(player)) return;
        if (!can(player, "break")) {
            event.setCancelled(true);
            deny(player, "break that", event.getBlock().getLocation());
        } else if (settings.blockedBlocks().contains(event.getBlock().getType()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, pretty(event.getBlock().getType().name()), event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!creative(player) || event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY
                && event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        Block block = event.getClickedBlock();
        ItemStack item = event.getItem();
        boolean right = event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR;
        EntityType egg = spawnEggType(item);
        if (egg != null && right) {
            if (!can(player, "spawn")) {
                event.setCancelled(true);
                deny(player, "spawn that", block == null ? null : block.getLocation());
                return;
            }
            if (settings.blockedEntities().contains(egg) && !can(player, "blacklist")) {
                event.setCancelled(true);
                denyBlacklisted(player, pretty(egg.name()), block == null ? null : block.getLocation());
                return;
            }
        }
        if (right && item != null && settings.blockedItems().contains(item.getType()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, pretty(item.getType().name()), block == null ? null : block.getLocation());
            return;
        }
        if (block != null && settings.blockedBlocks().contains(block.getType()) && !can(player, "blacklist")
                && event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            denyBlacklisted(player, pretty(block.getType().name()), block.getLocation());
            return;
        }
        if (can(player, "interact")) return;
        if (right && item != null && (item.getType() == Material.FIREWORK_ROCKET)) {
            event.setCancelled(true);
            deny(player, "use fireworks", null);
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null && interactable(block.getType())
                && !(player.isSneaking() && item != null && !item.getType().isAir())) {
            event.setCancelled(true);
            deny(player, "interact with " + pretty(block.getType().name()), block.getLocation());
            return;
        }
        if (event.getAction() == Action.PHYSICAL && block != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Blocks whose right-click does something: containers, redstone inputs, doors, beds and the like.
     * By name, so the check is the same on every server version.
     */
    static boolean interactable(Material material) {
        String name = material.name();
        // Signs are left out, as in legacy Allium: Allium's action signs work for creative staff.
        for (String part : List.of("CHEST", "BARREL", "SHULKER_BOX", "DOOR", "FENCE_GATE", "BUTTON", "_BED", "ANVIL",
                "CANDLE_CAKE", "POTTED_", "CAULDRON", "COMMAND_BLOCK")) {
            if (name.contains(part)) return true;
        }
        return Set.of("LEVER", "DISPENSER", "DROPPER", "HOPPER", "FURNACE", "BLAST_FURNACE", "SMOKER", "BREWING_STAND",
                "BEACON", "LOOM", "STONECUTTER", "GRINDSTONE", "CARTOGRAPHY_TABLE", "ENCHANTING_TABLE", "SMITHING_TABLE",
                "CRAFTING_TABLE", "CRAFTER", "REPEATER", "COMPARATOR", "DAYLIGHT_DETECTOR", "NOTE_BLOCK", "JUKEBOX",
                "STRUCTURE_BLOCK", "JIGSAW", "LECTERN", "BELL", "RESPAWN_ANCHOR", "LODESTONE", "CAKE", "FLOWER_POT",
                "DECORATED_POT", "CHISELED_BOOKSHELF", "COMPOSTER", "BEEHIVE", "BEE_NEST", "CAMPFIRE", "SOUL_CAMPFIRE",
                "VAULT", "TRIAL_SPAWNER", "SPAWNER", "DRAGON_EGG").contains(name);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (creative(player) && !can(player, "interact")) {
            event.setCancelled(true);
            deny(player, event.getItem().getType() == Material.POTION ? "drink that" : "eat that", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!creative(player)) return;
        ItemStack hand = player.getInventory().getItem(event.getHand());
        EntityType egg = spawnEggType(hand);
        if (egg != null && (!can(player, "spawn") || settings.blockedEntities().contains(egg) && !can(player, "blacklist"))) {
            event.setCancelled(true);
            deny(player, "spawn that", event.getRightClicked().getLocation());
            return;
        }
        if (can(player, "interact")) return;
        Entity clicked = event.getRightClicked();
        if (clicked instanceof ItemFrame || clicked instanceof LivingEntity || clicked.getType().name().contains("MINECART")
                || clicked.getType().name().contains("BOAT") || clicked.getType().name().contains("RAFT")) {
            event.setCancelled(true);
            deny(player, "interact with that " + pretty(clicked.getType().name()), clicked.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteractAt(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();
        if (creative(player) && !can(player, "interact") && event.getRightClicked().getType() == EntityType.ARMOR_STAND) {
            event.setCancelled(true);
            deny(player, "interact with armor stands", event.getRightClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || !creative(player)) return;
        if (settings.blockedEntities().contains(event.getEntityType()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, pretty(event.getEntityType().name()), event.getBlock().getLocation());
        } else if (!can(player, "place")) {
            event.setCancelled(true);
            deny(player, "place that", event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || !creative(player)) return;
        if (settings.blockedEntities().contains(event.getEntity().getType()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, pretty(event.getEntity().getType().name()), event.getBlock().getLocation());
        } else if (!can(player, "place") || !can(player, "interact")) {
            event.setCancelled(true);
            deny(player, "place that", event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player && creative(player) && (!can(player, "interact") || !can(player, "break"))) {
            event.setCancelled(true);
            deny(player, "break that", event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player && creative(player) && !can(player, "interact")) {
            event.setCancelled(true);
            deny(player, "shoot that", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && creative(player) && !can(player, "interact")) {
            event.setCancelled(true);
            deny(player, "throw " + pretty(event.getEntity().getType().name()) + "s", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (creative(player) && !can(player, "interact") && event.getState() == PlayerFishEvent.State.FISHING) {
            event.setCancelled(true);
            deny(player, "use fishing rods", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && creative(player) && !can(player, "interact")) {
            event.setCancelled(true);
            deny(player, "hurt that " + pretty(event.getEntity().getType().name()), event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreativeMenu(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !creative(player)) return;
        ItemStack taken = event.getCursor();
        if (can(player, "use")) {
            boolean armorSlot = event.getSlotType() == InventoryType.SlotType.ARMOR;
            // The creative armour stays on: it cannot be taken off, nor put anywhere else.
            if (armorSlot && CreativeItems.creativeArmor(event.getCurrentItem(), armorKey) && !CreativeItems.creativeArmor(taken, armorKey)
                    || !armorSlot && CreativeItems.creativeArmor(taken, armorKey)) {
                refuse(event, player);
                deny(player, "take off the creative armour", null);
                return;
            }
            if (taken == null || taken.getType().isAir()) return;
            if (settings.blockedItems().contains(taken.getType()) && !can(player, "blacklist")) {
                refuse(event, player);
                denyBlacklisted(player, pretty(taken.getType().name()), null);
                return;
            }
            boolean anyItem = can(player, "any-item");
            if (!anyItem && items.refuseEnchanted() && CreativeItems.enchanted(taken)) {
                refuse(event, player);
                deny(player, "take enchanted items from the creative menu", null);
                return;
            }
            if (!anyItem && items.refusePotions() && CreativeItems.hasEffects(taken)) {
                refuse(event, player);
                deny(player, "take potions with effects from the creative menu", null);
                return;
            }
            ItemStack result = taken;
            if (!anyItem && items.sanitize()) result = CreativeItems.sanitize(result, Set.of(creativeItemKey, armorKey));
            if (items.loreEnabled() && !can(player, "untagged") && !CreativeItems.tagged(result, creativeItemKey)
                    && !CreativeItems.creativeArmor(result, armorKey)) {
                result = CreativeItems.tag(result, player, items.loreLines(), creativeItemKey);
            }
            if (result != taken) event.setCursor(result);
            return;
        }
        ItemStack placed = event.getCursor();
        // Clearing a slot (or the trash slot) is allowed; putting any item anywhere is not, since
        // the creative client cannot tell the server whether it came from the menu.
        if (placed == null || placed.getType().isAir()) return;
        refuse(event, player);
        deny(player, "take items from the creative menu", null);
    }

    /** Refuses a creative inventory change and resynchronises the client, which already shows it. */
    private void refuse(InventoryCreativeEvent event, Player player) {
        event.setCancelled(true);
        BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
            if (!closed && player.isOnline() && creative(player)) {
                player.setItemOnCursor(null);
                player.updateInventory();
            }
        }, () -> {}, 0L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorClick(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent) return;
        if (event.getWhoClicked() instanceof Player player && creative(player) && !can(player, "interact")
                && event.getSlotType() == InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
            deny(player, "equip armor", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (creative(player) && !can(player, "drop")) {
            event.setCancelled(true);
            deny(player, "drop that", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && creative(player) && !can(player, "pickup")) {
            event.setCancelled(true);
            deny(player, "pick that up", event.getItem().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !creative(player) || can(player, "container")) return;
        if (CONTAINER_TYPES.contains(event.getInventory().getType().name())) {
            event.setCancelled(true);
            Location at = event.getInventory().getLocation();
            deny(player, "use this container", at);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onContainerClick(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent) return;
        if (!(event.getWhoClicked() instanceof Player player) || !creative(player) || can(player, "container")) return;
        if (event.getClickedInventory() != null && event.getClickedInventory() != player.getInventory()
                && CONTAINER_TYPES.contains(event.getClickedInventory().getType().name())) {
            event.setCancelled(true);
            deny(player, "use this container", null);
        }
    }

    @Nullable
    private static GameMode parseMode(@Nullable String name) {
        if (name == null) return null;
        try {
            return GameMode.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
