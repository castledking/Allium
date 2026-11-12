package codes.castled.allium.tool.felltree;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.message.LangMessage;
import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * The {@code allium:fell_tree} behavior: breaking one log of a natural tree fells the whole tree
 * bottom up.
 */
public final class FellTreeBehavior {

    static final NamespacedKey LEGACY_ITEM_ID = new NamespacedKey("allium", "custom_item_id");
    /** Looked up by key: the field was renamed (DURABILITY to UNBREAKING) in 1.20.5. */
    @SuppressWarnings("deprecation")
    private static final @Nullable Enchantment UNBREAKING = lookupUnbreaking();

    @SuppressWarnings("deprecation")
    private static @Nullable Enchantment lookupUnbreaking() {
        try {
            return Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
        } catch (RuntimeException | LinkageError e) {
            return null; // no server registry (unit tests)
        }
    }

    @FunctionalInterface
    public interface Notifier {
        void send(Player player, LangMessage message, String... replacements);
    }

    public static final class FellJob {
        private final UUID playerId;
        private final World world;
        private final String instanceUuid;
        private final FellTreeTool tool;
        private final List<TreeScanner.Pos> remainingLogs;
        private final Set<Long> chunks;
        private final @Nullable TreeFamily family;
        private final @Nullable String species;
        private volatile TaskHandle handle = TaskHandle.NONE;

        public FellJob(UUID playerId, World world, String instanceUuid, FellTreeTool tool,
                       List<TreeScanner.Pos> remainingLogs, Set<Long> chunks,
                       @Nullable TreeFamily family, @Nullable String species) {
            this.playerId = playerId;
            this.world = world;
            this.instanceUuid = instanceUuid;
            this.tool = tool;
            this.remainingLogs = remainingLogs;
            this.chunks = Set.copyOf(chunks);
            this.family = family;
            this.species = species;
        }

        public UUID playerId() { return playerId; }
        public World world() { return world; }
        public String instanceUuid() { return instanceUuid; }
        public FellTreeTool tool() { return tool; }
        public List<TreeScanner.Pos> remainingLogs() { return remainingLogs; }
        public Set<Long> chunks() { return chunks; }
        public @Nullable TreeFamily family() { return family; }
        public @Nullable String species() { return species; }
        public void setHandle(TaskHandle handle) { this.handle = handle; }
        public void cancel() { handle.cancel(); }
    }

    private final ItemService items;
    private final ProtectionService protection;
    private final TaskScheduler scheduler;
    private final Notifier notifier;
    private final Consumer<String> diagnostics;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final Function<UUID, Player> players;
    private final Consumer<Event> events;
    private final Map<String, FellTreeTool> tools;

    private final Map<UUID, FellJob> activeJobs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    /**
     * The re-entry guard: players whose logs Allium is breaking right now. {@code Player#breakBlock}
     * fires a real {@code BlockBreakEvent} synchronously, into this behavior's own listener too; an
     * event for a player in this set was caused by a fell, so it never starts another one.
     */
    private final Set<UUID> breakingFor = ConcurrentHashMap.newKeySet();

    public FellTreeBehavior(
            ItemService items,
            ProtectionService protection,
            TaskScheduler scheduler,
            Notifier notifier,
            Consumer<String> diagnostics,
            LongSupplier clock,
            RandomGenerator random,
            Collection<FellTreeTool> tools
    ) {
        this(items, protection, scheduler, notifier, diagnostics, clock, random, Bukkit::getPlayer,
                event -> Bukkit.getPluginManager().callEvent(event), tools);
    }

    public FellTreeBehavior(
            ItemService items,
            ProtectionService protection,
            TaskScheduler scheduler,
            Notifier notifier,
            Consumer<String> diagnostics,
            LongSupplier clock,
            RandomGenerator random,
            Function<UUID, Player> players,
            Consumer<Event> events,
            Collection<FellTreeTool> tools
    ) {
        this.items = Objects.requireNonNull(items, "items");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.players = Objects.requireNonNull(players, "players");
        this.events = Objects.requireNonNull(events, "events");
        Map<String, FellTreeTool> byId = new LinkedHashMap<>();
        for (FellTreeTool tool : tools) {
            byId.put(tool.id(), tool);
            byId.put(tool.definition().behavior(), tool);
        }
        this.tools = Map.copyOf(byId);
    }

    public Collection<FellTreeTool> tools() {
        return tools.values();
    }

    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (breakingFor.contains(player.getUniqueId()) || event.isCancelled()) {
            return; // a break this behavior caused: never a second fell
        }
        Block broken = event.getBlock();
        TreeScanner.Pos brokenPos = new TreeScanner.Pos(broken.getX(), broken.getY(), broken.getZ());
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return;
        }

        // Legacy adoption check (tree_axe netherite axe)
        held = checkLegacyAdoption(player, held);

        ItemRef ref = items.identify(held).orElse(null);
        if (ref == null || ref.behaviorId().isEmpty()) {
            return;
        }

        FellTreeTool tool = tools.get(ref.behaviorId().get());
        if (tool == null) {
            return;
        }

        if (!player.hasPermission(tool.usePermission())) {
            return;
        }

        if (!tool.parameters().fellsWhileSneaking(player.isSneaking())) {
            return;
        }

        if (activeJobs.containsKey(player.getUniqueId())) {
            send(player, tool, FellTreeBehaviorType.MSG_BUSY);
            return;
        }

        long now = clock.getAsLong();
        if (tool.parameters().cooldownMillis() > 0) {
            Long last = lastUse.get(player.getUniqueId());
            if (last != null && now - last < tool.parameters().cooldownMillis()) {
                return;
            }
        }

        World world = broken.getWorld();
        BukkitBlockView view = new BukkitBlockView(world);

        TreeScanner.Pos startPos = brokenPos;
        String brokenMat = broken.getType().name().toLowerCase(Locale.ROOT);
        if (brokenMat.equals("mangrove_roots") || brokenMat.equals("muddy_mangrove_roots")) {
            for (int dy = 1; dy <= 5; dy++) {
                String up = view.material(startPos.x(), startPos.y() + dy, startPos.z());
                if (isMangroveLog(up)) {
                    startPos = startPos.offset(0, dy, 0);
                    break;
                }
                String down = view.material(startPos.x(), startPos.y() - dy, startPos.z());
                if (isMangroveLog(down)) {
                    startPos = startPos.offset(0, -dy, 0);
                    break;
                }
            }
        }

        TreeScanner.Scan scan = TreeScanner.scan(view, startPos, tool.parameters().families(), tool.parameters().limits());
        if (scan.result() == TreeScanner.Result.TOO_LARGE) {
            send(player, tool, FellTreeBehaviorType.MSG_TOO_LARGE, "{max}", Integer.toString(tool.parameters().maxBlocks()));
            return;
        }
        if (scan.result() != TreeScanner.Result.FELL) {
            return;
        }
        if (scan.logs().isEmpty()) {
            return;
        }

        String instanceUuid = items.instanceUuid(held).orElse(null);
        if (instanceUuid == null) {
            return;
        }

        Set<Long> chunks = new HashSet<>();
        chunks.add(chunkKey(brokenPos.x() >> 4, brokenPos.z() >> 4));
        for (TreeScanner.Pos pos : scan.logs()) {
            chunks.add(chunkKey(pos.x() >> 4, pos.z() >> 4));
        }

        FellJob job = new FellJob(
                player.getUniqueId(),
                world,
                instanceUuid,
                tool,
                new ArrayList<>(scan.logs()),
                chunks,
                scan.family(),
                scan.species()
        );

        LazyAxeFellEvent fellEvent = new LazyAxeFellEvent(player, broken, tool.id(), scan.logs().size(), tool.parameters().breakAsPlayer());
        events.accept(fellEvent);
        if (fellEvent.isCancelled()) {
            return;
        }
        activeJobs.put(player.getUniqueId(), job);
        if (tool.parameters().cooldownMillis() > 0) {
            lastUse.put(player.getUniqueId(), now);
        }

        TaskHandle handle = scheduler.repeatOnServer(() -> stepJob(job), 50L, 50L);
        job.setHandle(handle);
    }

    private boolean stepJob(FellJob job) {
        Player player = players.apply(job.playerId());
        if (player == null || !player.isOnline() || player.isDead()) {
            finishJob(job);
            return false;
        }
        if (!player.getWorld().equals(job.world())) {
            finishJob(job);
            return false;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<String> currentUuid = items.instanceUuid(held);
        if (currentUuid.isEmpty() || !currentUuid.get().equals(job.instanceUuid())) {
            finishJob(job);
            return false;
        }

        // Cancel job if any chunk it touches unloads
        for (long chunkKey : job.chunks()) {
            int cx = (int) (chunkKey >> 32);
            int cz = (int) chunkKey;
            if (!job.world().isChunkLoaded(cx, cz)) {
                finishJob(job);
                return false;
            }
        }

        FellTreeTool tool = job.tool();
        int perTick = tool.parameters().blocksPerTick();
        int brokenCount = 0;

        while (brokenCount < perTick && !job.remainingLogs().isEmpty()) {
            held = player.getInventory().getItemInMainHand();
            currentUuid = items.instanceUuid(held);
            if (currentUuid.isEmpty() || !currentUuid.get().equals(job.instanceUuid())) {
                finishJob(job);
                return false;
            }

            if (tool.parameters().stopBeforeBreak() && player.getGameMode() != GameMode.CREATIVE) {
                ItemMeta meta = held.getItemMeta();
                if (meta instanceof Damageable dmg && !meta.isUnbreakable()) {
                    int remaining = held.getType().getMaxDurability() - dmg.getDamage();
                    if (remaining <= 1) {
                        send(player, tool, FellTreeBehaviorType.MSG_STOPPED);
                        finishJob(job);
                        return false;
                    }
                }
            }

            TreeScanner.Pos pos = job.remainingLogs().remove(0);
            if (!job.world().isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                finishJob(job);
                return false;
            }

            Block block = job.world().getBlockAt(pos.x(), pos.y(), pos.z());
            String mat = block.getType().name().toLowerCase(Locale.ROOT);

            // Recheck still a log of the tree's family & species
            if (job.family() != null) {
                if (!job.family().isLog(mat, tool.parameters().stripped()) || !TreeScanner.species(mat).equals(job.species())) {
                    continue;
                }
            }

            // Protection check
            ProtectionDecision decision = protection.checkBlockBreak(player, block.getLocation(), FellTreeBehaviorType.ID, null);
            if (!decision.allowed()) {
                continue;
            }

            if (tool.parameters().breakAsPlayer()) {
                breakAsPlayer(player, block);
            } else {
                breakDirect(player, block, held);
            }
            brokenCount++;
        }

        if (job.remainingLogs().isEmpty()) {
            finishJob(job);
            return false;
        }
        return true;
    }

    private void breakAsPlayer(Player player, Block block) {
        UUID id = player.getUniqueId();
        breakingFor.add(id);
        try {
            player.breakBlock(block);
        } catch (RuntimeException e) {
            diagnostics.accept("player.breakBlock threw at " + block.getLocation() + ": " + e);
        } finally {
            breakingFor.remove(id);
        }
    }

    /** Whether a break by this player right now was caused by a fell (the re-entry guard). */
    public boolean isFelling(UUID playerId) {
        return breakingFor.contains(playerId);
    }

    private void breakDirect(Player player, Block block, ItemStack axe) {
        block.breakNaturally(axe);
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemMeta meta = axe.getItemMeta();
            if (meta instanceof Damageable dmg && !meta.isUnbreakable()) {
                int unbreaking = UNBREAKING == null ? 0 : axe.getEnchantmentLevel(UNBREAKING);
                if (unbreaking == 0 || random.nextInt(unbreaking + 1) == 0) {
                    dmg.setDamage(dmg.getDamage() + 1);
                    axe.setItemMeta(dmg);
                    if (dmg.getDamage() >= axe.getType().getMaxDurability()) {
                        player.getInventory().setItemInMainHand(null);
                        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    }
                }
            }
        }
    }

    private void finishJob(FellJob job) {
        activeJobs.remove(job.playerId());
        job.cancel();
    }

    public void cancelJob(UUID playerId) {
        FellJob job = activeJobs.remove(playerId);
        if (job != null) {
            job.cancel();
        }
    }

    public void onChunkUnload(World world, int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        for (FellJob job : activeJobs.values()) {
            if (job.world().equals(world) && job.chunks().contains(key)) {
                finishJob(job);
            }
        }
    }

    public void disable() {
        for (FellJob job : activeJobs.values()) {
            job.cancel();
        }
        activeJobs.clear();
        lastUse.clear();
        breakingFor.clear();
    }

    public Map<UUID, FellJob> activeJobs() {
        return Collections.unmodifiableMap(activeJobs);
    }

    private ItemStack checkLegacyAdoption(Player player, ItemStack stack) {
        if (stack == null || stack.getType() != Material.NETHERITE_AXE || stack.getAmount() != 1) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta == null ? null : meta.getPersistentDataContainer();
        if (pdc == null || pdc.has(ItemService.KEY_BEHAVIOR_ID, PersistentDataType.STRING)) {
            return stack;
        }
        String customId = pdc.get(LEGACY_ITEM_ID, PersistentDataType.STRING);
        if (!"tree_axe".equals(customId)) {
            return stack;
        }
        FellTreeTool target = tools.get(FellTreeBehaviorType.ID);
        if (target == null) {
            target = tools.get("allium:lazy_axe");
        }
        if (target == null) {
            target = tools.values().stream().filter(t -> t.parameters().importLegacy()).findFirst().orElse(null);
        }
        if (target == null || !target.parameters().importLegacy()) {
            return stack;
        }
        ProviderKey nativeAppearance = items.identify(stack).map(ItemRef::appearance).filter(k -> !k.isVanilla()).orElse(null);
        ProviderKey appearance = nativeAppearance != null ? nativeAppearance : target.definition().appearance();
        if (items.adopt(stack, appearance, target.definition().id(), Map.of())) {
            player.getInventory().setItemInMainHand(stack);
            diagnostics.accept("Imported legacy Lazy Axe for " + player.getName() + " as " + appearance + ".");
            return player.getInventory().getItemInMainHand();
        }
        return stack;
    }

    private void send(Player player, FellTreeTool tool, String slot, String... replacements) {
        notifier.send(player, tool.message(slot), replacements);
    }

    private static boolean isMangroveLog(@Nullable String mat) {
        return mat != null && (mat.equals("mangrove_log") || mat.equals("mangrove_wood")
                || mat.equals("stripped_mangrove_log") || mat.equals("stripped_mangrove_wood"));
    }

    public static long chunkKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }
}
