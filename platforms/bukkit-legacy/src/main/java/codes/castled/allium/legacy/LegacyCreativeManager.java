package codes.castled.allium.legacy;

import codes.castled.allium.ServerVersion;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
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
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.bukkit.material.Bed;
import org.bukkit.material.Door;
import org.bukkit.material.MaterialData;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Java 8 / 1.12.2-1.19.4 port of Allium's CreativeManager: per-action creative restrictions,
 * separate creative and survival/adventure inventories, creative armor, creative-menu item lore
 * and sanitization, placed-block loot protection, gamemode restore on join, gamemode audits,
 * and spectator teleport protection.
 */
final class LegacyCreativeManager implements Listener {

    static final String PERMISSION_PREFIX = "allium.gamemode.creative.";
    static final String PERMISSION_INVENTORY = PERMISSION_PREFIX + "inventory";
    static final String PERMISSION_LOOT = PERMISSION_PREFIX + "loot";
    static final String PERMISSION_SPECTATOR_TELEPORT = "allium.gamemode.spectator.teleport";
    static final String PERMISSION_ALERTS = "allium.alerts";

    private static final String ARMOR_TAG = "\u00a78\u00a7a\u00a7l\u00a7a\u00a7r";
    private static final String LORE_TAG = "\u00a78\u00a7a\u00a7l\u00a7c\u00a7r";
    private static final Color ALLIUM_PINK = Color.fromRGB(0xFF55FF);

    private static final Set<String> CONTAINER_TYPES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "CHEST", "BARREL", "DISPENSER", "DROPPER", "HOPPER", "FURNACE", "BLAST_FURNACE", "SMOKER",
            "BREWING", "ENCHANTING", "ANVIL", "GRINDSTONE", "LOOM", "CARTOGRAPHY", "SMITHING",
            "SMITHING_NEW", "STONECUTTER", "BEACON", "SHULKER_BOX", "WORKBENCH", "ENDER_CHEST",
            "CRAFTER", "LECTERN", "CHISELED_BOOKSHELF", "DECORATED_POT", "MERCHANT")));

    private static final Set<String> INTERACTABLE_PARTS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "CHEST", "BARREL", "SHULKER_BOX", "DOOR", "FENCE_GATE", "BUTTON", "_BED", "ANVIL",
            "CANDLE_CAKE", "POTTED_", "CAULDRON", "COMMAND_BLOCK")));

    private static final Set<String> INTERACTABLE_EXACT = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "LEVER", "DISPENSER", "DROPPER", "HOPPER", "FURNACE", "BURNING_FURNACE", "BLAST_FURNACE", "SMOKER",
            "BREWING_STAND", "BEACON", "LOOM", "STONECUTTER", "GRINDSTONE", "CARTOGRAPHY_TABLE",
            "ENCHANTING_TABLE", "ENCHANTMENT_TABLE", "SMITHING_TABLE", "CRAFTING_TABLE", "WORKBENCH",
            "CRAFTER", "REPEATER", "DIODE_BLOCK_OFF", "DIODE_BLOCK_ON", "COMPARATOR",
            "REDSTONE_COMPARATOR_OFF", "REDSTONE_COMPARATOR_ON", "DAYLIGHT_DETECTOR",
            "DAYLIGHT_DETECTOR_INVERTED", "NOTE_BLOCK", "JUKEBOX", "STRUCTURE_BLOCK", "JIGSAW",
            "LECTERN", "BELL", "RESPAWN_ANCHOR", "LODESTONE", "CAKE", "CAKE_BLOCK", "FLOWER_POT",
            "DECORATED_POT", "CHISELED_BOOKSHELF", "COMPOSTER", "BEEHIVE", "BEE_NEST", "CAMPFIRE",
            "SOUL_CAMPFIRE", "VAULT", "TRIAL_SPAWNER", "SPAWNER", "MOB_SPAWNER", "DRAGON_EGG",
            "BED_BLOCK", "TRAP_DOOR", "IRON_TRAPDOOR")));

    private static final Object RED_DUST_OPTIONS = buildRedDustOptions();
    private static final Method WORLD_SPAWN_PARTICLE_DATA = findMethod(
            World.class, "spawnParticle",
            Particle.class, Location.class, int.class,
            double.class, double.class, double.class, double.class, Object.class);
    private static final Method META_HAS_ATTRIBUTES = findMethod(ItemMeta.class, "hasAttributeModifiers");
    private static final Method META_SET_ATTRIBUTES = findMethodByName(ItemMeta.class, "setAttributeModifiers", 1);
    private static final Method META_GET_PDC = findMethod(ItemMeta.class, "getPersistentDataContainer");
    private static final Method BLOCK_GET_DATA = findMethod(Block.class, "getBlockData");

    enum InventoryGroup {
        SURVIVAL("survival"),
        CREATIVE("creative");

        final String id;

        InventoryGroup(String id) {
            this.id = id;
        }

        static InventoryGroup of(GameMode mode) {
            if (mode == GameMode.CREATIVE) return CREATIVE;
            if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) return SURVIVAL;
            return null;
        }

        static InventoryGroup parse(String raw) {
            if (raw == null) return null;
            for (InventoryGroup g : values()) {
                if (g.id.equalsIgnoreCase(raw.trim())) return g;
            }
            return null;
        }
    }

    static final class ArmorPiece {
        final Material material;
        final String name;
        final List<String> lore;
        final Color color;

        ArmorPiece(Material material, String name, List<String> lore, Color color) {
            this.material = material;
            this.name = name == null ? "" : name;
            this.lore = lore == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(lore));
            this.color = color;
        }
    }

    static final class Settings {
        final boolean separateInventories;
        final boolean restoreOnJoin;
        final boolean audit;
        final long cooldownMillis;
        final boolean protectTnt;
        final Set<Material> blockedBlocks;
        final Set<EntityType> blockedEntities;
        final Set<Material> blockedItems;
        final List<String> blockedCommands;
        final boolean placedBlocksEnabled;
        final boolean placedBlocksDeny;
        final boolean preventMobBuilds;
        final boolean loreEnabled;
        final List<String> loreLines;
        final boolean sanitize;
        final boolean refuseEnchanted;
        final boolean refusePotions;
        final boolean clearEffects;
        final boolean armorEnabled;
        final List<ArmorPiece> armor;

        Settings(
                boolean separateInventories, boolean restoreOnJoin, boolean audit, long cooldownMillis,
                boolean protectTnt, Set<Material> blockedBlocks, Set<EntityType> blockedEntities,
                Set<Material> blockedItems, List<String> blockedCommands,
                boolean placedBlocksEnabled, boolean placedBlocksDeny, boolean preventMobBuilds,
                boolean loreEnabled, List<String> loreLines, boolean sanitize, boolean refuseEnchanted,
                boolean refusePotions, boolean clearEffects, boolean armorEnabled, List<ArmorPiece> armor
        ) {
            this.separateInventories = separateInventories;
            this.restoreOnJoin = restoreOnJoin;
            this.audit = audit;
            this.cooldownMillis = cooldownMillis;
            this.protectTnt = protectTnt;
            this.blockedBlocks = Collections.unmodifiableSet(blockedBlocks);
            this.blockedEntities = Collections.unmodifiableSet(blockedEntities);
            this.blockedItems = Collections.unmodifiableSet(blockedItems);
            this.blockedCommands = Collections.unmodifiableList(blockedCommands);
            this.placedBlocksEnabled = placedBlocksEnabled;
            this.placedBlocksDeny = placedBlocksDeny;
            this.preventMobBuilds = preventMobBuilds;
            this.loreEnabled = loreEnabled;
            this.loreLines = Collections.unmodifiableList(loreLines);
            this.sanitize = sanitize;
            this.refuseEnchanted = refuseEnchanted;
            this.refusePotions = refusePotions;
            this.clearEffects = clearEffects;
            this.armorEnabled = armorEnabled;
            this.armor = Collections.unmodifiableList(armor);
        }
    }

    private static final class PlayerState {
        InventoryGroup activeGroup;
        GameMode lastMode;
        ItemStack[] survivalStash;
        ItemStack[] creativeStash;
        ItemStack[] savedArmor;

        ItemStack[] stash(InventoryGroup group) {
            return group == InventoryGroup.CREATIVE ? creativeStash : survivalStash;
        }

        void setStash(InventoryGroup group, ItemStack[] items) {
            if (group == InventoryGroup.CREATIVE) creativeStash = items;
            else survivalStash = items;
        }
    }

    private static final class PreparedSwap {
        final GameMode to;
        final InventoryGroup from;
        final InventoryGroup target;
        final ItemStack[] outgoing;
        final ItemStack[] incoming;

        PreparedSwap(GameMode to, InventoryGroup from, InventoryGroup target, ItemStack[] outgoing, ItemStack[] incoming) {
            this.to = to;
            this.from = from;
            this.target = target;
            this.outgoing = outgoing;
            this.incoming = incoming;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyConfigManager configManager;
    private final LegacyMessageManager messages;
    private final boolean pre113;
    private final Map<UUID, PlayerState> playerStates = new HashMap<UUID, PlayerState>();
    private final Map<UUID, PreparedSwap> preparedSwaps = new HashMap<UUID, PreparedSwap>();
    private final Map<UUID, Long> lastMessage = new HashMap<UUID, Long>();
    private final Map<String, Map<Long, int[]>> placedBlocks = new HashMap<String, Map<Long, int[]>>();
    private final Set<UUID> creativeFalling = new HashSet<UUID>();
    private volatile Settings settings;
    private boolean dynamicHooksInstalled;
    private boolean placedBlocksDirty;

    LegacyCreativeManager(JavaPlugin plugin, LegacyConfigManager configManager,
                          LegacyMessageManager messages, ServerVersion version) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messages = messages;
        this.pre113 = version != null && !version.isAtLeast(1, 13, 0);
        this.settings = readSettings(configManager.raw());
    }

    void enable() {
        reload();
        loadPlayerStates();
        loadPlacedBlocks();
        hookDynamicEvents();
        if (isActive()) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                reconcile(player);
            }
        }
    }

    void reload() {
        settings = readSettings(configManager.raw());
    }

    void disable() {
        savePlayerStates();
        if (placedBlocksDirty) {
            savePlacedBlocks();
        }
        preparedSwaps.clear();
        lastMessage.clear();
        creativeFalling.clear();
    }

    boolean isActive() {
        return configManager.isModuleEnabled("creative");
    }

    // ==================== Separate Inventories & Gamemode Lifecycle ====================

    private boolean separates(Player player) {
        return settings.separateInventories && !player.hasPermission(PERMISSION_INVENTORY);
    }

    private PlayerState stateOf(UUID id) {
        PlayerState st = playerStates.get(id);
        if (st == null) {
            st = new PlayerState();
            playerStates.put(id, st);
        }
        return st;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void prepareSwap(PlayerGameModeChangeEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        preparedSwaps.remove(player.getUniqueId());
        if (!player.isOnline() || !separates(player)) return;
        InventoryGroup target = InventoryGroup.of(event.getNewGameMode());
        if (target == null) return;
        PlayerState state = stateOf(player.getUniqueId());
        InventoryGroup from = state.activeGroup != null ? state.activeGroup : InventoryGroup.of(player.getGameMode());
        if (from == null || from == target) return;

        player.closeInventory();
        ItemStack[] current = cloneContents(player.getInventory().getContents());
        ItemStack[] incoming = resizeContents(state.stash(target), current.length);
        preparedSwaps.put(player.getUniqueId(), new PreparedSwap(event.getNewGameMode(), from, target, current, incoming));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void applySwap(PlayerGameModeChangeEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        PreparedSwap swap = preparedSwaps.remove(player.getUniqueId());
        if (event.isCancelled()) return;
        GameMode from = player.getGameMode();
        GameMode to = event.getNewGameMode();
        PlayerState state = stateOf(player.getUniqueId());

        if (swap != null && swap.to == to) {
            state.setStash(swap.from, swap.outgoing);
            state.setStash(swap.target, null);
            state.activeGroup = swap.target;
            player.getInventory().setContents(swap.incoming);
            player.updateInventory();
            savePlayerStates();
            notify(player, "creative.inventory-swapped",
                    "{prefix} &7Loaded your &f{group} &7inventory.", false,
                    "{group}", swap.target.id);
        } else {
            InventoryGroup group = InventoryGroup.of(to);
            if (group != null && (state.activeGroup == null || !separates(player))) {
                state.activeGroup = group;
            }
        }
        afterSwitch(player, from, to);
        if (to == GameMode.CREATIVE || to == GameMode.SPECTATOR) {
            audit(player, "switched to", from, to);
        }
    }

    private void afterSwitch(Player player, GameMode from, GameMode to) {
        boolean entering = to == GameMode.CREATIVE && from != GameMode.CREATIVE;
        boolean leaving = from == GameMode.CREATIVE && to != GameMode.CREATIVE;
        if (!entering && !leaving) return;
        if (settings.clearEffects && !can(player, "keep-effects")) {
            for (PotionEffect effect : new ArrayList<PotionEffect>(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
        }
        if (leaving) takeOffCreativeArmor(player);
        if (entering && settings.armorEnabled && !can(player, "own-armor")) putOnCreativeArmor(player);
    }

    private void putOnCreativeArmor(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (!separates(player)) {
            ItemStack[] own = inventory.getArmorContents();
            boolean any = false;
            for (ItemStack piece : own) {
                if (!isAir(piece) && !isCreativeArmor(piece)) {
                    any = true;
                    break;
                }
            }
            if (any) {
                stateOf(player.getUniqueId()).savedArmor = cloneContents(own);
                savePlayerStates();
            }
        }
        List<ArmorPiece> pieces = settings.armor;
        inventory.setArmorContents(new ItemStack[]{
                buildArmorItem(pieces.get(3)),
                buildArmorItem(pieces.get(2)),
                buildArmorItem(pieces.get(1)),
                buildArmorItem(pieces.get(0))
        });
        player.updateInventory();
    }

    private void takeOffCreativeArmor(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (isCreativeArmor(contents[i])) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) inventory.setContents(contents);

        PlayerState state = playerStates.get(player.getUniqueId());
        if (state != null && state.savedArmor != null) {
            ItemStack[] worn = inventory.getArmorContents();
            for (int i = 0; i < worn.length && i < state.savedArmor.length; i++) {
                if (isAir(worn[i]) && state.savedArmor[i] != null) {
                    worn[i] = state.savedArmor[i].clone();
                }
            }
            inventory.setArmorContents(worn);
            state.savedArmor = null;
            savePlayerStates();
            changed = true;
        }
        if (changed) player.updateInventory();
    }

    private void reconcile(Player player) {
        InventoryGroup current = InventoryGroup.of(player.getGameMode());
        if (current == null) return;
        PlayerState state = stateOf(player.getUniqueId());
        if (!separates(player) || state.activeGroup == null) {
            state.activeGroup = current;
            return;
        }
        if (state.activeGroup == current) return;
        ItemStack[] outgoing = cloneContents(player.getInventory().getContents());
        ItemStack[] incoming = resizeContents(state.stash(current), outgoing.length);
        state.setStash(state.activeGroup, outgoing);
        state.setStash(current, null);
        state.activeGroup = current;
        player.getInventory().setContents(incoming);
        player.updateInventory();
        savePlayerStates();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        reconcile(player);
        PlayerState state = stateOf(player.getUniqueId());
        GameMode last = state.lastMode;
        GameMode joined = player.getGameMode();
        if (settings.restoreOnJoin && last != null
                && (last == GameMode.CREATIVE || last == GameMode.SPECTATOR)
                && (joined == GameMode.SURVIVAL || joined == GameMode.ADVENTURE)
                && player.hasPermission("allium.gamemode." + last.name().toLowerCase(Locale.ROOT))) {
            player.setGameMode(last);
            plugin.getLogger().info("Restored " + player.getName() + " to "
                    + last.name().toLowerCase(Locale.ROOT) + " from their last session.");
        } else if (joined == GameMode.CREATIVE || joined == GameMode.SPECTATOR) {
            audit(player, "joined in", joined, joined);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        stateOf(player.getUniqueId()).lastMode = player.getGameMode();
        lastMessage.remove(player.getUniqueId());
        preparedSwaps.remove(player.getUniqueId());
        savePlayerStates();
    }

    private void audit(Player player, String cause, GameMode from, GameMode to) {
        String mode = to.name().toLowerCase(Locale.ROOT);
        if (!settings.audit || player.hasPermission("allium.gamemode." + mode)) return;
        String shownMode = titleCase(mode);
        plugin.getLogger().warning("[GamemodeAudit] " + player.getName() + " " + cause + " " + mode
                + " (from " + from.name().toLowerCase(Locale.ROOT) + ") without allium.gamemode." + mode);
        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.hasPermission(PERMISSION_ALERTS)) {
                messages.send(staff, "creative.audit",
                        "{prefix} &e{player} &c{cause} &7({from} &7-> &f{to}&7) without &fallium.gamemode.{to}&7.",
                        "allium.gamemode.{to}", "allium.gamemode." + mode,
                        "{player}", player.getName(),
                        "{cause}", cause + " " + shownMode,
                        "{from}", titleCase(from.name()),
                        "{to}", shownMode);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectatorTeleport(PlayerTeleportEvent event) {
        if (!isActive()) return;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                && !event.getPlayer().hasPermission(PERMISSION_SPECTATOR_TELEPORT)) {
            event.setCancelled(true);
            notify(event.getPlayer(), "creative.spectator-teleport-denied",
                    "{prefix} &cYou are not allowed to teleport from the spectator menu.", true);
        }
    }

    // ==================== Restrictions ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (!isCreative(player)) return;
        if (!can(player, "place")) {
            event.setCancelled(true);
            deny(player, "place that", event.getBlock().getLocation());
        } else if (settings.blockedBlocks.contains(event.getBlock().getType()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, titleCase(event.getBlock().getType().name()), event.getBlock().getLocation());
        } else if (!can(player, "filled-containers") && isFilledContainer(event.getItemInHand())) {
            event.setCancelled(true);
            deny(player, "place containers that hold items", event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (isCreative(player) && settings.blockedItems.contains(event.getBucket()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, titleCase(event.getBucket().name()), event.getBlockClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (!isCreative(player) || settings.blockedCommands.isEmpty() || can(player, "blacklist")) return;
        if (commandBlocked(settings.blockedCommands, event.getMessage())) {
            event.setCancelled(true);
            String line = commandLine(event.getMessage());
            int sp = line.indexOf(' ');
            denyBlacklisted(player, "/" + (sp >= 0 ? line.substring(0, sp) : line), null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (isCreative(player)) {
            if (!can(player, "break")) {
                event.setCancelled(true);
                deny(player, "break that", event.getBlock().getLocation());
            } else if (settings.blockedBlocks.contains(event.getBlock().getType()) && !can(player, "blacklist")) {
                event.setCancelled(true);
                denyBlacklisted(player, titleCase(event.getBlock().getType().name()), event.getBlock().getLocation());
            }
            return;
        }
        // Survival/Adventure breaking a creative-placed block
        if (isMarkedPlaced(event.getBlock()) && !player.hasPermission(PERMISSION_LOOT)) {
            if (settings.placedBlocksDeny) {
                event.setCancelled(true);
                notify(player, "creative.restricted",
                        "{prefix} &cYou are not allowed to {action} in creative mode.", true,
                        "{action}", "break blocks placed in creative mode");
            } else {
                event.setDropItems(false);
                event.setExpToDrop(0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (!isCreative(player)) return;
        if (event.useInteractedBlock() == Event.Result.DENY && event.useItemInHand() == Event.Result.DENY) return;
        Block block = event.getClickedBlock();
        ItemStack item = event.getItem();
        boolean right = event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR;

        // 1.12.2-1.19.2 TNT ignition guard (before TNTPrimeEvent)
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null && block.getType() == Material.TNT
                && settings.protectTnt && !can(player, "blacklist")
                && item != null && isTntIgniter(item.getType())) {
            event.setCancelled(true);
            deny(player, "light TNT", block.getLocation());
            return;
        }

        EntityType egg = spawnEggType(item);
        if (egg != null && right) {
            if (!can(player, "spawn")) {
                event.setCancelled(true);
                deny(player, "spawn that", block == null ? null : block.getLocation());
                return;
            }
            if (settings.blockedEntities.contains(egg) && !can(player, "blacklist")) {
                event.setCancelled(true);
                denyBlacklisted(player, titleCase(egg.name()), block == null ? null : block.getLocation());
                return;
            }
        }
        if (right && item != null && settings.blockedItems.contains(item.getType()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, titleCase(item.getType().name()), block == null ? null : block.getLocation());
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null
                && settings.blockedBlocks.contains(block.getType()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, titleCase(block.getType().name()), block.getLocation());
            return;
        }
        // Pre-1.13 entity placement items (armor stands, minecarts, boats, end crystals)
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null && item != null) {
            EntityType placed = entityItemType(item.getType());
            if (placed != null) {
                if (settings.blockedEntities.contains(placed) && !can(player, "blacklist")) {
                    event.setCancelled(true);
                    denyBlacklisted(player, titleCase(placed.name()), block.getLocation());
                    return;
                }
                if (!can(player, "place")) {
                    event.setCancelled(true);
                    deny(player, "place that", block.getLocation());
                    return;
                }
            }
        }
        if (can(player, "interact")) return;
        if (right && item != null && isFirework(item.getType())) {
            event.setCancelled(true);
            deny(player, "use fireworks", null);
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null && isInteractable(block.getType())
                && !(player.isSneaking() && !isAir(item))) {
            event.setCancelled(true);
            deny(player, "interact with " + titleCase(block.getType().name()), block.getLocation());
            return;
        }
        if (event.getAction() == Action.PHYSICAL && block != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (isCreative(player) && !can(player, "interact")) {
            event.setCancelled(true);
            deny(player, event.getItem().getType() == Material.POTION ? "drink that" : "eat that", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (!isCreative(player)) return;
        ItemStack hand = event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        EntityType egg = spawnEggType(hand);
        if (egg != null && (!can(player, "spawn")
                || (settings.blockedEntities.contains(egg) && !can(player, "blacklist")))) {
            event.setCancelled(true);
            deny(player, "spawn that", event.getRightClicked().getLocation());
            return;
        }
        if (can(player, "interact")) return;
        Entity clicked = event.getRightClicked();
        String typeName = clicked.getType().name();
        if (clicked instanceof ItemFrame || clicked instanceof LivingEntity
                || typeName.contains("MINECART") || typeName.contains("BOAT") || typeName.contains("RAFT")) {
            event.setCancelled(true);
            deny(player, "interact with that " + titleCase(typeName), clicked.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteractAt(PlayerInteractAtEntityEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (isCreative(player) && !can(player, "interact") && event.getRightClicked().getType() == EntityType.ARMOR_STAND) {
            event.setCancelled(true);
            deny(player, "interact with armor stands", event.getRightClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (player == null || !isCreative(player)) return;
        if (settings.blockedEntities.contains(event.getEntity().getType()) && !can(player, "blacklist")) {
            event.setCancelled(true);
            denyBlacklisted(player, titleCase(event.getEntity().getType().name()), event.getBlock().getLocation());
        } else if (!can(player, "place") || !can(player, "interact")) {
            event.setCancelled(true);
            deny(player, "place that", event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (!isActive()) return;
        if (event.getRemover() instanceof Player) {
            Player player = (Player) event.getRemover();
            if (isCreative(player) && (!can(player, "interact") || !can(player, "break"))) {
                event.setCancelled(true);
                deny(player, "break that", event.getEntity().getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!isActive()) return;
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (isCreative(player) && !can(player, "interact")) {
                event.setCancelled(true);
                deny(player, "shoot that", null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (!isActive()) return;
        if (event.getEntity().getShooter() instanceof Player) {
            Player player = (Player) event.getEntity().getShooter();
            if (isCreative(player) && !can(player, "interact")) {
                event.setCancelled(true);
                deny(player, "throw " + titleCase(event.getEntity().getType().name()) + "s", null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (isCreative(player) && !can(player, "interact") && event.getState() == PlayerFishEvent.State.FISHING) {
            event.setCancelled(true);
            deny(player, "use fishing rods", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isActive()) return;
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            if (isCreative(player) && !can(player, "interact")) {
                event.setCancelled(true);
                deny(player, "hurt that " + titleCase(event.getEntity().getType().name()), event.getEntity().getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreativeMenu(InventoryCreativeEvent event) {
        if (!isActive()) return;
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!isCreative(player)) return;
        ItemStack taken = event.getCursor();
        if (can(player, "use")) {
            boolean armorSlot = event.getSlotType() == InventoryType.SlotType.ARMOR;
            if ((armorSlot && isCreativeArmor(event.getCurrentItem()) && !isCreativeArmor(taken))
                    || (!armorSlot && isCreativeArmor(taken))) {
                refuseCreative(event, player);
                deny(player, "take off the creative armour", null);
                return;
            }
            if (isAir(taken)) return;
            if (settings.blockedItems.contains(taken.getType()) && !can(player, "blacklist")) {
                refuseCreative(event, player);
                denyBlacklisted(player, titleCase(taken.getType().name()), null);
                return;
            }
            boolean anyItem = can(player, "any-item");
            if (!anyItem && settings.refuseEnchanted && isEnchanted(taken)) {
                refuseCreative(event, player);
                deny(player, "take enchanted items from the creative menu", null);
                return;
            }
            if (!anyItem && settings.refusePotions && hasPotionEffects(taken)) {
                refuseCreative(event, player);
                deny(player, "take potions with effects from the creative menu", null);
                return;
            }
            ItemStack result = taken;
            if (!anyItem && settings.sanitize) {
                result = sanitizeItem(result);
            }
            if (settings.loreEnabled && !can(player, "untagged") && !isTaggedCreative(result) && !isCreativeArmor(result)) {
                result = tagCreativeItem(result, player, settings.loreLines);
            }
            if (result != taken) {
                event.setCursor(result);
            }
            return;
        }
        if (isAir(taken)) return;
        refuseCreative(event, player);
        deny(player, "take items from the creative menu", null);
    }

    private void refuseCreative(InventoryCreativeEvent event, final Player player) {
        event.setCancelled(true);
        try {
            plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    if (player.isOnline() && isCreative(player)) {
                        player.setItemOnCursor(null);
                        player.updateInventory();
                    }
                }
            });
        } catch (Throwable ignored) {
            player.setItemOnCursor(null);
            player.updateInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isActive() || event instanceof InventoryCreativeEvent) return;
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!isCreative(player)) return;
        if (!can(player, "interact") && event.getSlotType() == InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
            deny(player, "equip armor", null);
            return;
        }
        if (!can(player, "container") && event.getClickedInventory() != null
                && event.getClickedInventory() != player.getInventory()
                && CONTAINER_TYPES.contains(event.getClickedInventory().getType().name())) {
            event.setCancelled(true);
            deny(player, "use this container", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        if (isCreative(player) && !can(player, "drop")) {
            event.setCancelled(true);
            deny(player, "drop that", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!isActive()) return;
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (isCreative(player) && !can(player, "pickup")) {
                event.setCancelled(true);
                deny(player, "pick that up", event.getItem().getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!isActive()) return;
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        if (!isCreative(player) || can(player, "container")) return;
        if (CONTAINER_TYPES.contains(event.getInventory().getType().name())) {
            event.setCancelled(true);
            deny(player, "use this container", event.getInventory().getLocation());
        }
    }

    // ==================== Placed-Block Loot Protection ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlacedMonitor(BlockPlaceEvent event) {
        if (!isActive() || !settings.placedBlocksEnabled) return;
        if (isCreative(event.getPlayer())) {
            for (Block part : structureBlocks(event.getBlockPlaced())) {
                markPlaced(part);
            }
        } else if (isMarkedPlaced(event.getBlockPlaced())) {
            unmarkPlaced(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrokenMonitor(BlockBreakEvent event) {
        if (!isActive() || !settings.placedBlocksEnabled) return;
        for (Block part : structureBlocks(event.getBlock())) {
            unmarkPlaced(part);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        removeMarkedFromList(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        removeMarkedFromList(event.blockList());
    }

    private void removeMarkedFromList(List<Block> blocks) {
        if (!isActive() || !settings.placedBlocksEnabled || blocks.isEmpty()) return;
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block block = it.next();
            if (!isMarkedPlaced(block)) continue;
            it.remove();
            block.setType(Material.AIR, false);
            unmarkPlaced(block);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (!isActive() || !settings.placedBlocksEnabled) return;
        Block to = event.getToBlock();
        if (isAirType(to.getType()) || to.isLiquid() || !isMarkedPlaced(to)) return;
        event.setCancelled(true);
        to.setType(Material.AIR, false);
        unmarkPlaced(to);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        if (!isActive() || !settings.placedBlocksEnabled) return;
        if (!isMarkedPlaced(event.getBlock())) return;
        event.setCancelled(true);
        event.getBlock().setType(Material.AIR, false);
        unmarkPlaced(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isActive() && settings.placedBlocksEnabled) unmarkPlaced(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (isActive() && settings.placedBlocksEnabled && isAirType(event.getNewState().getType())) {
            unmarkPlaced(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!isActive() || !settings.placedBlocksEnabled) return;
        Block block = event.getBlock();
        if (event.getEntity() instanceof FallingBlock) {
            FallingBlock falling = (FallingBlock) event.getEntity();
            if (isAirType(event.getTo())) {
                if (isMarkedPlaced(block)) {
                    unmarkPlaced(block);
                    creativeFalling.add(falling.getUniqueId());
                    falling.setDropItem(false);
                }
            } else if (creativeFalling.remove(falling.getUniqueId())) {
                markPlaced(block);
            }
            return;
        }
        if (isAirType(event.getTo()) && isMarkedPlaced(block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        moveMarkedBlocks(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        moveMarkedBlocks(event.getBlocks(), event.getDirection());
    }

    private void moveMarkedBlocks(List<Block> blocks, BlockFace direction) {
        if (!isActive() || !settings.placedBlocksEnabled || blocks.isEmpty()) return;
        List<Block> carried = new ArrayList<Block>();
        for (Block block : blocks) {
            if (!isMarkedPlaced(block)) continue;
            unmarkPlaced(block);
            if (block.getPistonMoveReaction() == PistonMoveReaction.BREAK) {
                block.setType(Material.AIR, false);
            } else {
                carried.add(block.getRelative(direction));
            }
        }
        for (Block landed : carried) markPlaced(landed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (!isActive() || !settings.placedBlocksEnabled) return;
        Block block = event.getBlock();
        String grown = event.getNewState().getType().name();
        if ("CACTUS".equals(grown) || "SUGAR_CANE".equals(grown) || "SUGAR_CANE_BLOCK".equals(grown)
                || "BAMBOO".equals(grown) || "KELP_PLANT".equals(grown) || "KELP".equals(grown)) {
            if (isMarkedPlaced(block.getRelative(BlockFace.DOWN))) markPlaced(block);
        } else if ("PUMPKIN".equals(grown) || "MELON".equals(grown) || "MELON_BLOCK".equals(grown)) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block stem = block.getRelative(face);
                if (stem.getType().name().endsWith("STEM") && isMarkedPlaced(stem)) {
                    markPlaced(block);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (!isActive() || !settings.placedBlocksEnabled) return;
        if (!isMarkedPlaced(event.getLocation().getBlock())) return;
        for (BlockState state : event.getBlocks()) {
            markPlaced(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (isActive() && settings.placedBlocksEnabled && isMarkedPlaced(event.getSource())) {
            markPlaced(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBuiltMob(CreatureSpawnEvent event) {
        if (!isActive() || !settings.placedBlocksEnabled || !settings.preventMobBuilds) return;
        List<int[]> shape = mobBuildShape(event.getSpawnReason());
        if (shape.isEmpty()) return;
        Block base = event.getLocation().getBlock();
        for (int[] offset : shape) {
            if (isMarkedPlaced(base.getRelative(offset[0], offset[1], offset[2]))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private static List<int[]> mobBuildShape(CreatureSpawnEvent.SpawnReason reason) {
        String name = reason.name();
        if ("BUILD_SNOWMAN".equals(name)) {
            return Arrays.asList(new int[]{0, 0, 0}, new int[]{0, 1, 0}, new int[]{0, 2, 0});
        }
        if ("BUILD_IRONGOLEM".equals(name)) {
            return Arrays.asList(new int[]{0, 0, 0}, new int[]{0, 1, 0}, new int[]{0, 2, 0},
                    new int[]{0, 1, 1}, new int[]{0, 1, -1}, new int[]{1, 1, 0}, new int[]{-1, 1, 0});
        }
        if ("BUILD_WITHER".equals(name)) {
            return Arrays.asList(new int[]{0, 0, 0}, new int[]{0, 1, 0}, new int[]{0, 2, 0},
                    new int[]{0, 1, 1}, new int[]{0, 2, 1}, new int[]{0, 1, -1}, new int[]{0, 2, -1},
                    new int[]{1, 1, 0}, new int[]{1, 2, 0}, new int[]{-1, 1, 0}, new int[]{-1, 2, 0});
        }
        return Collections.emptyList();
    }

    // ==================== Dynamic 1.13+ / 1.19+ Event Hooks ====================

    @SuppressWarnings("unchecked")
    private void hookDynamicEvents() {
        if (dynamicHooksInstalled) return;
        dynamicHooksInstalled = true;

        // 1.13+ EntityPlaceEvent
        try {
            Class<? extends Event> epClass = (Class<? extends Event>)
                    Class.forName("org.bukkit.event.entity.EntityPlaceEvent");
            final Method getPlayer = epClass.getMethod("getPlayer");
            final Method getEntityType = epClass.getMethod("getEntityType");
            final Method getBlock = epClass.getMethod("getBlock");
            plugin.getServer().getPluginManager().registerEvent(epClass, this, EventPriority.HIGH, new EventExecutor() {
                @Override
                public void execute(Listener listener, Event raw) throws EventException {
                    if (!isActive() || !(raw instanceof Cancellable)) return;
                    try {
                        Player player = (Player) getPlayer.invoke(raw);
                        if (player == null || !isCreative(player)) return;
                        EntityType type = (EntityType) getEntityType.invoke(raw);
                        Block block = (Block) getBlock.invoke(raw);
                        Location at = block == null ? null : block.getLocation();
                        if (type != null && settings.blockedEntities.contains(type) && !can(player, "blacklist")) {
                            ((Cancellable) raw).setCancelled(true);
                            denyBlacklisted(player, titleCase(type.name()), at);
                        } else if (!can(player, "place")) {
                            ((Cancellable) raw).setCancelled(true);
                            deny(player, "place that", at);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, plugin, true);
        } catch (Throwable pre113) {
            // Handled via PlayerInteractEvent on 1.12.2.
        }

        // 1.19.2+ TNTPrimeEvent
        try {
            Class<? extends Event> tntClass = (Class<? extends Event>)
                    Class.forName("org.bukkit.event.block.TNTPrimeEvent");
            final Method getPrimingEntity = tntClass.getMethod("getPrimingEntity");
            final Method getBlock = tntClass.getMethod("getBlock");
            plugin.getServer().getPluginManager().registerEvent(tntClass, this, EventPriority.HIGH, new EventExecutor() {
                @Override
                public void execute(Listener listener, Event raw) throws EventException {
                    if (!isActive() || !settings.protectTnt || !(raw instanceof Cancellable)) return;
                    try {
                        Entity primer = (Entity) getPrimingEntity.invoke(raw);
                        Player player = primingPlayer(primer);
                        if (player == null || !isCreative(player) || can(player, "blacklist")) return;
                        Block block = (Block) getBlock.invoke(raw);
                        ((Cancellable) raw).setCancelled(true);
                        deny(player, "light TNT", block == null ? null : block.getLocation());
                    } catch (Throwable ignored) {
                    }
                }
            }, plugin, true);
        } catch (Throwable pre119) {
            // Handled via PlayerInteractEvent on 1.12.2-1.19.2.
        }
    }

    private static Player primingPlayer(Entity primer) {
        if (primer instanceof Player) return (Player) primer;
        if (primer instanceof Projectile && ((Projectile) primer).getShooter() instanceof Player) {
            return (Player) ((Projectile) primer).getShooter();
        }
        return null;
    }

    // ==================== Placed-Block Storage ====================

    static int packPos(int x, int y, int z) {
        return (y << 8) | ((z & 15) << 4) | (x & 15);
    }

    private static long packChunk(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }

    boolean isMarkedPlaced(Block block) {
        if (!settings.placedBlocksEnabled || block == null || block.getWorld() == null) return false;
        Map<Long, int[]> worldMap = placedBlocks.get(block.getWorld().getName());
        if (worldMap == null) return false;
        int[] arr = worldMap.get(Long.valueOf(packChunk(block.getX() >> 4, block.getZ() >> 4)));
        return arr != null && Arrays.binarySearch(arr, packPos(block.getX(), block.getY(), block.getZ())) >= 0;
    }

    void markPlaced(Block block) {
        if (block == null || block.getWorld() == null) return;
        String world = block.getWorld().getName();
        Map<Long, int[]> worldMap = placedBlocks.get(world);
        if (worldMap == null) {
            worldMap = new HashMap<Long, int[]>();
            placedBlocks.put(world, worldMap);
        }
        Long ck = Long.valueOf(packChunk(block.getX() >> 4, block.getZ() >> 4));
        int[] current = worldMap.get(ck);
        int pos = packPos(block.getX(), block.getY(), block.getZ());
        int[] next = addSorted(current == null ? new int[0] : current, pos);
        if (next != current) {
            worldMap.put(ck, next);
            placedBlocksDirty = true;
        }
    }

    void unmarkPlaced(Block block) {
        if (block == null || block.getWorld() == null) return;
        Map<Long, int[]> worldMap = placedBlocks.get(block.getWorld().getName());
        if (worldMap == null) return;
        Long ck = Long.valueOf(packChunk(block.getX() >> 4, block.getZ() >> 4));
        int[] current = worldMap.get(ck);
        if (current == null) return;
        int pos = packPos(block.getX(), block.getY(), block.getZ());
        int[] next = removeSorted(current, pos);
        if (next != current) {
            if (next.length == 0) worldMap.remove(ck);
            else worldMap.put(ck, next);
            placedBlocksDirty = true;
        }
    }

    private static int[] addSorted(int[] marks, int value) {
        int at = Arrays.binarySearch(marks, value);
        if (at >= 0) return marks;
        int insert = -at - 1;
        int[] next = new int[marks.length + 1];
        System.arraycopy(marks, 0, next, 0, insert);
        next[insert] = value;
        System.arraycopy(marks, insert, next, insert + 1, marks.length - insert);
        return next;
    }

    private static int[] removeSorted(int[] marks, int value) {
        int at = Arrays.binarySearch(marks, value);
        if (at < 0) return marks;
        int[] next = new int[marks.length - 1];
        System.arraycopy(marks, 0, next, 0, at);
        System.arraycopy(marks, at + 1, next, at, marks.length - at - 1);
        return next;
    }

    @SuppressWarnings("deprecation")
    private static List<Block> structureBlocks(Block block) {
        List<Block> parts = new ArrayList<Block>(2);
        parts.add(block);
        // 1.13+ BlockData (Bed / Bisected) via reflection
        if (BLOCK_GET_DATA != null) {
            try {
                Object data = BLOCK_GET_DATA.invoke(block);
                if (data != null) {
                    Class<?> bedClass = Class.forName("org.bukkit.block.data.type.Bed");
                    if (bedClass.isInstance(data)) {
                        Object part = bedClass.getMethod("getPart").invoke(data);
                        BlockFace facing = (BlockFace) bedClass.getMethod("getFacing").invoke(data);
                        parts.add(block.getRelative("HEAD".equals(String.valueOf(part)) ? facing.getOppositeFace() : facing));
                        return parts;
                    }
                    Class<?> bisectedClass = Class.forName("org.bukkit.block.data.Bisected");
                    if (bisectedClass.isInstance(data)) {
                        Object half = bisectedClass.getMethod("getHalf").invoke(data);
                        parts.add(block.getRelative("BOTTOM".equals(String.valueOf(half)) ? BlockFace.UP : BlockFace.DOWN));
                        return parts;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 1.12.2 MaterialData fallback
        try {
            MaterialData md = block.getState().getData();
            if (md instanceof Bed) {
                Bed bed = (Bed) md;
                BlockFace facing = bed.getFacing();
                parts.add(block.getRelative(bed.isHeadOfBed() ? facing.getOppositeFace() : facing));
            } else if (md instanceof Door) {
                Door door = (Door) md;
                parts.add(block.getRelative(door.isTopHalf() ? BlockFace.DOWN : BlockFace.UP));
            }
        } catch (Throwable ignored) {
        }
        return parts;
    }

    // ==================== Item Helpers ====================

    static boolean isEnchanted(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return false;
        if (meta.hasEnchants()) return true;
        return meta instanceof EnchantmentStorageMeta && ((EnchantmentStorageMeta) meta).hasStoredEnchants();
    }

    static boolean hasPotionEffects(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return false;
        if (meta instanceof PotionMeta) {
            PotionMeta potion = (PotionMeta) meta;
            if (potion.hasCustomEffects()) return true;
            try {
                PotionData data = potion.getBasePotionData();
                if (data != null && data.getType() != null && data.getType().getEffectType() != null) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
        // 1.14+ SuspiciousStewMeta via reflection
        try {
            Class<?> stewClass = Class.forName("org.bukkit.inventory.meta.SuspiciousStewMeta");
            if (stewClass.isInstance(meta)) {
                return Boolean.TRUE.equals(stewClass.getMethod("hasCustomEffects").invoke(meta));
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static ItemStack sanitizeItem(ItemStack item) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        meta.setUnbreakable(false);
        for (Map.Entry<Enchantment, Integer> e : new HashMap<Enchantment, Integer>(meta.getEnchants()).entrySet()) {
            if (e.getValue().intValue() > e.getKey().getMaxLevel()) {
                meta.removeEnchant(e.getKey());
            }
        }
        if (meta instanceof EnchantmentStorageMeta) {
            EnchantmentStorageMeta book = (EnchantmentStorageMeta) meta;
            for (Map.Entry<Enchantment, Integer> e : new HashMap<Enchantment, Integer>(book.getStoredEnchants()).entrySet()) {
                if (e.getValue().intValue() > e.getKey().getMaxLevel()) {
                    book.removeStoredEnchant(e.getKey());
                }
            }
        }
        if (META_HAS_ATTRIBUTES != null && META_SET_ATTRIBUTES != null) {
            try {
                if (Boolean.TRUE.equals(META_HAS_ATTRIBUTES.invoke(meta))) {
                    META_SET_ATTRIBUTES.invoke(meta, new Object[]{null});
                }
            } catch (Throwable ignored) {
            }
        }
        if (META_GET_PDC != null) {
            try {
                Object pdc = META_GET_PDC.invoke(meta);
                if (pdc != null) {
                    Collection<?> keys = (Collection<?>) pdc.getClass().getMethod("getKeys").invoke(pdc);
                    Method remove = findMethodByName(pdc.getClass(), "remove", 1);
                    if (keys != null && remove != null) {
                        for (Object key : new ArrayList<Object>(keys)) {
                            remove.invoke(pdc, key);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        copy.setItemMeta(meta);
        return copy;
    }

    static boolean isTaggedCreative(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) return false;
        for (String line : meta.getLore()) {
            if (line != null && line.startsWith(LORE_TAG)) return true;
        }
        return false;
    }

    static ItemStack tagCreativeItem(ItemStack item, Player player, List<String> lines) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<String>(meta.getLore())
                : new ArrayList<String>();
        String itemName = titleCase(item.getType().name());
        boolean marked = false;
        for (String line : lines) {
            String colored = LegacyCompat.color(line
                    .replace("{item}", itemName)
                    .replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString()));
            if (!marked) {
                colored = LORE_TAG + colored;
                marked = true;
            }
            lore.add(colored);
        }
        if (!marked) {
            lore.add(LORE_TAG + ChatColor.DARK_PURPLE + "Creative " + itemName);
        }
        meta.setLore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    static ItemStack buildArmorItem(ArmorPiece piece) {
        ItemStack item = new ItemStack(piece.material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String display = piece.name.trim().isEmpty() ? "" : LegacyCompat.color(piece.name);
        meta.setDisplayName(ARMOR_TAG + display);
        if (!piece.lore.isEmpty()) {
            List<String> colored = new ArrayList<String>(piece.lore.size());
            for (String line : piece.lore) colored.add(LegacyCompat.color(line));
            meta.setLore(colored);
        }
        if (piece.color != null && meta instanceof LeatherArmorMeta) {
            ((LeatherArmorMeta) meta).setColor(piece.color);
        }
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

    static boolean isCreativeArmor(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.hasDisplayName() && meta.getDisplayName() != null
                && meta.getDisplayName().startsWith(ARMOR_TAG);
    }

    static boolean isFilledContainer(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof BlockStateMeta)) return false;
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        if (!meta.hasBlockState() || !(meta.getBlockState() instanceof Container)) return false;
        ItemStack[] contents = ((Container) meta.getBlockState()).getInventory().getContents();
        if (contents == null) return false;
        for (ItemStack stack : contents) {
            if (!isAir(stack)) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    static EntityType spawnEggType(ItemStack item) {
        if (item == null) return null;
        String matName = item.getType().name();
        if (matName.endsWith("_SPAWN_EGG")) {
            EntityType type = parseEntityType(matName.substring(0, matName.length() - "_SPAWN_EGG".length()));
            if (type == null && "MOOSHROOM_SPAWN_EGG".equals(matName)) type = parseEntityType("MUSHROOM_COW");
            return type;
        }
        if ("MONSTER_EGG".equals(matName)) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof SpawnEggMeta) {
                EntityType spawned = ((SpawnEggMeta) meta).getSpawnedType();
                if (spawned != null) return spawned;
            }
            return EntityType.fromId(item.getDurability());
        }
        return null;
    }

    private static EntityType entityItemType(Material material) {
        if (material == null) return null;
        String name = material.name();
        if ("ARMOR_STAND".equals(name)) return EntityType.ARMOR_STAND;
        if ("END_CRYSTAL".equals(name)) return parseEntityType("ENDER_CRYSTAL");
        if (name.contains("MINECART")) return parseEntityType(name);
        if (name.contains("BOAT") || name.contains("RAFT")) return parseEntityType("BOAT");
        return null;
    }

    private static boolean isTntIgniter(Material material) {
        String name = material.name();
        return "FLINT_AND_STEEL".equals(name) || "FIRE_CHARGE".equals(name) || "FIREBALL".equals(name);
    }

    private static boolean isFirework(Material material) {
        String name = material.name();
        return "FIREWORK".equals(name) || "FIREWORK_ROCKET".equals(name);
    }

    static boolean isInteractable(Material material) {
        if (material == null) return false;
        String name = material.name();
        for (String part : INTERACTABLE_PARTS) {
            if (name.contains(part)) return true;
        }
        return INTERACTABLE_EXACT.contains(name);
    }

    static String commandLine(String raw) {
        if (raw == null) return "";
        String line = raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (line.startsWith("/")) line = line.substring(1);
        int space = line.indexOf(' ');
        String label = space < 0 ? line : line.substring(0, space);
        int colon = label.lastIndexOf(':');
        return colon < 0 ? line : line.substring(colon + 1);
    }

    static boolean commandBlocked(List<String> blocked, String typed) {
        String line = commandLine(typed);
        for (String entry : blocked) {
            if (line.equals(entry) || line.startsWith(entry + " ")) return true;
        }
        return false;
    }

    // ==================== Notifications & Utilities ====================

    private static boolean isCreative(Player player) {
        return player != null && player.getGameMode() == GameMode.CREATIVE;
    }

    private static boolean can(Player player, String action) {
        return player.hasPermission(PERMISSION_PREFIX + action);
    }

    private void deny(Player player, String action, Location at) {
        fizzle(at != null ? at : player.getLocation());
        notify(player, "creative.restricted",
                "{prefix} &cYou are not allowed to {action} in creative mode.", true,
                "{action}", action);
    }

    private void denyBlacklisted(Player player, String target, Location at) {
        fizzle(at != null ? at : player.getLocation());
        notify(player, "creative.blacklisted",
                "{prefix} &f{target} &cis not allowed in creative mode.", true,
                "{target}", target);
    }

    private void notify(Player player, String key, String fallback, boolean throttle, String... replacements) {
        long now = System.currentTimeMillis();
        if (throttle) {
            Long last = lastMessage.get(player.getUniqueId());
            if (last != null && now - last.longValue() < settings.cooldownMillis) return;
            lastMessage.put(player.getUniqueId(), Long.valueOf(now));
        }
        messages.send(player, key, fallback, replacements);
    }

    private void fizzle(Location at) {
        if (at == null || at.getWorld() == null) return;
        Location center = at.clone().add(0.5, 0.5, 0.5);
        if (!pre113 && RED_DUST_OPTIONS != null && WORLD_SPAWN_PARTICLE_DATA != null) {
            try {
                WORLD_SPAWN_PARTICLE_DATA.invoke(at.getWorld(), Particle.REDSTONE, center, 15,
                        0.5, 0.5, 0.5, 0.0, RED_DUST_OPTIONS);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            at.getWorld().spawnParticle(Particle.REDSTONE, center.getX(), center.getY(), center.getZ(),
                    0, 1.0, 0.0, 0.0, 1.0);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isAir(ItemStack stack) {
        return stack == null || isAirType(stack.getType());
    }

    private static boolean isAirType(Material type) {
        if (type == null || type == Material.AIR) return true;
        String name = type.name();
        return "CAVE_AIR".equals(name) || "VOID_AIR".equals(name);
    }

    private static EntityType parseEntityType(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String norm = raw.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        try {
            return EntityType.valueOf(norm);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static String titleCase(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] parts = raw.toLowerCase(Locale.ROOT).replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static ItemStack[] cloneContents(ItemStack[] source) {
        if (source == null) return new ItemStack[0];
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }

    private static ItemStack[] resizeContents(ItemStack[] source, int size) {
        ItemStack[] out = new ItemStack[size];
        if (source == null) return out;
        for (int i = 0; i < size && i < source.length; i++) {
            out[i] = source[i] == null ? null : source[i].clone();
        }
        return out;
    }

    // ==================== Configuration ====================

    private Settings readSettings(org.bukkit.configuration.file.FileConfiguration root) {
        ConfigurationSection c = root == null ? null : root.getConfigurationSection("creative-manager");
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        Set<EntityType> entities = EnumSet.noneOf(EntityType.class);
        Set<Material> items = EnumSet.noneOf(Material.class);
        List<String> commands = new ArrayList<String>();
        if (c != null) {
            for (String name : c.getStringList("blacklist.blocks")) {
                Material m = LegacyCompat.matchMaterial(name);
                if (m != null) blocks.add(m);
            }
            for (String name : c.getStringList("blacklist.entities")) {
                EntityType t = parseEntityType(name);
                if (t != null) entities.add(t);
            }
            for (String name : c.getStringList("blacklist.items")) {
                Material m = LegacyCompat.matchMaterial(name);
                if (m == null && "FIRE_CHARGE".equalsIgnoreCase(name)) m = LegacyCompat.matchMaterial("FIREBALL");
                if (m != null) items.add(m);
            }
            for (String raw : c.getStringList("blacklist.commands")) {
                String cmd = commandLine(raw);
                if (!cmd.isEmpty()) commands.add(cmd);
            }
        }
        ConfigurationSection placed = c == null ? null : c.getConfigurationSection("placed-blocks");
        boolean placedEnabled = placed == null || placed.getBoolean("enabled", true);
        boolean placedDeny = placed != null && "deny".equalsIgnoreCase(placed.getString("survival-break", "no-drops"));
        boolean preventMobBuilds = placed == null || placed.getBoolean("prevent-mob-builds", true);

        List<String> defaultLore = Arrays.asList("&5Creative {item}", "&7Taken by {player}");
        boolean loreEnabled = c == null || c.getBoolean("item-lore.enabled", true);
        List<String> loreLines = (c != null && c.isList("item-lore.lines")) ? c.getStringList("item-lore.lines") : defaultLore;
        boolean sanitize = c == null || c.getBoolean("menu-items.sanitize", true);
        boolean refuseEnchanted = c == null || c.getBoolean("menu-items.refuse-enchanted", true);
        boolean refusePotions = c == null || c.getBoolean("menu-items.refuse-potions", true);
        boolean clearEffects = c == null || c.getBoolean("clear-effects-on-switch", true);
        boolean armorEnabled = c == null || c.getBoolean("armor.enabled", true);

        List<ArmorPiece> defaults = defaultArmor();
        List<ArmorPiece> armor = new ArrayList<ArmorPiece>(4);
        String[] slots = new String[]{"helmet", "chestplate", "leggings", "boots"};
        for (int i = 0; i < slots.length; i++) {
            ArmorPiece fb = defaults.get(i);
            ConfigurationSection sec = c == null ? null : c.getConfigurationSection("armor." + slots[i]);
            if (sec == null) {
                armor.add(fb);
                continue;
            }
            Material mat = LegacyCompat.matchMaterial(sec.getString("material", fb.material.name()));
            if (mat == null) mat = fb.material;
            String name = sec.getString("name", fb.name);
            List<String> lore = sec.contains("lore") ? sec.getStringList("lore") : fb.lore;
            Color color = sec.contains("color") ? parseHexColor(sec.getString("color")) : fb.color;
            armor.add(new ArmorPiece(mat, name, lore, color));
        }

        return new Settings(
                c == null || c.getBoolean("separate-inventories", true),
                c == null || c.getBoolean("restore-gamemode-on-join", true),
                c == null || c.getBoolean("audit", true),
                Math.max(0, c == null ? 10 : c.getInt("message-cooldown-seconds", 10)) * 1000L,
                c == null || c.getBoolean("protect-tnt", true),
                blocks, entities, items, commands,
                placedEnabled, placedDeny, preventMobBuilds,
                loreEnabled, loreLines, sanitize, refuseEnchanted, refusePotions,
                clearEffects, armorEnabled, armor);
    }

    private static List<ArmorPiece> defaultArmor() {
        List<String> lore = Collections.singletonList("&7Worn while in creative mode");
        return Arrays.asList(
                new ArmorPiece(Material.LEATHER_HELMET, "&6Creative Helmet", lore, ALLIUM_PINK),
                new ArmorPiece(Material.LEATHER_CHESTPLATE, "&6Creative Chestplate", lore, ALLIUM_PINK),
                new ArmorPiece(Material.LEATHER_LEGGINGS, "&6Creative Leggings", lore, ALLIUM_PINK),
                new ArmorPiece(Material.LEATHER_BOOTS, "&6Creative Boots", lore, ALLIUM_PINK));
    }

    private static Color parseHexColor(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String hex = raw.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            return Color.fromRGB(Integer.parseInt(hex, 16) & 0xFFFFFF);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ==================== Persistence ====================

    private File inventoriesFile() {
        return new File(plugin.getDataFolder(), "creative-inventories-legacy.yml");
    }

    private File placedBlocksFile() {
        return new File(plugin.getDataFolder(), "creative-placed-blocks-legacy.yml");
    }

    private void loadPlayerStates() {
        playerStates.clear();
        File file = inventoriesFile();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ConfigurationSection sec = players.getConfigurationSection(key);
            if (sec == null) continue;
            PlayerState state = new PlayerState();
            state.activeGroup = InventoryGroup.parse(sec.getString("active-group"));
            String modeRaw = sec.getString("last-gamemode");
            if (modeRaw != null) {
                try { state.lastMode = GameMode.valueOf(modeRaw); } catch (IllegalArgumentException ignored) {}
            }
            state.survivalStash = readItemArray(sec.getList("survival"));
            state.creativeStash = readItemArray(sec.getList("creative"));
            state.savedArmor = readItemArray(sec.getList("saved-armor"));
            playerStates.put(id, state);
        }
    }

    private void savePlayerStates() {
        if (playerStates.isEmpty()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerState> entry : playerStates.entrySet()) {
            String base = "players." + entry.getKey().toString();
            PlayerState st = entry.getValue();
            if (st.activeGroup != null) yaml.set(base + ".active-group", st.activeGroup.id);
            if (st.lastMode != null) yaml.set(base + ".last-gamemode", st.lastMode.name());
            if (st.survivalStash != null) yaml.set(base + ".survival", Arrays.asList(st.survivalStash));
            if (st.creativeStash != null) yaml.set(base + ".creative", Arrays.asList(st.creativeStash));
            if (st.savedArmor != null) yaml.set(base + ".saved-armor", Arrays.asList(st.savedArmor));
        }
        try {
            LegacyCompat.saveYamlAtomically(yaml, inventoriesFile());
        } catch (IOException error) {
            plugin.getLogger().warning("Could not save creative-inventories-legacy.yml: " + error.getMessage());
        }
    }

    private static ItemStack[] readItemArray(List<?> raw) {
        if (raw == null || raw.isEmpty()) return null;
        ItemStack[] out = new ItemStack[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            Object item = raw.get(i);
            if (item instanceof ItemStack) out[i] = ((ItemStack) item).clone();
        }
        return out;
    }

    private void loadPlacedBlocks() {
        placedBlocks.clear();
        placedBlocksDirty = false;
        File file = placedBlocksFile();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection worlds = yaml.getConfigurationSection("worlds");
        if (worlds == null) return;
        for (String worldName : worlds.getKeys(false)) {
            ConfigurationSection wSec = worlds.getConfigurationSection(worldName);
            if (wSec == null) continue;
            Map<Long, int[]> map = new HashMap<Long, int[]>();
            for (String chunkKey : wSec.getKeys(false)) {
                String[] parts = chunkKey.split(",");
                if (parts.length != 2) continue;
                try {
                    long ck = packChunk(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    List<Integer> ints = wSec.getIntegerList(chunkKey);
                    if (ints.isEmpty()) continue;
                    int[] arr = new int[ints.size()];
                    for (int i = 0; i < ints.size(); i++) arr[i] = ints.get(i).intValue();
                    Arrays.sort(arr);
                    map.put(Long.valueOf(ck), arr);
                } catch (NumberFormatException ignored) {
                }
            }
            if (!map.isEmpty()) placedBlocks.put(worldName, map);
        }
    }

    private void savePlacedBlocks() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Map<Long, int[]>> worldEntry : placedBlocks.entrySet()) {
            for (Map.Entry<Long, int[]> chunkEntry : worldEntry.getValue().entrySet()) {
                int[] arr = chunkEntry.getValue();
                if (arr == null || arr.length == 0) continue;
                long ck = chunkEntry.getKey().longValue();
                int cx = (int) (ck >> 32);
                int cz = (int) ck;
                List<Integer> list = new ArrayList<Integer>(arr.length);
                for (int v : arr) list.add(Integer.valueOf(v));
                yaml.set("worlds." + worldEntry.getKey() + "." + cx + "," + cz, list);
            }
        }
        try {
            LegacyCompat.saveYamlAtomically(yaml, placedBlocksFile());
            placedBlocksDirty = false;
        } catch (IOException error) {
            plugin.getLogger().warning("Could not save creative-placed-blocks-legacy.yml: " + error.getMessage());
        }
    }

    private static Object buildRedDustOptions() {
        try {
            Class<?> dustClass = Class.forName("org.bukkit.Particle$DustOptions");
            return dustClass.getConstructor(Color.class, float.class).newInstance(Color.RED, Float.valueOf(1.0f));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethodByName(Class<?> type, String name, int paramCount) {
        try {
            for (Method m : type.getMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
