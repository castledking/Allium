package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.module.ToolsModule;
import codes.castled.allium.tool.felltree.FellTreeBehavior;
import codes.castled.allium.tool.felltree.LazyAxeFellEvent;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * S10 Lazy Axe and carrier suppression cases. Builds trees and stations from the console and
 * records what the server did after the real bot mined a log or used a block. It never breaks a
 * log or clicks a slot itself.
 */
final class ToolsGate implements Listener {

    private static final String BOT = "GateBot";
    private static final int R = 12, H = 50;

    private final JavaPlugin plugin;
    private final ItemService items;
    private final BiConsumer<String, Object> writer;
    private int tick;
    private long seq;

    // The current Lazy Axe case.
    private int caseX, caseZ, ground;
    private final List<int[]> caseLogs = new ArrayList<>();
    private int caseLeaves;
    /** [seq, tick, x, y, z, byFell (1/0)] for each of the bot's block breaks in this case. */
    private final List<long[]> breaks = new ArrayList<>();
    private final List<Map<String, Object>> fells = new ArrayList<>();
    private long heldSeq = -1, quitSeq = -1, unloadSeq = -1, itemBreakSeq = -1;
    private final Set<Long> watchedChunks = new HashSet<>();
    private String unloadArm;
    private int unloadAfter;
    private final List<String> unloadLog = new ArrayList<>();
    // Suppression cases.
    private final List<Map<String, Object>> moves = new ArrayList<>();
    private final List<Map<String, Object>> enchantOffers = new ArrayList<>();
    private final Map<String, Location> stations = new LinkedHashMap<>();

    ToolsGate(JavaPlugin plugin, ItemService items, BiConsumer<String, Object> writer) {
        this.plugin = plugin;
        this.items = items;
        this.writer = writer;
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick++, 1L, 1L);
        // Vanilla has no smithing recipe for these carriers (the netherite upgrade takes diamond
        // gear), so the gate adds one, as a server's custom recipe would: golden hoe -> netherite hoe.
        plugin.getServer().addRecipe(new org.bukkit.inventory.SmithingTransformRecipe(new NamespacedKey(plugin, "gate_hoe_upgrade"),
                new ItemStack(Material.NETHERITE_HOE), new org.bukkit.inventory.RecipeChoice.MaterialChoice(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new org.bukkit.inventory.RecipeChoice.MaterialChoice(Material.GOLDEN_HOE),
                new org.bukkit.inventory.RecipeChoice.MaterialChoice(Material.NETHERITE_INGOT)));
    }

    boolean handle(String[] args) throws Exception {
        switch (args[0]) {
            case "la-arena" -> arena(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]), args.length > 4 && args[4].equals("keep"));
            case "la-tree" -> tree(args[1], args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]), Long.parseLong(args[5]));
            case "la-build" -> build(args[1], args[2]);
            case "la-find" -> find(args[1], Integer.parseInt(args[2]));
            case "la-clear" -> { bot().getInventory().clear(); bot().getInventory().setHeldItemSlot(0); }
            case "la-give" -> give(args[1], args[2], Integer.parseInt(args[3]), args.length > 4 ? args[4] : "-");
            case "la-observe" -> writer.accept(args[1] + "-observe", observe());
            case "la-claim" -> writer.accept(args[1], claim(Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]), Integer.parseInt(args[5])));
            case "la-unload-after" -> { unloadArm = "unload"; unloadAfter = Integer.parseInt(args[1]); }
            case "la-return" -> bot().teleport(new Location(bot().getWorld(), caseX - 2.5, ground + 1, caseZ + 0.5, -90F, 0F));
            case "la-level" -> bot().setLevel(Integer.parseInt(args[1]));
            case "sp-stations" -> stations(args[1]);
            case "sp-observe-stations" -> writer.accept(args[1], observeStations());
            case "sp-craft" -> writer.accept(args[1], craft(args[2], args[3]));
            case "sp-block" -> block(args[1], args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            case "sp-menu" -> menu(args[1], args[2], args.length > 3 ? args[3] : "-", args.length > 4 ? args[4] : "-");
            case "sp-view" -> writer.accept(args[1], view());
            case "sp-enchanting" -> enchanting(args[1]);
            case "sp-snapshot" -> writer.accept(args[1], inventory());
            case "la-debug" -> writer.accept(args[1], debug());
            default -> { return false; }
        }
        return true;
    }

    // -- Lazy Axe ------------------------------------------------------------------------------

    /**
     * A cleared 25x25 arena around (x, z) on the flat ground; the bot stands 3 blocks west of it.
     * With keep, an arena from before a restart is selected again as it is.
     */
    private void arena(String name, int x, int z, boolean keep) {
        World world = bot().getWorld();
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0); // leaves must not decay during a case
        Location stand = new Location(world, x - 2.5, 0, z + 0.5, -90F, 0F);
        bot().teleport(stand); // loads the arena's chunks around the bot first
        ground = world.getHighestBlockYAt(x, z - 30); // outside every arena: the flat ground
        stand.setY(ground + 1);
        for (int dx = -R; dx <= R && !keep; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                for (int y = ground + 1; y <= ground + H; y++) {
                    Block b = world.getBlockAt(x + dx, y, z + dz);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
                world.getBlockAt(x + dx, ground, z + dz).setType(Material.GRASS_BLOCK, false);
            }
        }
        for (Entity e : world.getNearbyEntities(new Location(world, x, ground + H / 2.0, z), R + 4, H, R + 4)) {
            if (e instanceof Item && !keep) e.remove();
        }
        bot().teleport(stand);
        if (!keep) bot().getInventory().clear();
        caseX = x;
        caseZ = z;
        caseLogs.clear();
        breaks.clear();
        fells.clear();
        heldSeq = quitSeq = unloadSeq = itemBreakSeq = -1;
        watchedChunks.clear();
        unloadArm = null;
        unloadLog.clear();
        caseLeaves = 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ground", ground);
        out.put("stand", List.of(stand.getX(), stand.getY(), stand.getZ()));
        writer.accept(name + "-arena", out);
    }

    /** Grows a tree with a seeded random at (x, z), or with kind "existing" only scans the arena. */
    private void tree(String name, String kind, int x, int z, long seed) {
        World world = bot().getWorld();
        Location at = new Location(world, x, ground + 1, z);
        boolean grown = true;
        if (!kind.equals("existing")) {
            grown = world.generateTree(at, new Random(seed), TreeType.valueOf(kind.toUpperCase(Locale.ROOT)));
        }
        scan();
        boolean heartPlaced = false;
        if (kind.equals("pale_oak_creaking") && count("creaking_heart") == 0) {
            // The seeded tree grew without its heart: set one into the trunk, as the decorator does
            // (a trunk log replaced by a creaking heart).
            Block trunk = world.getBlockAt(x, ground + 3, z);
            if (isLog(trunk.getType().name().toLowerCase(Locale.ROOT))) {
                trunk.setType(Material.valueOf("CREAKING_HEART"), false);
                heartPlaced = true;
                scan();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("grown", grown);
        out.put("heartPlacedByFixture", heartPlaced);
        out.put("logs", caseLogs.size());
        out.put("logPositions", caseLogs);
        out.put("materials", materials());
        out.put("leaves", caseLeaves);
        out.put("persistentLeaves", leaves(true));
        out.put("creakingHearts", count("creaking_heart"));
        out.put("lowest", caseLogs.stream().mapToInt(p -> p[1]).min().orElse(0));
        out.put("chunks", watchedChunks.size());
        writer.accept(name + "-tree", out);
    }

    /**
     * Finds the natural (worldgen) tree nearest the bot in a vanilla-terrain world: a log on dirt or
     * grass with logs above it and natural leaves near its top. Selects it as the case (not cleared)
     * and stands the bot on free ground next to its trunk.
     */
    private void find(String name, int radius) {
        Player bot = bot();
        World world = bot.getWorld();
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0); // leaves must not decay during a case
        Location origin = bot.getLocation();
        int[] best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = origin.getBlockX() + dx, z = origin.getBlockZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = world.getHighestBlockYAt(x, z); y > world.getMinHeight() + 1; y--) {
                    Block b = world.getBlockAt(x, y, z);
                    String m = b.getType().name().toLowerCase(Locale.ROOT);
                    if (!isLog(m)) continue;
                    String below = world.getBlockAt(x, y - 1, z).getType().name().toLowerCase(Locale.ROOT);
                    if (isLog(below)) continue;
                    boolean ground = List.of("dirt", "grass_block", "podzol", "coarse_dirt", "rooted_dirt", "mycelium", "moss_block").contains(below);
                    boolean trunk = isLog(world.getBlockAt(x, y + 1, z).getType().name().toLowerCase(Locale.ROOT))
                            && isLog(world.getBlockAt(x, y + 2, z).getType().name().toLowerCase(Locale.ROOT));
                    double d = dx * dx + dz * dz;
                    if (ground && trunk && d < bestDist && stand(world, x, y, z) != null) {
                        best = new int[]{x, y, z};
                        bestDist = d;
                    }
                    break;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", best != null);
        if (best != null) {
            caseX = best[0];
            caseZ = best[2];
            ground = best[1] - 1;
            Location stand = stand(world, best[0], best[1], best[2]);
            bot.teleport(stand);
            breaks.clear();
            fells.clear();
            scan();
            out.put("trunk", List.of(best[0], best[1], best[2]));
            out.put("stand", List.of(stand.getX(), stand.getY(), stand.getZ()));
            out.put("species", world.getBlockAt(best[0], best[1], best[2]).getType().name());
            out.put("biome", world.getBlockAt(best[0], best[1], best[2]).getBiome().toString());
            out.put("logs", caseLogs.size());
            out.put("logPositions", caseLogs);
            out.put("leaves", caseLeaves);
            out.put("persistentLeaves", leaves(true));
        }
        writer.accept(name + "-tree", out);
    }

    /** Free standing room (feet and head air, solid floor) two blocks from a trunk, or null. */
    private static Location stand(World world, int x, int y, int z) {
        for (int[] d : new int[][]{{-2, 0}, {2, 0}, {0, -2}, {0, 2}, {-2, -1}, {2, 1}}) {
            Block feet = world.getBlockAt(x + d[0], y, z + d[1]);
            if (feet.getType().isAir() && feet.getRelative(BlockFace.UP).getType().isAir() && feet.getRelative(BlockFace.DOWN).getType().isSolid()) {
                float yaw = d[0] < 0 ? -90F : d[0] > 0 ? 90F : d[1] < 0 ? 0F : 180F;
                return new Location(world, feet.getX() + 0.5, y, feet.getZ() + 0.5, yaw, 0F);
            }
        }
        return null;
    }

    /**
     * Player-style builds: "logs" (a bare oak log frame, no leaves), "persistent" (an oak trunk with
     * a crown of player-placed, persistent leaves), "wall" (planks touching an existing tree's trunk).
     */
    private void build(String name, String kind) {
        World world = bot().getWorld();
        int x = caseX, z = caseZ, y0 = ground + 1;
        switch (kind) {
            case "logs" -> {
                for (int y = y0; y < y0 + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.OAK_LOG, false);
                    world.getBlockAt(x + 2, y, z).setType(Material.OAK_LOG, false);
                }
                for (int dx = 0; dx <= 2; dx++) world.getBlockAt(x + dx, y0 + 4, z).setType(Material.OAK_LOG, false);
            }
            case "persistent" -> {
                for (int y = y0; y < y0 + 5; y++) world.getBlockAt(x, y, z).setType(Material.OAK_LOG, false);
                for (int y = y0 + 3; y <= y0 + 5; y++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            Block b = world.getBlockAt(x + dx, y, z + dz);
                            if (!b.getType().isAir()) continue;
                            b.setType(Material.OAK_LEAVES, false);
                            Leaves data = (Leaves) b.getBlockData();
                            data.setPersistent(true);
                            b.setBlockData(data, false);
                        }
                    }
                }
            }
            case "wall" -> world.getBlockAt(x, y0 + 1, z - 1).setType(Material.OAK_PLANKS, false);
            default -> throw new IllegalArgumentException(kind);
        }
        tree(name, "existing", x, z, 0L);
    }

    private void scan() {
        World world = bot().getWorld();
        caseLogs.clear();
        caseLeaves = 0;
        watchedChunks.clear();
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                for (int y = ground + 1; y <= ground + H; y++) {
                    Block b = world.getBlockAt(caseX + dx, y, caseZ + dz);
                    String m = b.getType().name().toLowerCase(Locale.ROOT);
                    if (isLog(m)) {
                        caseLogs.add(new int[]{b.getX(), b.getY(), b.getZ()});
                        watchedChunks.add(FellTreeBehavior.chunkKey(b.getX() >> 4, b.getZ() >> 4));
                    } else if (m.endsWith("_leaves")) {
                        caseLeaves++;
                    }
                }
            }
        }
    }

    private static boolean isLog(String m) {
        return m.endsWith("_log") || m.endsWith("_wood") || m.endsWith("_stem") || m.endsWith("_hyphae");
    }

    private Map<String, Integer> materials() {
        Map<String, Integer> out = new LinkedHashMap<>();
        World world = bot().getWorld();
        for (int[] p : caseLogs) out.merge(world.getBlockAt(p[0], p[1], p[2]).getType().name(), 1, Integer::sum);
        return out;
    }

    private int leaves(boolean persistentOnly) {
        int n = 0;
        World world = bot().getWorld();
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                for (int y = ground + 1; y <= ground + H; y++) {
                    BlockData data = world.getBlockAt(caseX + dx, y, caseZ + dz).getBlockData();
                    if (data instanceof Leaves l && (!persistentOnly || l.isPersistent())) n++;
                }
            }
        }
        return n;
    }

    private int count(String material) {
        int n = 0;
        World world = bot().getWorld();
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                for (int y = ground + 1; y <= ground + H; y++) {
                    if (world.getBlockAt(caseX + dx, y, caseZ + dz).getType().name().equalsIgnoreCase(material)) n++;
                }
            }
        }
        return n;
    }

    /**
     * Puts an axe in the bot's main hand (slot 0) and a stick in slot 1. spec: a tool identity
     * (allium:lazy_axe, allium:gate_axe_slow, ...), "legacy" (a pre-recode tree_axe) or "plain".
     * left: the durability left, or -1 for a fresh axe. enchants: "unbreaking:3,efficiency:5" or "-".
     */
    private void give(String name, String spec, int left, String enchants) {
        Player bot = bot();
        ItemStack axe;
        if (spec.equals("legacy")) {
            axe = new ItemStack(Material.NETHERITE_AXE);
            ItemMeta meta = axe.getItemMeta();
            meta.setDisplayName("Old Tree Axe");
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(new NamespacedKey("allium", "custom_item_id"), PersistentDataType.STRING, "tree_axe");
            pdc.set(new NamespacedKey("nexo", "id"), PersistentDataType.STRING, "tree_axe");
            ((Repairable) meta).setRepairCost(7);
            axe.setItemMeta(meta);
        } else if (spec.equals("plain")) {
            axe = new ItemStack(Material.NETHERITE_AXE);
        } else {
            ItemRef bound = items.refForKey(ProviderKey.parse(spec));
            ResolutionResult result = items.create(bound.behaviorId().isPresent() ? bound : bound.withBehaviorId(spec));
            if (!result.isResolved()) throw new IllegalStateException(spec + ": " + result.diagnosticMessage());
            axe = result.itemStack().orElseThrow();
        }
        ItemMeta meta = axe.getItemMeta();
        if (!enchants.equals("-")) {
            for (String part : enchants.split(",")) {
                String[] kv = part.split(":");
                meta.addEnchant(enchantment(kv[0]), Integer.parseInt(kv[1]), true);
            }
        }
        if (left >= 0) ((Damageable) meta).setDamage(axe.getType().getMaxDurability() - left);
        axe.setItemMeta(meta);
        bot.getInventory().setItem(0, axe);
        bot.getInventory().setItem(1, new ItemStack(Material.STICK));
        bot.getInventory().setHeldItemSlot(0);
        breaks.clear();
        fells.clear();
        heldSeq = quitSeq = unloadSeq = itemBreakSeq = -1;
        writer.accept(name, snapshot(bot.getInventory().getItemInMainHand()));
    }

    @SuppressWarnings("deprecation")
    private static Enchantment enchantment(String key) {
        Enchantment e = Enchantment.getByKey(NamespacedKey.minecraft(key));
        if (e == null) throw new IllegalArgumentException("no enchantment " + key);
        return e;
    }

    private Map<String, Object> observe() {
        Player bot = bot();
        World world = bot.getWorld();
        Map<String, Object> out = new LinkedHashMap<>();
        List<int[]> left = new ArrayList<>();
        for (int[] p : caseLogs) {
            if (isLog(world.getBlockAt(p[0], p[1], p[2]).getType().name().toLowerCase(Locale.ROOT))) left.add(p);
        }
        out.put("logs", caseLogs.size());
        out.put("logsLeft", left.size());
        out.put("leftPositions", left);
        out.put("leaves", leaves(false));
        out.put("leavesBefore", caseLeaves);
        // Log drops: on the ground near the tree, plus what the bot picked up.
        Map<String, Integer> drops = new LinkedHashMap<>();
        for (Entity e : world.getNearbyEntities(new Location(world, caseX, ground + H / 2.0, caseZ), R + 4, H, R + 4)) {
            if (e instanceof Item item && isLog(item.getItemStack().getType().name().toLowerCase(Locale.ROOT))) {
                drops.merge(item.getItemStack().getType().name(), item.getItemStack().getAmount(), Integer::sum);
            }
        }
        for (ItemStack s : bot.getInventory().getContents()) {
            if (s != null && isLog(s.getType().name().toLowerCase(Locale.ROOT))) drops.merge(s.getType().name(), s.getAmount(), Integer::sum);
        }
        out.put("drops", drops);
        out.put("dropTotal", drops.values().stream().mapToInt(Integer::intValue).sum());
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<Long, Integer> byTick = new LinkedHashMap<>();
        int byFell = 0;
        for (long[] b : breaks) {
            rows.add(Map.of("seq", b[0], "tick", b[1], "pos", List.of(b[2], b[3], b[4]), "byFell", b[5] == 1));
            if (b[5] == 1) {
                byFell++;
                byTick.merge(b[1], 1, Integer::sum);
            }
        }
        out.put("breakEvents", breaks.size());
        out.put("breaksByFell", byFell);
        out.put("maxPerTick", byTick.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        out.put("fellTicks", byTick.size());
        out.put("firstFellTick", breaks.stream().filter(b -> b[5] == 1).mapToLong(b -> b[1]).min().orElse(-1));
        out.put("lastFellTick", breaks.stream().filter(b -> b[5] == 1).mapToLong(b -> b[1]).max().orElse(-1));
        out.put("breaks", rows);
        out.put("fells", fells);
        out.put("heldSeq", heldSeq);
        out.put("quitSeq", quitSeq);
        out.put("unloadSeq", unloadSeq);
        out.put("unloadLog", unloadLog);
        out.put("itemBreakSeq", itemBreakSeq);
        out.put("lastBreakSeq", breaks.isEmpty() ? -1 : breaks.get(breaks.size() - 1)[0]);
        out.put("sneaking", bot.isSneaking());
        out.put("main", snapshot(bot.getInventory().getItemInMainHand()));
        out.put("slot0", snapshot(bot.getInventory().getItem(0)));
        out.put("heldSlot", bot.getInventory().getHeldItemSlot());
        out.put("felling", fellTree() != null && fellTree().activeJobs().containsKey(bot.getUniqueId()));
        return out;
    }

    private FellTreeBehavior fellTree() {
        Object module = ((PluginStart) plugin.getServer().getPluginManager().getPlugin("Allium")).getModuleManager().getModule("tools");
        return module instanceof ToolsModule tools ? tools.fellTree() : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!event.getPlayer().getName().equals(BOT)) return;
        Block b = event.getBlock();
        FellTreeBehavior behavior = fellTree();
        // isFelling: the break was caused by a fell (Allium's re-entry guard is set around it).
        boolean byFell = behavior != null && behavior.isFelling(event.getPlayer().getUniqueId());
        breaks.add(new long[]{++seq, tick, b.getX(), b.getY(), b.getZ(), byFell ? 1 : 0});
        if ("unload".equals(unloadArm) && breaks.stream().filter(r -> r[5] == 1).count() == unloadAfter) {
            unloadArm = null;
            plugin.getServer().getScheduler().runTask(plugin, this::leaveAndUnload);
        }
    }

    /** Takes the bot far away, then asks the server to unload the tree's chunks until one goes. */
    private void leaveAndUnload() {
        Player bot = bot();
        World world = bot.getWorld();
        bot.teleport(new Location(world, caseX + 3000.5, ground + 1, caseZ + 0.5));
        unloadLog.add("tick " + tick + ": bot teleported away");
        int[] attempts = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            attempts[0]++;
            for (long key : watchedChunks) {
                int cx = (int) (key >> 32), cz = (int) key;
                if (world.isChunkLoaded(cx, cz)) {
                    boolean ok = world.unloadChunk(cx, cz, true);
                    if (ok) unloadLog.add("tick " + tick + ": unloadChunk(" + cx + "," + cz + ") succeeded");
                }
            }
            if (unloadSeq >= 0 || attempts[0] > 200) {
                unloadLog.add("tick " + tick + ": stop after " + attempts[0] + " attempts, unloadSeq " + unloadSeq);
                task.cancel();
            }
        }, 1L, 1L);
    }

    /** Logged so a lane whose client never starts mining shows where it stops. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(org.bukkit.event.block.BlockDamageEvent event) {
        if (event.getPlayer().getName().equals(BOT)) {
            plugin.getLogger().info("S06A-DIG damage " + event.getBlock().getType() + " cancelled=" + event.isCancelled() + " instaBreak=" + event.getInstaBreak());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeftClick(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getPlayer().getName().equals(BOT) && event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            plugin.getLogger().info("S06A-DIG left-click " + event.getClickedBlock().getType() + " denied=" + (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFell(LazyAxeFellEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", ++seq);
        row.put("tick", tick);
        row.put("tool", event.getToolId());
        row.put("logs", event.getLogs());
        row.put("breakAsPlayer", event.isBreakAsPlayer());
        row.put("first", List.of(event.getFirstLog().getX(), event.getFirstLog().getY(), event.getFirstLog().getZ()));
        fells.add(row);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        if (event.getPlayer().getName().equals(BOT) && heldSeq < 0) heldSeq = ++seq;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer().getName().equals(BOT) && quitSeq < 0) quitSeq = ++seq;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        if (event.getPlayer().getName().equals(BOT) && itemBreakSeq < 0) itemBreakSeq = ++seq;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(ChunkUnloadEvent event) {
        long key = FellTreeBehavior.chunkKey(event.getChunk().getX(), event.getChunk().getZ());
        if (watchedChunks.contains(key) && unloadSeq < 0) {
            unloadSeq = ++seq;
            unloadLog.add("tick " + tick + ": ChunkUnloadEvent " + event.getChunk().getX() + "," + event.getChunk().getZ());
        }
    }

    /** An admin claim (no owner, so the bot has no trust) over x1..x2, z1..z2, through GriefPrevention's API. */
    private Map<String, Object> claim(int x1, int x2, int z1, int z2) throws Exception {
        org.bukkit.plugin.Plugin gp = plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
        if (gp == null) throw new IllegalStateException("GriefPrevention is not installed");
        ClassLoader loader = gp.getClass().getClassLoader();
        Class<?> main = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention", true, loader);
        Class<?> claim = Class.forName("me.ryanhamshire.GriefPrevention.Claim", true, loader);
        Object dataStore = main.getField("dataStore").get(main.getField("instance").get(null));
        World world = bot().getWorld();
        java.lang.reflect.Method create = dataStore.getClass().getMethod("createClaim", World.class, int.class, int.class,
                int.class, int.class, int.class, int.class, java.util.UUID.class, claim, Long.class, Player.class);
        Object result = create.invoke(dataStore, world, x1, x2, world.getMinHeight(), world.getMaxHeight() - 1, z1, z2,
                null, null, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plugin", gp.getName() + " " + gp.getDescription().getVersion());
        out.put("succeeded", result.getClass().getField("succeeded").getBoolean(result));
        out.put("bounds", List.of(x1, x2, z1, z2));
        return out;
    }

    // -- Carrier suppression -------------------------------------------------------------------

    private ItemStack tool(String identity) {
        ItemRef bound = items.refForKey(ProviderKey.parse(identity));
        ResolutionResult result = items.create(bound.behaviorId().isPresent() ? bound : bound.withBehaviorId(identity));
        if (!result.isResolved()) throw new IllegalStateException(identity + ": " + result.diagnosticMessage());
        return result.itemStack().orElseThrow();
    }

    private ItemStack item(String spec) {
        return switch (spec) {
            case "membrane-tool" -> tool("allium:phantom_obliterator");
            case "hoe-tool" -> tool("allium:mob_disarmer");
            case "axe-tool" -> tool("allium:lazy_axe");
            case "axe-tool-damaged" -> damaged(tool("allium:lazy_axe"), 500);
            case "membrane" -> new ItemStack(Material.PHANTOM_MEMBRANE);
            case "hoe" -> new ItemStack(Material.GOLDEN_HOE);
            case "axe-damaged" -> damaged(new ItemStack(Material.NETHERITE_AXE), 500);
            case "elytra-damaged" -> damaged(new ItemStack(Material.ELYTRA), 200);
            case "netherite-ingot" -> new ItemStack(Material.NETHERITE_INGOT);
            case "lapis" -> new ItemStack(Material.LAPIS_LAZULI, 3);
            case "axe-tool-enchanted" -> {
                ItemStack axe = tool("allium:lazy_axe");
                axe.addUnsafeEnchantment(enchantment("efficiency"), 1);
                yield axe;
            }
            case "template" -> new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            case "book-sharpness" -> {
                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
                meta.addStoredEnchant(enchantment("sharpness"), 3, true);
                book.setItemMeta(meta);
                yield book;
            }
            case "-" -> null;
            default -> throw new IllegalArgumentException(spec);
        };
    }

    private static ItemStack damaged(ItemStack stack, int damage) {
        ItemMeta meta = stack.getItemMeta();
        ((Damageable) meta).setDamage(damage);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Hopper-fed stations, one with a tool and one with the plain item each: a hopper above a
     * brewing stand, a furnace, a blast furnace and a smoker (and a crafter where it exists), plus a
     * fueled blast furnace and a fueled brewing stand with the item already inside (as a hotbar
     * swap would put it).
     */
    private void stations(String name) {
        Player bot = bot();
        World world = bot.getWorld();
        int bx = 6000, bz = 0;
        bot.teleport(new Location(world, bx - 4.5, 0, bz + 0.5));
        int gy = world.getHighestBlockYAt(bx - 30, bz - 30) + 1;
        bot.teleport(new Location(world, bx - 4.5, gy, bz + 0.5));
        moves.clear();
        stations.clear();
        int i = 0;
        List<String> types = new ArrayList<>(List.of("BREWING_STAND", "FURNACE", "BLAST_FURNACE", "SMOKER"));
        if (Material.getMaterial("CRAFTER") != null) types.add("CRAFTER");
        for (String type : types) {
            for (String variant : List.of("tool", "plain")) {
                Location at = new Location(world, bx + 3 * i, gy, bz + (variant.equals("tool") ? 0 : 3));
                at.getBlock().setType(Material.valueOf(type));
                Block hopper = at.clone().add(0, 1, 0).getBlock();
                hopper.setType(Material.HOPPER);
                org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) hopper.getBlockData();
                data.setFacing(BlockFace.DOWN);
                hopper.setBlockData(data);
                String item = switch (type) {
                    case "BREWING_STAND" -> "membrane";
                    case "CRAFTER" -> "axe";
                    default -> "hoe";
                };
                ItemStack stack = variant.equals("tool")
                        ? item(item.equals("axe") ? "axe-tool" : item + "-tool")
                        : (item.equals("axe") ? new ItemStack(Material.NETHERITE_AXE) : item(item));
                ((Container) hopper.getState()).getInventory().setItem(0, stack);
                stations.put("hopper:" + type + ":" + variant, at);
            }
            i++;
        }
        // Items already inside, fueled: the station's own processing must not consume a tool.
        for (String variant : List.of("tool", "plain")) {
            Location blast = new Location(world, bx + 3 * i, gy, bz + (variant.equals("tool") ? 0 : 3));
            blast.getBlock().setType(Material.BLAST_FURNACE);
            Inventory inv = ((Container) blast.getBlock().getState()).getInventory();
            inv.setItem(0, variant.equals("tool") ? item("hoe-tool") : item("hoe"));
            inv.setItem(1, new ItemStack(Material.COAL, 4));
            stations.put("smelt:BLAST_FURNACE:" + variant, blast);
            Location brew = new Location(world, bx + 3 * (i + 1), gy, bz + (variant.equals("tool") ? 0 : 3));
            brew.getBlock().setType(Material.BREWING_STAND);
            BrewingStand stand = (BrewingStand) brew.getBlock().getState();
            for (int slot = 0; slot < 3; slot++) {
                ItemStack potion = new ItemStack(Material.POTION);
                PotionMeta meta = (PotionMeta) potion.getItemMeta();
                setPotion(meta, org.bukkit.potion.PotionType.AWKWARD);
                potion.setItemMeta(meta);
                stand.getInventory().setItem(slot, potion);
            }
            stand.getInventory().setItem(3, variant.equals("tool") ? item("membrane-tool") : item("membrane"));
            stand.getInventory().setItem(4, new ItemStack(Material.BLAZE_POWDER, 4));
            stations.put("brew:BREWING_STAND:" + variant, brew);
        }
        // A crafter (1.21+) holding a Mob Disarmer and a golden hoe, the repair-combine recipe, and
        // its plain twin with two golden hoes; a redstone block below powers each one tick later.
        if (Material.getMaterial("CRAFTER") != null) {
            for (String variant : List.of("tool", "plain")) {
                Location at = new Location(world, bx + 3 * (i + 2), gy, bz + (variant.equals("tool") ? 0 : 3));
                at.getBlock().setType(Material.valueOf("CRAFTER"));
                Inventory grid = ((Container) at.getBlock().getState()).getInventory();
                grid.setItem(0, variant.equals("tool") ? item("hoe-tool") : damaged(item("hoe"), 10));
                grid.setItem(1, damaged(item("hoe"), 10));
                stations.put("craft:CRAFTER:" + variant, at);
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> at.clone().add(0, -1, 0).getBlock().setType(Material.REDSTONE_BLOCK), 5L);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stations", new ArrayList<>(stations.keySet()));
        writer.accept(name, out);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(InventoryMoveItemEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("item", event.getItem().getType().name());
        row.put("tool", items.identify(event.getItem()).flatMap(ItemRef::behaviorId).isPresent());
        row.put("destination", event.getDestination().getType().name());
        row.put("cancelled", event.isCancelled());
        if (moves.size() < 200) moves.add(row);
    }

    private Map<String, Object> observeStations() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Location> e : stations.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            Block station = e.getValue().getBlock();
            row.put("station", contents(((Container) station.getState()).getInventory()));
            if (e.getKey().startsWith("hopper:")) {
                row.put("hopper", contents(((Container) e.getValue().clone().add(0, 1, 0).getBlock().getState()).getInventory()));
            }
            out.put(e.getKey(), row);
        }
        out.put("moves", moves);
        return out;
    }

    private List<Object> contents(Inventory inv) {
        List<Object> out = new ArrayList<>();
        for (ItemStack s : inv.getContents()) {
            if (s == null || s.getType().isAir()) {
                out.add(null);
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("material", s.getType().name());
            row.put("amount", s.getAmount());
            row.put("tool", items.identify(s).flatMap(ItemRef::behaviorId).orElse(null));
            if (s.getItemMeta() instanceof PotionMeta pm) row.put("potion", potion(pm));
            out.add(row);
        }
        return out;
    }

    /** A real crafting table menu for the bot; the two items go in the grid, the server computes the result. */
    private Map<String, Object> craft(String first, String second) {
        Player bot = bot();
        InventoryView view = bot.openWorkbench(null, true);
        view.setItem(1, item(first));
        view.setItem(2, item(second));
        ItemStack result = view.getItem(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", view.getType().name());
        out.put("result", result == null || result.getType().isAir() ? null : result.getType().name());
        view.setItem(1, null);
        view.setItem(2, null);
        bot.closeInventory();
        return out;
    }

    /** Places a station block at an offset from the stations area, for the bot to right-click. */
    private void block(String name, String material, int dx, int dz) {
        Player bot = bot();
        World world = bot.getWorld();
        int bx = 6000, bz = 12;
        bot.teleport(new Location(world, bx - 2.5, 0, bz + 0.5, -90F, 0F));
        int gy = world.getHighestBlockYAt(bx - 30, bz - 30) + 1;
        bot.teleport(new Location(world, bx - 2.5, gy, bz + 0.5, -90F, 0F));
        Block b = world.getBlockAt(bx + dx, gy, bz + dz);
        b.setType(Material.valueOf(material));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pos", List.of(b.getX(), b.getY(), b.getZ()));
        writer.accept(name, out);
    }

    /**
     * With a real menu open (the bot right-clicked the block), puts items in its input slots, as a
     * hotbar swap would, and reports the result the server computed two ticks later.
     */
    private void menu(String name, String first, String second, String third) {
        Player bot = bot();
        InventoryView view = bot.getOpenInventory();
        view.setItem(0, item(first));
        view.setItem(1, item(second));
        if (!third.equals("-")) view.setItem(2, item(third));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> writer.accept(name, view()), 2L);
    }

    private Map<String, Object> view() {
        Player bot = bot();
        InventoryView view = bot.getOpenInventory();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", view.getType().name());
        for (int slot = 0; slot < Math.min(4, view.getTopInventory().getSize() + 1); slot++) {
            out.put("slot" + slot, snapshot(view.getItem(slot)));
        }
        out.put("level", bot.getLevel());
        out.put("offers", enchantOffers);
        return out;
    }

    /** A real enchanting table menu at a table block; the axe tool and lapis go in. */
    private void enchanting(String name) {
        Player bot = bot();
        World world = bot.getWorld();
        Location at = bot.getLocation().clone().add(2, 0, 0);
        at.getBlock().setType(Material.ENCHANTING_TABLE);
        enchantOffers.clear();
        InventoryView view = bot.openEnchanting(at, true);
        view.setItem(0, item("axe-tool"));
        view.setItem(1, item("lapis"));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> writer.accept(name, view()), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnchantPrepare(PrepareItemEnchantEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cancelled", event.isCancelled());
        row.put("item", event.getItem().getType().name());
        int offers = 0;
        for (org.bukkit.enchantments.EnchantmentOffer offer : event.getOffers()) if (offer != null) offers++;
        row.put("offers", offers);
        enchantOffers.add(row);
    }

    private Map<String, Object> inventory() {
        Player bot = bot();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack s = bot.getInventory().getItem(slot);
            if (s != null && !s.getType().isAir()) out.put(Integer.toString(slot), snapshot(s));
        }
        out.put("level", bot.getLevel());
        return out;
    }

    /** setBasePotionType (1.20.2+) or the older setBasePotionData, whichever this API has. */
    private static void setPotion(PotionMeta meta, org.bukkit.potion.PotionType type) {
        try {
            PotionMeta.class.getMethod("setBasePotionType", org.bukkit.potion.PotionType.class).invoke(meta, type);
        } catch (NoSuchMethodException e) {
            try {
                Class<?> data = Class.forName("org.bukkit.potion.PotionData");
                Object value = data.getConstructor(org.bukkit.potion.PotionType.class).newInstance(type);
                PotionMeta.class.getMethod("setBasePotionData", data).invoke(meta, value);
            } catch (ReflectiveOperationException e2) {
                throw new IllegalStateException(e2);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String potion(PotionMeta meta) {
        try {
            Object type = PotionMeta.class.getMethod("getBasePotionType").invoke(meta);
            return type == null ? null : type.toString();
        } catch (NoSuchMethodException e) {
            try {
                Object data = PotionMeta.class.getMethod("getBasePotionData").invoke(meta);
                return data.getClass().getMethod("getType").invoke(data).toString();
            } catch (ReflectiveOperationException e2) {
                return "?";
            }
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    /** Where the server thinks the bot is, and whether it counts its client as loaded. */
    private Map<String, Object> debug() {
        Player bot = bot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("location", bot.getLocation().toString());
        out.put("onGround", bot.isOnGround());
        try {
            Object handle = bot.getClass().getMethod("getHandle").invoke(bot);
            Object connection = handle.getClass().getField("connection").get(handle);
            for (java.lang.reflect.Method m : connection.getClass().getMethods()) {
                if (m.getName().equals("hasClientLoaded") && m.getParameterCount() == 0) out.put("clientLoaded", m.invoke(connection));
            }
            for (java.lang.reflect.Method m : handle.getClass().getMethods()) {
                if (m.getName().equals("isImmobile") && m.getParameterCount() == 0) out.put("immobile", m.invoke(handle));
                if (m.getName().equals("blockInteractionRange") && m.getParameterCount() == 0) out.put("range", m.invoke(handle));
            }
        } catch (ReflectiveOperationException e) {
            out.put("error", e.toString());
        }
        return out;
    }

    // -- Shared ----------------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    Map<String, Object> snapshot(ItemStack stack) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (stack == null || stack.getType().isAir()) {
            out.put("empty", true);
            return out;
        }
        out.put("material", stack.getType().name());
        out.put("amount", stack.getAmount());
        Optional<ItemRef> ref = items.identify(stack);
        out.put("behavior", ref.flatMap(ItemRef::behaviorId).orElse(null));
        out.put("appearance", ref.map(r -> r.appearance().asString()).orElse(null));
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        out.put("uuid", pdc.get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING));
        out.put("signature", pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));
        out.put("legacyId", pdc.get(new NamespacedKey("allium", "custom_item_id"), PersistentDataType.STRING));
        out.put("damage", meta instanceof Damageable d ? d.getDamage() : 0);
        out.put("maxDurability", (int) stack.getType().getMaxDurability());
        out.put("name", meta.hasDisplayName() ? meta.getDisplayName() : null);
        out.put("repairCost", meta instanceof Repairable r ? r.getRepairCost() : 0);
        Map<String, Integer> enchants = new LinkedHashMap<>();
        stack.getEnchantments().forEach((e, level) -> enchants.put(e.getKey().getKey(), level));
        out.put("enchants", enchants);
        return out;
    }

    private Player bot() {
        Player bot = plugin.getServer().getPlayerExact(BOT);
        if (bot == null) throw new IllegalStateException(BOT + " is not online");
        return bot;
    }
}
