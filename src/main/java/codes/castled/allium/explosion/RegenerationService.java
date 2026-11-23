package codes.castled.allium.explosion;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.explosion.ExplosionSettings.Explosive;
import codes.castled.allium.explosion.ExplosionSettings.Source;
import codes.castled.allium.mail.MailItemCodec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.ServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.tool.BukkitParticles;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Explosion regeneration. Server thread only.
 *
 * <p>When an explosion that regenerates goes off, the blocks it would break are taken out of the
 * explosion and removed here instead, with no drops and no block updates: containers keep their
 * items (nothing spills, so nothing can be duplicated), and the blocks resting on or hanging from
 * them (torches, signs, plants, doors' upper halves) and the sand and gravel above them are taken
 * with them rather than popping off or falling. Each explosion is written to the journal before
 * anything leaves the world. After {@code delay-seconds} the blocks come back in {@link RegenOrder},
 * from their full snapshot, only into air, fire or flowing liquid; a living thing in the way is
 * lifted out first. Until a block is back, nothing may be placed, flow or spread into its spot, and
 * the explosion's chunks stay loaded.
 *
 * <p>Stopping the plugin puts every waiting block back at once. A crash leaves the journal, and the
 * next start finishes the job: a spot that already holds the same block (the world was not saved
 * after the explosion) is left alone, and the items of a container whose spot was taken are dropped
 * there.
 *
 * <p>Blocks inside claims (when {@code skip-claims} is on), in disabled worlds and in
 * {@code ignored-blocks} explode as usual. {@code /nuke}'s TNT breaks no blocks at all.
 */
public final class RegenerationService implements Listener {

    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST};
    private static final String CHIME = "minecraft:block.amethyst_block.chime";
    private static final Map<Class<?>, Optional<Method>> RESULT_METHODS = new ConcurrentHashMap<>();

    private record BlockKey(UUID world, int x, int y, int z) {
    }

    private record ChunkKey(UUID world, int x, int z) {
    }

    /** An explosion that is going off right now: from its prime event to its explode event. */
    private record Window(UUID entity, boolean protect) {
    }

    private static final class Job {
        final UUID id;
        final World world;
        final double centerX;
        final double centerY;
        final double centerZ;
        final long created;
        final List<CapturedBlock> blocks;
        final Set<ChunkKey> chunks = new HashSet<>();
        final long startAt;
        int next;
        double budget;
        volatile boolean dirty;
        boolean persistWarned;
        @Nullable
        volatile TaskHandle task;
        final AtomicInteger inFlightCrossRegion = new AtomicInteger();

        Job(UUID id, World world, double centerX, double centerY, double centerZ, long created, List<CapturedBlock> blocks,
            long startAt) {
            this.id = id;
            this.world = world;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.created = created;
            this.blocks = blocks;
            this.startAt = startAt;
            for (CapturedBlock block : blocks) {
                chunks.add(new ChunkKey(world.getUID(), block.x() >> 4, block.z() >> 4));
            }
        }

        RegenJournal.Entry remaining() {
            return new RegenJournal.Entry(id, world.getUID(), world.getName(), centerX, centerY, centerZ, created,
                    blocks.subList(next, blocks.size()));
        }
    }

    private final PluginStart plugin;
    private final Path file;
    private final RegenJournal journal;
    private final NamespacedKey explosiveKey;
    private final MailItemCodec codec = new MailItemCodec();
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Map<BlockKey, Job> pending = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Integer> tickets = new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<Window>> windows = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Boolean> sweepQueued = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile ExplosionSettings settings;
    @Nullable
    private volatile TaskHandle ticker;
    private volatile boolean closed;

    public RegenerationService(PluginStart plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("explosions.yml");
        this.journal = new RegenJournal(plugin.getDataFolder().toPath().resolve("regeneration"));
        this.explosiveKey = new NamespacedKey(plugin, "explosive");
    }

    // -- Lifecycle -------------------------------------------------------------------------------

    public void enable() throws IOException, InvalidConfigurationException {
        if (!Files.exists(file)) plugin.saveResource("explosions.yml", false);
        settings = ExplosionSettings.read(read(), plugin.logger()::warning);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        int resumed = 0;
        for (World world : plugin.getServer().getWorlds()) resumed += resume(world);
        if (resumed > 0) {
            plugin.logger().info("Regenerating " + resumed + " explosion(s) that a stop interrupted.");
        }
    }

    /** Puts every waiting block back at once, then stops listening. */
    public void disable() {
        closed = true;
        restoreNow(job -> true);
        HandlerList.unregisterAll(this);
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        windows.remove();
        sweepQueued.remove();
    }

    /** Re-reads {@code explosions.yml}; on an error the previous settings stay and false is returned. */
    public boolean reload() {
        try {
            settings = ExplosionSettings.read(read(), plugin.logger()::warning);
            return true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException invalid) {
            plugin.logger().warning("Invalid explosions.yml; the previous explosion settings are kept: " + invalid.getMessage());
            return false;
        }
    }

    public ExplosionSettings settings() {
        return settings;
    }

    /** Explosions still coming back, and their blocks left. */
    public int[] waiting() {
        int blocks = 0;
        for (Job job : jobs.values()) blocks += job.blocks.size() - job.next;
        return new int[]{jobs.size(), blocks};
    }

    private YamlConfiguration read() throws IOException, InvalidConfigurationException {
        if (Files.size(file) > 1_048_576) throw new IOException("explosions.yml exceeds 1 MiB");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    // -- Tags ------------------------------------------------------------------------------------

    /** Marks TNT, a projectile or a fireball as made by one of the explosive commands. */
    public void tag(Entity entity, Explosive explosive) {
        entity.getPersistentDataContainer().set(explosiveKey, PersistentDataType.STRING, explosive.key());
    }

    @Nullable
    public Explosive explosive(@Nullable Entity entity) {
        if (entity == null) return null;
        String key = entity.getPersistentDataContainer().get(explosiveKey, PersistentDataType.STRING);
        return key == null ? null : Explosive.of(key);
    }

    static Source source(@Nullable Entity entity) {
        if (entity instanceof Creeper) return Source.CREEPER;
        if (entity instanceof WitherSkull || entity instanceof Wither) return Source.WITHER;
        if (entity instanceof LargeFireball) return Source.GHAST_FIREBALL;
        if (entity instanceof TNTPrimed || entity instanceof ExplosiveMinecart) return Source.TNT;
        if (entity instanceof EnderCrystal) return Source.END_CRYSTAL;
        return Source.OTHER;
    }

    private boolean regenerates(@Nullable Entity entity) {
        Explosive explosive = explosive(entity);
        return explosive != null ? settings.regenerates(explosive) : settings.regenerates(source(entity));
    }

    // -- Capture ---------------------------------------------------------------------------------

    /*
     * HIGH: after protection plugins have taken out what they protect, and before the creative
     * manager (HIGHEST) removes blocks placed in creative mode. Those come back here still marked as
     * creative-placed, instead of vanishing.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (explosive(event.getEntity()) == Explosive.NUKE) {
            event.blockList().clear();
            return;
        }
        if (event.blockList().isEmpty() || !breaksBlocks(event) || !regenerates(event.getEntity())) return;
        capture(event.getLocation(), event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.blockList().isEmpty() || !breaksBlocks(event) || !settings.regenerates(Source.BLOCK)) return;
        capture(event.getBlock().getLocation().add(0.5D, 0.5D, 0.5D), event.blockList());
    }

    /** Wind charges (and anything else on newer servers that only toggles doors and buttons) break nothing. */
    private static boolean breaksBlocks(Event event) {
        Optional<Method> method = RESULT_METHODS.computeIfAbsent(event.getClass(), type -> {
            try {
                return Optional.of(type.getMethod("getExplosionResult"));
            } catch (NoSuchMethodException older) {
                return Optional.empty();
            }
        });
        if (method.isEmpty()) return true;
        try {
            Object result = method.get().invoke(event);
            String name = result instanceof Enum<?> constant ? constant.name() : String.valueOf(result);
            return !name.equals("KEEP") && !name.equals("TRIGGER_BLOCK");
        } catch (ReflectiveOperationException | RuntimeException unknown) {
            return true;
        }
    }

    private void capture(Location center, List<Block> exploded) {
        World world = center.getWorld();
        ExplosionSettings settings = this.settings;
        if (closed || world == null || !settings.worldEnabled(world.getName())) return;
        ProtectionService protection = settings.skipClaims() ? plugin.getProtectionService() : null;
        Predicate<Block> eligible = block -> !block.getType().isAir()
                && !settings.ignored().contains(block.getType())
                && !pending.containsKey(key(block))
                && (protection == null || !protection.claimed(block.getLocation()));
        Map<BlockKey, Block> first = new LinkedHashMap<>();
        for (Block block : exploded) {
            if (eligible.test(block)) first.put(key(block), block);
        }
        if (first.isEmpty() || first.size() > settings.maxBlocks()) return;
        for (Block block : List.copyOf(first.values())) {
            Block other = partner(block);
            if (other != null && !first.containsKey(key(other)) && eligible.test(other)) first.put(key(other), other);
        }
        // Every snapshot is taken before anything leaves the world.
        Map<BlockKey, CapturedBlock> captured = new LinkedHashMap<>();
        for (Map.Entry<BlockKey, Block> entry : first.entrySet()) captured.put(entry.getKey(), snapshot(entry.getValue()));
        for (Block block : first.values()) block.setType(Material.AIR, false);
        // What rested on or hung from them, and the sand and gravel above, go with them.
        int limit = settings.maxBlocks() * 2;
        ArrayDeque<Block> queue = new ArrayDeque<>(first.values());
        while (!queue.isEmpty() && captured.size() < limit) {
            Block from = queue.poll();
            for (BlockFace face : FACES) {
                Block next = from.getRelative(face);
                BlockKey key = key(next);
                if (captured.containsKey(key) || next.getType().isAir()) continue;
                boolean falls = face == BlockFace.UP && next.getType().hasGravity();
                if (!falls && next.getBlockData().isSupported(next) || !eligible.test(next)) continue;
                captured.put(key, snapshot(next));
                next.setType(Material.AIR, false);
                queue.add(next);
                if (captured.size() >= limit) break;
            }
        }
        List<CapturedBlock> ordered = RegenOrder.sort(new ArrayList<>(captured.values()), center.getX(), center.getZ());
        Job job = new Job(UUID.randomUUID(), world, center.getX(), center.getY(), center.getZ(), System.currentTimeMillis(),
                ordered, System.currentTimeMillis() + settings.delayTicks() * 50L);
        try {
            journal.write(job.remaining());
        } catch (IOException | RuntimeException failed) {
            // Without a journal a crash would lose the blocks and what containers held: undo, and
            // let the explosion break them as usual.
            for (CapturedBlock block : captured.values()) {
                if (block.live() != null) block.live().update(true, false);
            }
            warnOnce("journal", "Explosion regeneration is off for an explosion: its journal could not be written to "
                    + journal.folder() + " (" + failed.getMessage() + "). It broke blocks as usual.");
            return;
        }
        exploded.removeIf(block -> captured.containsKey(key(block)));
        start(job);
    }

    private CapturedBlock snapshot(Block block) {
        BlockState state = block.getState();
        TileSnapshot tile = null;
        if (state instanceof TileState) {
            try {
                tile = TileSnapshot.capture(state, codec);
            } catch (RuntimeException failed) {
                warnOnce("tile:" + state.getType(), "A regenerating " + state.getType().name().toLowerCase(java.util.Locale.ROOT)
                        + "'s data could not be journaled, so a crash before it comes back would lose it: " + failed.getMessage());
            }
        }
        BlockData data = state.getBlockData();
        return new CapturedBlock(block.getX(), block.getY(), block.getZ(), data.getMaterial().getKey().toString(),
                data.getAsString(), !data.getMaterial().isSolid(), state, tile);
    }

    /** The other half of a bed, door or tall plant. */
    @Nullable
    private static Block partner(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Bed bed) {
            return block.getRelative(bed.getPart() == Bed.Part.HEAD ? bed.getFacing().getOppositeFace() : bed.getFacing());
        }
        if (data instanceof Bisected half && !(data instanceof Stairs) && !(data instanceof TrapDoor)) {
            Block other = block.getRelative(half.getHalf() == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN);
            if (other.getType() == block.getType() && other.getBlockData() instanceof Bisected twin && twin.getHalf() != half.getHalf()) {
                return other;
            }
        }
        return null;
    }

    private void start(Job job) {
        jobs.put(job.id, job);
        for (CapturedBlock block : job.blocks) pending.put(new BlockKey(job.world.getUID(), block.x(), block.y(), block.z()), job);
        for (ChunkKey chunk : job.chunks) {
            if (tickets.merge(chunk, 1, Integer::sum) == 1) {
                UtilitySupport.onRegion(plugin, job.world, chunk.x(), chunk.z(),
                        () -> job.world.addPluginChunkTicket(chunk.x(), chunk.z(), plugin));
            }
        }
        if (closed) return;
        if (BukkitServerThread.isFolia()) {
            long delay = Math.max(50L, job.startAt - System.currentTimeMillis());
            ServerThread centerRegion = BukkitServerThread.region(plugin, job.world,
                    ((int) Math.floor(job.centerX)) >> 4, ((int) Math.floor(job.centerZ)) >> 4);
            job.task = plugin.getScheduler().runOn(centerRegion, () -> tickFoliaJob(job), () -> {}, delay);
            return;
        }
        if (ticker == null) {
            ticker = plugin.getScheduler().repeatOnServer(this::tick, 50L, 50L);
        }
    }

    /** Resumes the journals of {@code world} that are not running yet; returns how many. */
    private int resume(World world) {
        int resumed = 0;
        for (RegenJournal.Entry entry : journal.readAll(plugin.logger()::warning)) {
            if (!entry.world().equals(world.getUID()) || jobs.containsKey(entry.id())) continue;
            if (entry.blocks().isEmpty()) {
                deleteJournal(entry.id());
                continue;
            }
            start(new Job(entry.id(), world, entry.centerX(), entry.centerY(), entry.centerZ(), entry.created(),
                    new ArrayList<>(entry.blocks()), System.currentTimeMillis() + settings.delayTicks() * 50L));
            resumed++;
        }
        return resumed;
    }

    // -- Restore ---------------------------------------------------------------------------------

    private boolean tick() {
        if (closed) {
            ticker = null;
            return false;
        }
        long now = System.currentTimeMillis();
        ExplosionSettings settings = this.settings;
        double perTick = settings.blocksPerTick();
        for (Job job : new ArrayList<>(jobs.values())) {
            if (now < job.startAt) continue;
            job.budget = Math.min(job.budget + perTick, Math.max(1.0D, perTick) * 2.0D);
            Block last = null;
            while (job.budget >= 1.0D && job.next < job.blocks.size()) {
                job.budget -= 1.0D;
                Block placed = restore(job, job.blocks.get(job.next++));
                if (placed != null) {
                    last = placed;
                    if (settings.effects()) particle(placed);
                }
            }
            if (last != null && settings.effects()) {
                last.getWorld().playSound(last.getLocation().add(0.5D, 0.5D, 0.5D), CHIME, 0.6F, 1.0F);
            }
            if (job.next >= job.blocks.size()) {
                finish(job);
            } else if (job.dirty) {
                persist(job);
            }
        }
        if (jobs.isEmpty()) {
            ticker = null;
            return false;
        }
        return true;
    }

    private void tickFoliaJob(Job job) {
        if (closed || !jobs.containsKey(job.id)) {
            return;
        }
        long now = System.currentTimeMillis();
        ExplosionSettings settings = this.settings;
        ServerThread centerRegion = BukkitServerThread.region(plugin, job.world,
                ((int) Math.floor(job.centerX)) >> 4, ((int) Math.floor(job.centerZ)) >> 4);
        if (now >= job.startAt) {
            double perTick = settings.blocksPerTick();
            job.budget = Math.min(job.budget + perTick, Math.max(1.0D, perTick) * 2.0D);
            Block last = null;
            while (job.budget >= 1.0D && job.next < job.blocks.size()) {
                job.budget -= 1.0D;
                CapturedBlock entry = job.blocks.get(job.next++);
                ServerThread blockRegion = BukkitServerThread.region(plugin, job.world, entry.x() >> 4, entry.z() >> 4);
                if (blockRegion.isServerThread()) {
                    Block placed = restore(job, entry);
                    if (placed != null) {
                        last = placed;
                        if (settings.effects()) particle(placed);
                    }
                } else {
                    job.inFlightCrossRegion.incrementAndGet();
                    TaskHandle handle = plugin.getScheduler().runOn(blockRegion, () -> {
                        try {
                            Block placed = restore(job, entry);
                            if (placed != null && settings.effects()) {
                                particle(placed);
                                placed.getWorld().playSound(placed.getLocation().add(0.5D, 0.5D, 0.5D), CHIME, 0.6F, 1.0F);
                            }
                        } finally {
                            job.inFlightCrossRegion.decrementAndGet();
                        }
                    }, job.inFlightCrossRegion::decrementAndGet, 0L);
                    if (handle == TaskHandle.NONE) {
                        job.inFlightCrossRegion.decrementAndGet();
                    }
                }
            }
            if (last != null && settings.effects()) {
                last.getWorld().playSound(last.getLocation().add(0.5D, 0.5D, 0.5D), CHIME, 0.6F, 1.0F);
            }
        }
        if (job.next >= job.blocks.size()) {
            if (job.inFlightCrossRegion.get() > 0) {
                job.task = plugin.getScheduler().runOn(centerRegion, () -> tickFoliaJob(job), () -> {}, 50L);
            } else {
                finish(job);
            }
            return;
        }
        if (job.dirty) {
            persist(job);
        }
        job.task = plugin.getScheduler().runOn(centerRegion, () -> tickFoliaJob(job), () -> {}, 50L);
    }

    /** Puts every waiting block of the matching explosions back now, without effects. */
    private void restoreNow(Predicate<Job> which) {
        for (Job job : new ArrayList<>(jobs.values())) {
            if (!which.test(job)) continue;
            TaskHandle handle = job.task;
            if (handle != null) {
                handle.cancel();
                job.task = null;
            }
            if (BukkitServerThread.isFolia() && !ownsAllRemaining(job)) {
                // On Folia shutdown/unload off-region, preserve the journal for lossless resume on next start.
                if (job.next < job.blocks.size()) persist(job);
                releaseStateWithoutDeletingJournal(job);
                continue;
            }
            while (job.next < job.blocks.size()) restore(job, job.blocks.get(job.next++));
            finish(job);
        }
    }

    private boolean ownsAllRemaining(Job job) {
        for (int i = job.next; i < job.blocks.size(); i++) {
            CapturedBlock block = job.blocks.get(i);
            if (!BukkitServerThread.region(plugin, job.world, block.x() >> 4, block.z() >> 4).isServerThread()) {
                return false;
            }
        }
        return true;
    }

    private void releaseStateWithoutDeletingJournal(Job job) {
        jobs.remove(job.id);
        for (int i = 0; i < job.blocks.size(); i++) {
            CapturedBlock block = job.blocks.get(i);
            pending.remove(new BlockKey(job.world.getUID(), block.x(), block.y(), block.z()), job);
        }
        for (ChunkKey chunk : job.chunks) {
            tickets.computeIfPresent(chunk, (k, count) -> count <= 1 ? null : count - 1);
        }
    }

    /** Puts one block back; returns it if it was placed. */
    @Nullable
    private Block restore(Job job, CapturedBlock entry) {
        Block block = job.world.getBlockAt(entry.x(), entry.y(), entry.z());
        BlockKey key = new BlockKey(job.world.getUID(), entry.x(), entry.y(), entry.z());
        if (pending.get(key) == job) pending.remove(key);
        if (entry.hasItems()) job.dirty = true;
        if (!replaceable(block)) {
            // The same block is already there when the world was not saved after the explosion and
            // the journal brought the job back; anything else means the spot was taken.
            if (!block.getType().getKey().toString().equals(entry.material())) drop(block, entry);
            return null;
        }
        lift(block, entry);
        try {
            if (entry.live() != null) {
                entry.live().update(true, false);
                return block;
            }
            BlockData data = blockData(entry);
            if (data == null) {
                drop(block, entry);
                return null;
            }
            block.setBlockData(data, false);
            if (entry.tile() != null) {
                BlockState state = block.getState();
                entry.tile().apply(state, codec, message -> warnOnce("apply:" + message, "Regeneration: " + message + "."));
                state.update(true, false);
            }
            return block;
        } catch (RuntimeException failed) {
            warnOnce("restore:" + entry.material(), "A regenerating " + entry.material() + " at " + entry.x() + ", " + entry.y() + ", "
                    + entry.z() + " could not be put back: " + failed.getMessage());
            return null;
        }
    }

    @Nullable
    private BlockData blockData(CapturedBlock entry) {
        try {
            return Bukkit.createBlockData(entry.data());
        } catch (IllegalArgumentException changed) {
            Material material = Material.matchMaterial(entry.material());
            if (material != null && material.isBlock()) return material.createBlockData();
            warnOnce("unknown:" + entry.material(), "Regeneration: " + entry.material() + " is no block on this server; it was not put back.");
            return null;
        }
    }

    private static boolean replaceable(Block block) {
        Material type = block.getType();
        if (type.isAir() || type == Material.FIRE || type == Material.SOUL_FIRE) return true;
        if (type == Material.WATER || type == Material.LAVA) {
            return block.getBlockData() instanceof Levelled liquid && liquid.getLevel() != 0;
        }
        return false;
    }

    /** Lifts living things out of a solid block's spot, so it does not trap them. */
    private static void lift(Block block, CapturedBlock entry) {
        Material material = entry.live() != null ? entry.live().getType() : Material.matchMaterial(entry.material());
        if (material == null || !material.isSolid()) return;
        BoundingBox spot = BoundingBox.of(block);
        for (Entity entity : block.getWorld().getNearbyEntities(spot, entity -> entity instanceof LivingEntity)) {
            if (entity.isInsideVehicle()) continue;
            Location at = entity.getLocation();
            if (at.getY() >= block.getY() + 1.0D) continue;
            at.setY(block.getY() + 1.0D);
            if (BukkitServerThread.isFolia()) {
                BukkitServerThread.teleportRegional(entity, at, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            } else {
                entity.teleport(at);
            }
        }
    }

    /** Drops what a container held where it stood, when its spot was taken. */
    private void drop(Block block, CapturedBlock entry) {
        if (!entry.hasItems()) return;
        try {
            for (ItemStack item : entry.tile().items(codec)) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5D, 0.5D, 0.5D), item);
            }
            plugin.logger().info("Regeneration: the spot of a " + entry.material() + " at " + entry.x() + ", " + entry.y() + ", " + entry.z()
                    + " in " + block.getWorld().getName() + " was taken; what it held was dropped there.");
        } catch (IOException | RuntimeException failed) {
            plugin.logger().warning("Regeneration: the items of a " + entry.material() + " at " + entry.x() + ", " + entry.y() + ", "
                    + entry.z() + " could not be read: " + failed.getMessage());
        }
    }

    private void particle(Block block) {
        Particle heart = BukkitParticles.resolve("heart").orElse(null);
        if (heart != null) {
            block.getWorld().spawnParticle(heart, block.getLocation().add(0.5D, 0.7D, 0.5D), 1, 0.2D, 0.2D, 0.2D, 0.0D);
        }
    }

    private void persist(Job job) {
        job.dirty = false;
        try {
            journal.write(job.remaining());
        } catch (IOException | RuntimeException failed) {
            if (!job.persistWarned) {
                job.persistWarned = true;
                plugin.logger().warning("Regeneration journal " + job.id + " could not be updated: " + failed.getMessage());
            }
        }
    }

    private void finish(Job job) {
        jobs.remove(job.id);
        for (int i = 0; i < job.blocks.size(); i++) {
            CapturedBlock block = job.blocks.get(i);
            BlockKey key = new BlockKey(job.world.getUID(), block.x(), block.y(), block.z());
            if (pending.get(key) == job) pending.remove(key);
        }
        for (ChunkKey chunk : job.chunks) {
            Integer left = tickets.compute(chunk, (k, count) -> count == null || count <= 1 ? null : count - 1);
            if (left == null) {
                UtilitySupport.onRegion(plugin, job.world, chunk.x(), chunk.z(),
                        () -> job.world.removePluginChunkTicket(chunk.x(), chunk.z(), plugin));
            }
        }
        deleteJournal(job.id);
    }

    private void deleteJournal(UUID id) {
        try {
            journal.delete(id);
        } catch (IOException failed) {
            plugin.logger().warning("Regeneration journal " + id + " could not be deleted: " + failed.getMessage());
        }
    }

    // -- Item frames and paintings ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        ExplosionSettings settings = this.settings;
        boolean protect = settings.protectHanging()
                && (explosive(entity) == Explosive.NUKE || settings.worldEnabled(entity.getWorld().getName()) && regenerates(entity));
        Deque<Window> localWindows = windows.get();
        localWindows.push(new Window(entity.getUniqueId(), protect));
        if (!Boolean.TRUE.equals(sweepQueued.get())) {
            sweepQueued.set(Boolean.TRUE);
            Runnable clear = () -> {
                windows.get().clear();
                sweepQueued.set(Boolean.FALSE);
            };
            if (BukkitServerThread.isFolia()) {
                Location loc = entity.getLocation();
                if (loc.getWorld() != null) {
                    ServerThread region = BukkitServerThread.region(plugin, loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
                    plugin.getScheduler().runOn(region, clear, () -> {}, 0L);
                } else {
                    clear.run();
                }
            } else {
                plugin.getScheduler().runOnServer(clear);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExplodeDone(EntityExplodeEvent event) {
        UUID id = event.getEntity().getUniqueId();
        windows.get().removeIf(window -> window.entity().equals(id));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Hanging hanging = event.getEntity();
        if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) {
            Window window = windows.get().peek();
            if (window == null || !window.protect()) return;
            ProtectionService protection = settings.skipClaims() ? plugin.getProtectionService() : null;
            if (protection != null && protection.claimed(hanging.getLocation())) return;
            event.setCancelled(true);
        } else if (event.getCause() == HangingBreakEvent.RemoveCause.PHYSICS && !pending.isEmpty()) {
            BoundingBox box = hanging.getBoundingBox();
            BlockFace face = hanging.getAttachedFace();
            for (int x = (int) Math.floor(box.getMinX()); x < Math.ceil(box.getMaxX()); x++) {
                for (int y = (int) Math.floor(box.getMinY()); y < Math.ceil(box.getMaxY()); y++) {
                    for (int z = (int) Math.floor(box.getMinZ()); z < Math.ceil(box.getMaxZ()); z++) {
                        if (pending.containsKey(new BlockKey(hanging.getWorld().getUID(), x + face.getModX(), y + face.getModY(),
                                z + face.getModZ()))) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                }
            }
        }
    }

    // -- Keeping waiting spots clear -------------------------------------------------------------

    private boolean waiting(Block block) {
        return !pending.isEmpty() && pending.containsKey(key(block));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (waiting(event.getBlockPlaced())) {
            event.setCancelled(true);
            plugin.getMessageManager().send(event.getPlayer(), Messages.EXPLOSION_REGENERATING);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (waiting(event.getBlock())) {
            event.setCancelled(true);
            plugin.getMessageManager().send(event.getPlayer(), Messages.EXPLOSION_REGENERATING);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (waiting(event.getToBlock())) event.setCancelled(true);
    }

    /** Snow, ice, concrete, fire and mushroom spread, frost walker. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (waiting(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (waiting(event.getBlock())) event.setCancelled(true);
    }

    /** Endermen and other mobs placing blocks; a falling block lands as usual so it is never lost. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock) && !event.getTo().isAir() && waiting(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pending.isEmpty()) return;
        if (waiting(event.getBlock().getRelative(event.getDirection()))) {
            event.setCancelled(true);
            return;
        }
        for (Block moved : event.getBlocks()) {
            if (waiting(moved.getRelative(event.getDirection()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pending.isEmpty()) return;
        for (Block moved : event.getBlocks()) {
            if (waiting(moved.getRelative(event.getDirection()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        restoreNow(job -> job.world.getUID().equals(world));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        int resumed = resume(event.getWorld());
        if (resumed > 0) {
            plugin.logger().info("Regenerating " + resumed + " interrupted explosion(s) in " + event.getWorld().getName() + ".");
        }
    }

    // -- Helpers ---------------------------------------------------------------------------------

    private static BlockKey key(Block block) {
        return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) plugin.logger().warning(message);
    }
}
