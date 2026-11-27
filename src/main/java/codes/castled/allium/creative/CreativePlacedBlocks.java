package codes.castled.allium.creative;

import codes.castled.allium.PluginStart;
import codes.castled.allium.message.Messages;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
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
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CreativeManager's loot protection: blocks placed in creative mode are remembered, so they never
 * turn into survival loot. Marks live in each chunk's own persistent data (a sorted int array of
 * packed in-chunk positions), so they travel with the world and need no database.
 *
 * <p>A player not in creative who breaks a marked block gets no drops (or, with {@code deny}, cannot
 * break it) unless they hold {@value #PERMISSION_LOOT}. Explosions, liquids, decay and falling
 * remove marked blocks without drops; pistons and falling blocks carry the mark; plants and trees
 * grown from marked blocks are marked; golems and withers built from them are refused.
 */
public final class CreativePlacedBlocks implements Listener {

    public static final String PERMISSION_LOOT = "allium.gamemode.creative.loot";

    record Settings(boolean enabled, boolean deny, boolean preventMobBuilds) {
        static Settings read(@Nullable ConfigurationSection c) {
            if (c == null) return new Settings(true, false, true);
            String mode = c.getString("survival-break", "no-drops").toLowerCase(Locale.ROOT);
            return new Settings(c.getBoolean("enabled", true), mode.equals("deny"), c.getBoolean("prevent-mob-builds", true));
        }
    }

    private final PluginStart plugin;
    private final NamespacedKey key;
    private final NamespacedKey fallingKey;
    private volatile Settings settings;

    public CreativePlacedBlocks(PluginStart plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(codes.castled.allium.PluginStart.bukkitPlugin(plugin), "creative_blocks");
        this.fallingKey = new NamespacedKey(codes.castled.allium.PluginStart.bukkitPlugin(plugin), "creative_falling");
        reload();
    }

    public void reload() {
        settings = Settings.read(plugin.getConfig().getConfigurationSection("creative-manager.placed-blocks"));
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, codes.castled.allium.PluginStart.bukkitPlugin(plugin));
    }

    public void disable() {
        HandlerList.unregisterAll(this);
    }

    // -- Storage ---------------------------------------------------------------------------------------

    /** A block's position inside its chunk: x and z in 4 bits each, y in the 16 bits above. */
    static int pack(int x, int y, int z) {
        return (y << 8) | ((z & 15) << 4) | (x & 15);
    }

    private int[] read(Chunk chunk) {
        int[] stored = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        return stored == null ? new int[0] : stored;
    }

    private void write(Chunk chunk, int[] marks) {
        if (marks.length == 0) chunk.getPersistentDataContainer().remove(key);
        else chunk.getPersistentDataContainer().set(key, PersistentDataType.INTEGER_ARRAY, marks);
    }

    /** Whether the block was placed in creative mode (and has not changed hands since). */
    public boolean marked(Block block) {
        if (!settings.enabled()) return false;
        return Arrays.binarySearch(read(block.getChunk()), pack(block.getX(), block.getY(), block.getZ())) >= 0;
    }

    void mark(Block block) {
        Chunk chunk = block.getChunk();
        write(chunk, with(read(chunk), pack(block.getX(), block.getY(), block.getZ())));
    }

    void unmark(Block block) {
        Chunk chunk = block.getChunk();
        int[] marks = read(chunk);
        int[] next = without(marks, pack(block.getX(), block.getY(), block.getZ()));
        if (next != marks) write(chunk, next);
    }

    /** {@code marks} (sorted) with {@code value} added. */
    static int[] with(int[] marks, int value) {
        int at = Arrays.binarySearch(marks, value);
        if (at >= 0) return marks;
        int insert = -at - 1;
        int[] next = new int[marks.length + 1];
        System.arraycopy(marks, 0, next, 0, insert);
        next[insert] = value;
        System.arraycopy(marks, insert, next, insert + 1, marks.length - insert);
        return next;
    }

    /** {@code marks} (sorted) without {@code value}; the same array when it was not there. */
    static int[] without(int[] marks, int value) {
        int at = Arrays.binarySearch(marks, value);
        if (at < 0) return marks;
        int[] next = new int[marks.length - 1];
        System.arraycopy(marks, 0, next, 0, at);
        System.arraycopy(marks, at + 1, next, at, marks.length - at - 1);
        return next;
    }

    /** The block and, for doors, tall plants and beds, its other half. */
    static List<Block> structure(Block block) {
        List<Block> parts = new ArrayList<>(2);
        parts.add(block);
        BlockData data = block.getBlockData();
        if (data instanceof Bed bed) {
            parts.add(block.getRelative(bed.getPart() == Bed.Part.HEAD ? bed.getFacing().getOppositeFace() : bed.getFacing()));
        } else if (data instanceof Bisected half) {
            parts.add(block.getRelative(half.getHalf() == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN));
        }
        return parts;
    }

    private static boolean creative(@Nullable Player player) {
        return player != null && player.getGameMode() == GameMode.CREATIVE;
    }

    // -- Placing and breaking ------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!settings.enabled()) return;
        if (creative(event.getPlayer())) {
            for (Block part : structure(event.getBlockPlaced())) mark(part);
        } else if (marked(event.getBlockPlaced())) {
            // Something replaced a marked spot (a block placed into a marked liquid or plant).
            unmark(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!marked(block)) return;
        Player player = event.getPlayer();
        if (creative(player) || player.hasPermission(PERMISSION_LOOT)) return;
        if (settings.deny()) {
            event.setCancelled(true);
            plugin.getMessageManager().send(player, Messages.CREATIVE_RESTRICTED, "{action}", "break blocks placed in creative mode");
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent event) {
        if (!settings.enabled()) return;
        for (Block part : structure(event.getBlock())) unmark(part);
    }

    // -- Blocks removed without a player ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        removeMarked(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        removeMarked(event.blockList());
    }

    /** Marked blocks leave the explosion's list and vanish, so they drop nothing. */
    private void removeMarked(List<Block> blocks) {
        if (!settings.enabled() || blocks.isEmpty()) return;
        Map<Chunk, int[]> cache = new HashMap<>();
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block block = it.next();
            int[] marks = cache.computeIfAbsent(block.getChunk(), this::read);
            if (Arrays.binarySearch(marks, pack(block.getX(), block.getY(), block.getZ())) < 0) continue;
            it.remove();
            block.setType(Material.AIR, false);
            cache.put(block.getChunk(), without(marks, pack(block.getX(), block.getY(), block.getZ())));
        }
        cache.forEach((chunk, marks) -> write(chunk, marks));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        // Only what a liquid would wash away (torches, rails, redstone...) can drop.
        if (to.getType().isAir() || to.isLiquid() || !marked(to)) return;
        event.setCancelled(true);
        to.setType(Material.AIR, false);
        unmark(to);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        if (!marked(event.getBlock())) return;
        event.setCancelled(true);
        event.getBlock().setType(Material.AIR, false);
        unmark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (settings.enabled()) unmark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (settings.enabled() && event.getNewState().getType().isAir()) unmark(event.getBlock());
    }

    /** Endermen and the like cannot carry marked blocks off; falling blocks keep their mark. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (!settings.enabled()) return;
        Block block = event.getBlock();
        if (event.getEntity() instanceof FallingBlock falling) {
            if (event.getTo().isAir()) {
                // Starting to fall: the entity carries the mark and drops nothing if it breaks.
                if (marked(block)) {
                    unmark(block);
                    falling.getPersistentDataContainer().set(fallingKey, PersistentDataType.BYTE, (byte) 1);
                    falling.setDropItem(false);
                }
            } else if (falling.getPersistentDataContainer().has(fallingKey, PersistentDataType.BYTE)) {
                mark(block);
            }
            return;
        }
        if (event.getTo().isAir() && marked(block)) {
            event.setCancelled(true);
        }
    }

    // -- Pistons ---------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    /** Moved marked blocks keep their mark; those a piston breaks vanish without drops. */
    private void move(List<Block> blocks, BlockFace direction) {
        if (!settings.enabled() || blocks.isEmpty()) return;
        List<Block> carried = new ArrayList<>();
        for (Block block : blocks) {
            if (!marked(block)) continue;
            unmark(block);
            if (block.getPistonMoveReaction() == org.bukkit.block.PistonMoveReaction.BREAK) {
                block.setType(Material.AIR, false);
            } else {
                carried.add(block.getRelative(direction));
            }
        }
        for (Block landed : carried) mark(landed);
    }

    // -- Growth ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (!settings.enabled()) return;
        Block block = event.getBlock();
        Material grown = event.getNewState().getType();
        if (grown == Material.CACTUS || grown == Material.SUGAR_CANE || grown == Material.BAMBOO || grown == Material.KELP_PLANT
                || grown == Material.KELP) {
            if (marked(block.getRelative(BlockFace.DOWN))) mark(block);
        } else if (grown == Material.PUMPKIN || grown == Material.MELON) {
            for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                Block stem = block.getRelative(face);
                if (stem.getType().name().endsWith("STEM") && marked(stem)) {
                    mark(block);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (!marked(event.getLocation().getBlock())) return;
        for (BlockState state : event.getBlocks()) mark(state.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (marked(event.getSource())) mark(event.getBlock());
    }

    // -- Golems and withers --------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBuiltMob(CreatureSpawnEvent event) {
        if (!settings.enabled() || !settings.preventMobBuilds()) return;
        List<int[]> body = buildShape(event.getSpawnReason());
        if (body.isEmpty()) return;
        Block base = event.getLocation().getBlock();
        for (int[] offset : body) {
            if (marked(base.getRelative(offset[0], offset[1], offset[2]))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** The blocks of a built mob around its spawn block, as CreativeManager checks them. */
    static List<int[]> buildShape(CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason.name()) {
            case "BUILD_SNOWMAN" -> List.of(new int[]{0, 0, 0}, new int[]{0, 1, 0}, new int[]{0, 2, 0});
            case "BUILD_IRONGOLEM" -> List.of(new int[]{0, 0, 0}, new int[]{0, 1, 0}, new int[]{0, 2, 0},
                    new int[]{0, 1, 1}, new int[]{0, 1, -1}, new int[]{1, 1, 0}, new int[]{-1, 1, 0});
            case "BUILD_WITHER" -> List.of(new int[]{0, 0, 0}, new int[]{0, 1, 0}, new int[]{0, 2, 0},
                    new int[]{0, 1, 1}, new int[]{0, 2, 1}, new int[]{0, 1, -1}, new int[]{0, 2, -1},
                    new int[]{1, 1, 0}, new int[]{1, 2, 0}, new int[]{-1, 1, 0}, new int[]{-1, 2, 0});
            default -> List.of();
        };
    }
}
