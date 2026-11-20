package codes.castled.allium.tool.powertool;

import codes.castled.allium.item.ItemService;
import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import codes.castled.allium.tool.BukkitParticles;
import codes.castled.allium.tool.disarmer.DisarmDropRule;
import codes.castled.allium.tool.disarmer.DisarmTargetPolicy;
import codes.castled.allium.tool.disarmer.PickupSuppression;
import codes.castled.allium.tool.felltree.BukkitBlockView;
import codes.castled.allium.tool.felltree.LazyAxeFellEvent;
import codes.castled.allium.tool.felltree.TreeFamily;
import codes.castled.allium.tool.felltree.TreeScanner;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
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
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Extensible registry of {@link PowerToolActionHandler}s.
 *
 * <p>Ships with built-in actions ({@code command}, {@code console}, {@code message},
 * {@code actionbar}, {@code sound}, {@code particle}, {@code potion}) and Allium's own tool
 * actions ({@code tree_fell}, {@code disarm}, {@code obliterate}). Future Allium modules or
 * addons can call {@link #register(String, PowerToolActionHandler)} to add new actions.
 */
public final class PowerToolActionRegistry {

    private static final EquipmentSlot[] DISARM_SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final Set<String> DEFAULT_EXCLUDED_DISARM_MOBS = Set.of(
            "ender_dragon", "wither", "elder_guardian", "warden"
    );

    @SuppressWarnings("deprecation")
    private static final @Nullable Enchantment UNBREAKING = lookupUnbreaking();

    @SuppressWarnings("deprecation")
    private static @Nullable Enchantment lookupUnbreaking() {
        try {
            return Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private final Map<String, PowerToolActionHandler> handlers = new LinkedHashMap<>();
    private final ItemService items;
    private final ProtectionService protection;
    private final TaskScheduler scheduler;
    private final Supplier<PowerToolSettings> settings;
    private final Consumer<String> diagnostics;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final Function<UUID, Player> players;
    private final PickupSuppression pickupSuppression;

    // Active tree-fell jobs and re-entry guard so Player#breakBlock does not re-trigger on-break.
    private final Map<UUID, ActiveFellJob> activeFellJobs = new ConcurrentHashMap<>();
    private final Set<UUID> breakingFor = ConcurrentHashMap.newKeySet();

    private record ActiveFellJob(
            UUID playerId,
            World world,
            @Nullable String instanceUuid,
            List<TreeScanner.Pos> remainingLogs,
            Set<Long> chunks,
            int blocksPerTick,
            boolean breakAsPlayer,
            boolean stopBeforeBreak,
            TaskHandle[] handleBox
    ) {
        void cancel() {
            if (handleBox[0] != null) {
                handleBox[0].cancel();
            }
        }
    }

    public PowerToolActionRegistry(
            ItemService items,
            ProtectionService protection,
            TaskScheduler scheduler,
            Supplier<PowerToolSettings> settings,
            Consumer<String> diagnostics,
            LongSupplier clock,
            RandomGenerator random
    ) {
        this(items, protection, scheduler, settings, diagnostics, clock, random, Bukkit::getPlayer);
    }

    public PowerToolActionRegistry(
            ItemService items,
            ProtectionService protection,
            TaskScheduler scheduler,
            Supplier<PowerToolSettings> settings,
            Consumer<String> diagnostics,
            LongSupplier clock,
            RandomGenerator random,
            Function<UUID, Player> players
    ) {
        this.items = Objects.requireNonNull(items, "items");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.players = Objects.requireNonNull(players, "players");
        this.pickupSuppression = new PickupSuppression(clock);
        registerDefaults();
    }

    public PickupSuppression pickupSuppression() {
        return pickupSuppression;
    }

    public boolean isBreakingFor(UUID playerId) {
        return breakingFor.contains(playerId);
    }

    public void disable() {
        for (ActiveFellJob job : new ArrayList<>(activeFellJobs.values())) {
            job.cancel();
        }
        activeFellJobs.clear();
        breakingFor.clear();
    }

    public void register(String typeId, PowerToolActionHandler handler) {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(handler, "handler");
        handlers.put(PowerToolAction.normalizeType(typeId), handler);
    }

    @Nullable
    public PowerToolActionHandler get(String typeId) {
        return handlers.get(PowerToolAction.normalizeType(typeId));
    }

    public Set<String> actionIds() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    private void registerDefaults() {
        register(PowerToolAction.TYPE_COMMAND, this::executeCommand);
        register(PowerToolAction.TYPE_CONSOLE, this::executeCommand);
        register(PowerToolAction.TYPE_MESSAGE, this::executeMessage);
        register(PowerToolAction.TYPE_ACTIONBAR, this::executeActionBar);
        register(PowerToolAction.TYPE_SOUND, this::executeSound);
        register(PowerToolAction.TYPE_PARTICLE, this::executeParticle);
        register(PowerToolAction.TYPE_POTION, this::executePotion);
        register(PowerToolAction.TYPE_TREE_FELL, this::executeTreeFell);
        register(PowerToolAction.TYPE_DISARM, this::executeDisarm);
        register(PowerToolAction.TYPE_OBLITERATE, this::executeObliterate);
    }

    private boolean executeCommand(PowerToolContext ctx, PowerToolAction action) {
        String raw = action.value();
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String unknown = PowerToolSecurity.firstUnknownPlaceholder(raw);
        if (unknown != null) {
            diagnostics.accept("PowerTool '" + ctx.toolId() + "' command has disallowed placeholder {" + unknown + "}");
            return false;
        }
        String command = PowerToolSecurity.cleanCommand(PowerToolSecurity.applyPlaceholders(raw, ctx.placeholders()));
        if (command.isEmpty()) {
            return false;
        }
        if (action.isConsoleExecution()) {
            PowerToolSettings cfg = settings.get();
            if (!PowerToolSecurity.isConsoleCommandAllowed(command, cfg.consoleAllowlist())) {
                diagnostics.accept("PowerTool '" + ctx.toolId() + "' blocked console command not in powertools.console-allowlist: " + command);
                return false;
            }
            if (cfg.blockSelectors() && PowerToolSecurity.containsSelectors(command)) {
                diagnostics.accept("PowerTool '" + ctx.toolId() + "' blocked console command containing selector: " + command);
                return false;
            }
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            Bukkit.dispatchCommand(console, command);
            return true;
        }
        return ctx.player().performCommand(command);
    }

    private boolean executeMessage(PowerToolContext ctx, PowerToolAction action) {
        if (action.value() == null || action.value().isBlank()) {
            return false;
        }
        String text = PowerToolSecurity.applyPlaceholders(action.value(), ctx.placeholders());
        TextSender.send(ctx.player(), TextFormatter.parse(text));
        return true;
    }

    private boolean executeActionBar(PowerToolContext ctx, PowerToolAction action) {
        if (action.value() == null || action.value().isBlank()) {
            return false;
        }
        String text = PowerToolSecurity.applyPlaceholders(action.value(), ctx.placeholders());
        TextSender.sendActionBar(ctx.player(), TextFormatter.parse(text));
        return true;
    }

    private boolean executeSound(PowerToolContext ctx, PowerToolAction action) {
        if (action.value() == null || action.value().isBlank()) {
            return false;
        }
        String[] parts = action.value().trim().split("\\s+");
        String sound = parts[0];
        float volume = parts.length >= 2 ? parseFloat(parts[1], 1.0F) : 1.0F;
        float pitch = parts.length >= 3 ? parseFloat(parts[2], 1.0F) : 1.0F;
        Location at = ctx.targetEntity() != null ? ctx.targetEntity().getLocation() : ctx.player().getLocation();
        if (at.getWorld() != null) {
            at.getWorld().playSound(at, sound, volume, pitch);
            return true;
        }
        return false;
    }

    private boolean executeParticle(PowerToolContext ctx, PowerToolAction action) {
        if (action.value() == null || action.value().isBlank()) {
            return false;
        }
        String[] parts = action.value().trim().split("\\s+");
        Optional<Particle> particle = BukkitParticles.resolve(parts[0]);
        if (particle.isEmpty()) {
            return false;
        }
        int count = parts.length >= 2 ? Math.max(1, Math.min(200, parseInt(parts[1], 10))) : 10;
        double spread = parts.length >= 3 ? Math.max(0.0D, Math.min(5.0D, parseDouble(parts[2], 0.35D))) : 0.35D;
        Location at = ctx.targetEntity() != null
                ? ctx.targetEntity().getLocation().add(0.0D, 0.8D, 0.0D)
                : ctx.player().getLocation().add(0.0D, 1.0D, 0.0D);
        if (at.getWorld() != null) {
            at.getWorld().spawnParticle(particle.get(), at, count, spread, spread, spread, 0.02D);
            return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private boolean executePotion(PowerToolContext ctx, PowerToolAction action) {
        if (action.value() == null || action.value().isBlank()) {
            return false;
        }
        String[] parts = action.value().trim().split("\\s+");
        String rawType = parts[0].toLowerCase(Locale.ROOT).replace("minecraft:", "");
        PotionEffectType effectType = resolvePotionEffect(rawType);
        if (effectType == null) {
            return false;
        }
        long durationMs = parts.length >= 2 ? parseDurationSafe(parts[1], 5000L) : 5000L;
        int ticks = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, PowerToolDurations.millisToTicks(durationMs)));
        int amplifier = parts.length >= 3 ? Math.max(0, Math.min(255, parseInt(parts[2], 1) - 1)) : 0;
        boolean onTarget = parts.length >= 4 && "target".equalsIgnoreCase(parts[3]);
        LivingEntity recipient = (onTarget && ctx.targetEntity() instanceof LivingEntity living) ? living : ctx.player();
        recipient.addPotionEffect(new PotionEffect(effectType, ticks, amplifier));
        return true;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static PotionEffectType resolvePotionEffect(String raw) {
        String canonical = switch (raw) {
            case "jump" -> "jump_boost";
            case "heal" -> "instant_health";
            case "harm" -> "instant_damage";
            case "regen" -> "regeneration";
            case "slow" -> "slowness";
            default -> raw;
        };
        try {
            PotionEffectType byKey = PotionEffectType.getByKey(NamespacedKey.minecraft(canonical));
            if (byKey != null) {
                return byKey;
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return PotionEffectType.getByName(raw.toUpperCase(Locale.ROOT));
    }

    // ---- Allium Custom Action: tree_fell ----

    private boolean executeTreeFell(PowerToolContext ctx, PowerToolAction action) {
        Block broken = ctx.block();
        if (broken == null) {
            return false;
        }
        Player player = ctx.player();
        if (breakingFor.contains(player.getUniqueId()) || activeFellJobs.containsKey(player.getUniqueId())) {
            return false;
        }
        Map<String, Object> params = action.params();
        int maxBlocks = intParam(params, "max-blocks", 256, 1, 1024);
        int maxRadius = intParam(params, "max-radius", 8, 1, 32);
        int maxHeight = intParam(params, "max-height", 48, 1, 128);
        int minLeaves = intParam(params, "min-natural-leaves", 4, 0, 64);
        int blocksPerTick = intParam(params, "blocks-per-tick", 6, 1, 32);
        boolean stripped = boolParam(params, "stripped", true);
        boolean stopBeforeBreak = boolParam(params, "stop-before-break", true);
        boolean breakAsPlayer = !"direct".equalsIgnoreCase(stringParam(params, "mode", "break-as-player"));
        List<TreeFamily> families = parseTreeFamilies(params.get("families"));

        World world = broken.getWorld();
        BukkitBlockView view = new BukkitBlockView(world);
        TreeScanner.Pos brokenPos = new TreeScanner.Pos(broken.getX(), broken.getY(), broken.getZ());
        TreeScanner.Pos startPos = brokenPos;
        String brokenMat = broken.getType().name().toLowerCase(Locale.ROOT);
        if (brokenMat.equals("mangrove_roots") || brokenMat.equals("muddy_mangrove_roots")) {
            for (int dy = 1; dy <= 5; dy++) {
                String up = view.material(startPos.x(), startPos.y() + dy, startPos.z());
                if ("mangrove_log".equals(up) || "stripped_mangrove_log".equals(up)) {
                    startPos = new TreeScanner.Pos(startPos.x(), startPos.y() + dy, startPos.z());
                    break;
                }
                String down = view.material(startPos.x(), startPos.y() - dy, startPos.z());
                if ("mangrove_log".equals(down) || "stripped_mangrove_log".equals(down)) {
                    startPos = new TreeScanner.Pos(startPos.x(), startPos.y() - dy, startPos.z());
                    break;
                }
            }
        }

        TreeScanner.Limits limits = new TreeScanner.Limits(maxBlocks, maxRadius, maxHeight, minLeaves, stripped);
        TreeScanner.Scan scan = TreeScanner.scan(view, startPos, families, limits);
        if (scan.result() != TreeScanner.Result.FELL || scan.logs().isEmpty()) {
            return false;
        }

        Set<Long> chunks = new HashSet<>();
        chunks.add(chunkKey(brokenPos.x() >> 4, brokenPos.z() >> 4));
        for (TreeScanner.Pos pos : scan.logs()) {
            chunks.add(chunkKey(pos.x() >> 4, pos.z() >> 4));
        }

        LazyAxeFellEvent fellEvent = new LazyAxeFellEvent(player, broken, ctx.toolId(), scan.logs().size(), breakAsPlayer);
        try {
            Bukkit.getPluginManager().callEvent(fellEvent);
        } catch (RuntimeException | LinkageError ignored) {
            // Pure unit tests without Bukkit server plugin manager
        }
        if (fellEvent.isCancelled()) {
            return false;
        }

        TaskHandle[] handleBox = new TaskHandle[1];
        ActiveFellJob job = new ActiveFellJob(
                player.getUniqueId(),
                world,
                ctx.instanceUuid(),
                new ArrayList<>(scan.logs()),
                chunks,
                blocksPerTick,
                breakAsPlayer,
                stopBeforeBreak,
                handleBox
        );
        activeFellJobs.put(player.getUniqueId(), job);
        handleBox[0] = scheduler.repeatOnServer(() -> stepFellJob(job), 50L, 50L);
        return true;
    }

    private boolean stepFellJob(ActiveFellJob job) {
        Player player = players.apply(job.playerId());
        if (player == null || !player.isOnline() || player.isDead() || !player.getWorld().equals(job.world())) {
            finishFellJob(job);
            return false;
        }
        if (job.instanceUuid() != null) {
            Optional<String> heldUuid = items.instanceUuid(player.getInventory().getItemInMainHand());
            if (heldUuid.isEmpty() || !heldUuid.get().equals(job.instanceUuid())) {
                finishFellJob(job);
                return false;
            }
        }
        for (long ck : job.chunks()) {
            if (!job.world().isChunkLoaded((int) (ck >> 32), (int) ck)) {
                finishFellJob(job);
                return false;
            }
        }

        int brokenCount = 0;
        while (brokenCount < job.blocksPerTick() && !job.remainingLogs().isEmpty()) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (job.stopBeforeBreak() && player.getGameMode() != GameMode.CREATIVE && held != null) {
                ItemMeta meta = held.getItemMeta();
                if (meta instanceof Damageable dmg && !meta.isUnbreakable() && held.getType().getMaxDurability() > 0) {
                    if (held.getType().getMaxDurability() - dmg.getDamage() <= 1) {
                        finishFellJob(job);
                        return false;
                    }
                }
            }

            TreeScanner.Pos pos = job.remainingLogs().remove(0);
            Block block = job.world().getBlockAt(pos.x(), pos.y(), pos.z());
            if (block.getType().isAir()) {
                continue;
            }
            ProtectionDecision decision = protection.checkBlockBreak(player, block.getLocation(), PowerToolBehaviorType.ID, null);
            if (!decision.allowed()) {
                continue;
            }
            if (job.breakAsPlayer()) {
                breakingFor.add(player.getUniqueId());
                try {
                    player.breakBlock(block);
                } finally {
                    breakingFor.remove(player.getUniqueId());
                }
            } else {
                block.breakNaturally(held);
                damageItemDirectly(player, held);
            }
            brokenCount++;
        }

        if (job.remainingLogs().isEmpty()) {
            finishFellJob(job);
            return false;
        }
        return true;
    }

    private void finishFellJob(ActiveFellJob job) {
        activeFellJobs.remove(job.playerId(), job);
        job.cancel();
    }

    private void damageItemDirectly(Player player, @Nullable ItemStack held) {
        if (player.getGameMode() == GameMode.CREATIVE || held == null || held.getType().getMaxDurability() <= 0) {
            return;
        }
        ItemMeta meta = held.getItemMeta();
        if (!(meta instanceof Damageable dmg) || meta.isUnbreakable()) {
            return;
        }
        int unbreaking = UNBREAKING == null ? 0 : held.getEnchantmentLevel(UNBREAKING);
        if (unbreaking > 0 && random.nextInt(unbreaking + 1) != 0) {
            return;
        }
        dmg.setDamage(dmg.getDamage() + 1);
        held.setItemMeta(meta);
    }

    private static List<TreeFamily> parseTreeFamilies(@Nullable Object raw) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            List<TreeFamily> out = new ArrayList<>();
            for (Object elem : list) {
                if (elem instanceof String s) {
                    TreeFamily.of(s.trim().toLowerCase(Locale.ROOT)).ifPresent(out::add);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        List<TreeFamily> defaults = new ArrayList<>();
        for (String id : List.of("overworld_logs", "mangrove", "cherry", "pale_oak")) {
            TreeFamily.of(id).ifPresent(defaults::add);
        }
        return defaults;
    }

    // ---- Allium Custom Action: disarm ----

    private boolean executeDisarm(PowerToolContext ctx, PowerToolAction action) {
        Entity target = ctx.targetEntity();
        if (target == null) {
            return false;
        }
        Map<String, Object> params = action.params();
        double radius = doubleParam(params, "radius", 6.0D, 0.0D, 64.0D);
        boolean excludeNpcs = boolParam(params, "exclude-npcs", true);
        Set<String> excluded = stringSetParam(params, "excluded-mobs", DEFAULT_EXCLUDED_DISARM_MOBS);
        DisarmTargetPolicy policy = new DisarmTargetPolicy(excluded, excludeNpcs);
        DisarmTargetPolicy.Verdict verdict = policy.evaluate(new DisarmTargetPolicy.TargetFacts(
                "minecraft:" + target.getType().name().toLowerCase(Locale.ROOT),
                target instanceof Player,
                target instanceof Mob,
                target.hasMetadata("NPC")
        ));
        if (verdict != DisarmTargetPolicy.Verdict.ALLOWED) {
            return false;
        }
        Mob mob = (Mob) target;
        Player player = ctx.player();
        if (radius > 0.0D && (!player.getWorld().equals(mob.getWorld())
                || player.getLocation().distanceSquared(mob.getLocation()) > radius * radius)) {
            return false;
        }
        ProtectionDecision decision = protection.checkEntityInteraction(player, mob, ctx.toolId(), ctx.causeEvent());
        if (!decision.allowed()) {
            return false;
        }
        DisarmDropRule dropRule = "always".equalsIgnoreCase(stringParam(params, "drop-rule", "slot-chance"))
                ? DisarmDropRule.ALWAYS
                : DisarmDropRule.SLOT_CHANCE;
        long pickupBlockMillis = intParam(params, "pickup-block-seconds", 120, 0, 3600) * 1000L;

        EntityEquipment eq = mob.getEquipment();
        int removed = 0;
        Location dropAt = mob.getLocation().add(0.0D, 0.5D, 0.0D);
        boolean villager = mob instanceof Villager;
        if (eq != null) {
            for (EquipmentSlot slot : DISARM_SLOTS) {
                ItemStack piece = eq.getItem(slot);
                if (piece == null || piece.getType().isAir()) {
                    continue;
                }
                float chance = dropChance(eq, slot);
                ItemStack copy = piece.clone();
                eq.setItem(slot, null);
                setDropChance(eq, slot, 0.0F);
                removed++;
                if (dropRule.drops(chance, villager, random.nextFloat())) {
                    mob.getWorld().dropItemNaturally(dropAt, copy);
                }
            }
        }
        if (mob instanceof Enderman enderman) {
            BlockData carried = enderman.getCarriedBlock();
            if (carried != null && !carried.getMaterial().isAir()) {
                Material mat = carried.getMaterial();
                enderman.setCarriedBlock(null);
                removed++;
                if (mat.isItem()) {
                    mob.getWorld().dropItemNaturally(dropAt, new ItemStack(mat));
                }
            }
        }
        if (removed == 0) {
            return false;
        }
        if (pickupBlockMillis > 0L) {
            pickupSuppression.block(mob, pickupBlockMillis);
        }
        String sound = stringParam(params, "sound", "minecraft:item.shield.break");
        if (!sound.isBlank()) {
            mob.getWorld().playSound(mob.getLocation(), sound, 1.0F, 1.2F);
        }
        return true;
    }

    private static float dropChance(EntityEquipment eq, EquipmentSlot slot) {
        return switch (slot) {
            case HAND -> eq.getItemInMainHandDropChance();
            case OFF_HAND -> eq.getItemInOffHandDropChance();
            case HEAD -> eq.getHelmetDropChance();
            case CHEST -> eq.getChestplateDropChance();
            case LEGS -> eq.getLeggingsDropChance();
            case FEET -> eq.getBootsDropChance();
            default -> 0.0F;
        };
    }

    private static void setDropChance(EntityEquipment eq, EquipmentSlot slot, float chance) {
        switch (slot) {
            case HAND -> eq.setItemInMainHandDropChance(chance);
            case OFF_HAND -> eq.setItemInOffHandDropChance(chance);
            case HEAD -> eq.setHelmetDropChance(chance);
            case CHEST -> eq.setChestplateDropChance(chance);
            case LEGS -> eq.setLeggingsDropChance(chance);
            case FEET -> eq.setBootsDropChance(chance);
            default -> { }
        }
    }

    // ---- Allium Custom Action: obliterate ----

    private boolean executeObliterate(PowerToolContext ctx, PowerToolAction action) {
        Player player = ctx.player();
        Map<String, Object> params = action.params();
        double radius = doubleParam(params, "radius", 64.0D, 1.0D, 128.0D);
        int maxTargets = intParam(params, "max-targets", 64, 1, 256);
        boolean requireUnnamed = boolParam(params, "require-unnamed", true);
        boolean excludeNpcs = boolParam(params, "exclude-npcs", true);
        boolean attributed = !"silent".equalsIgnoreCase(stringParam(params, "kill", "attributed"));
        Set<String> entityTypes = stringSetParam(params, "entity-types", Set.of("phantom"));

        Location origin = player.getLocation();
        double radiusSq = radius * radius;
        List<LivingEntity> candidates = new ArrayList<>();
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || entity instanceof Player || !living.isValid() || living.isDead()) {
                continue;
            }
            if (excludeNpcs && living.hasMetadata("NPC")) {
                continue;
            }
            if (requireUnnamed && living.getCustomName() != null && !living.getCustomName().isEmpty()) {
                continue;
            }
            String typeName = living.getType().name().toLowerCase(Locale.ROOT);
            if (!entityTypes.contains(typeName) && !entityTypes.contains("*")) {
                continue;
            }
            if (living.getLocation().distanceSquared(origin) > radiusSq) {
                continue;
            }
            if (!protection.checkEntityInteraction(player, living, ctx.toolId(), ctx.causeEvent()).allowed()) {
                continue;
            }
            candidates.add(living);
        }
        if (candidates.isEmpty()) {
            return false;
        }
        candidates.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(origin)));
        if (candidates.size() > maxTargets) {
            candidates = candidates.subList(0, maxTargets);
        }

        int killed = 0;
        for (LivingEntity target : candidates) {
            if (!target.isValid() || target.isDead()) {
                continue;
            }
            if (attributed) {
                target.damage(target.getHealth() + 1000.0D, player);
            } else {
                target.setHealth(0.0D);
            }
            if (target.isDead() || target.getHealth() <= 0.0D) {
                killed++;
            }
        }
        return killed > 0;
    }

    // ---- Parameter helpers ----

    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xffffffffL);
    }

    private static int intParam(Map<String, Object> params, String key, int fallback, int min, int max) {
        Object val = params.get(key);
        if (val instanceof Number n) {
            return Math.max(min, Math.min(max, n.intValue()));
        }
        if (val instanceof String s) {
            return Math.max(min, Math.min(max, parseInt(s, fallback)));
        }
        return fallback;
    }

    private static double doubleParam(Map<String, Object> params, String key, double fallback, double min, double max) {
        Object val = params.get(key);
        if (val instanceof Number n) {
            return Math.max(min, Math.min(max, n.doubleValue()));
        }
        if (val instanceof String s) {
            return Math.max(min, Math.min(max, parseDouble(s, fallback)));
        }
        return fallback;
    }

    private static boolean boolParam(Map<String, Object> params, String key, boolean fallback) {
        Object val = params.get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static String stringParam(Map<String, Object> params, String key, String fallback) {
        Object val = params.get(key);
        return val instanceof String s ? s : fallback;
    }

    private static Set<String> stringSetParam(Map<String, Object> params, String key, Set<String> fallback) {
        Object val = params.get(key);
        if (val instanceof List<?> list && !list.isEmpty()) {
            Set<String> out = new HashSet<>();
            for (Object elem : list) {
                if (elem instanceof String s && !s.isBlank()) {
                    out.add(s.trim().toLowerCase(Locale.ROOT).replace("minecraft:", ""));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        if (val instanceof String s && !s.isBlank()) {
            Set<String> out = new HashSet<>();
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim().toLowerCase(Locale.ROOT).replace("minecraft:", ""));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return fallback;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloat(String raw, float fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseDurationSafe(String raw, long fallback) {
        try {
            return PowerToolDurations.parseMillis(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
