package codes.castled.allium.tool.powertool;

import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.scheduler.BukkitServerThread;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundGroup;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * Built-in {@code block_replace} powertool action: replaces a clicked block with a block chosen
 * from the player's hotbar, inventory, offhand, or a configured (optionally weighted) list.
 *
 * <p>Respects {@link ProtectionService#checkBlockBreak}, Folia region ownership, creative mode,
 * unbreakable/container protection, compatible {@link BlockData} preservation, survival item
 * consumption, and old-block return/drop options.
 */
public final class BlockReplaceAction implements PowerToolActionHandler {

    private static final int HOTBAR_SLOTS = 9;
    private static final int STORAGE_SLOTS = 36;
    private static final int OFFHAND_SENTINEL = -2;

    /** Technical or unbreakable blocks that cannot be replaced or placed unless explicitly allowed. */
    private static final Set<String> UNBREAKABLE_TECHNICAL = Set.of(
            "bedrock", "barrier",
            "command_block", "chain_command_block", "repeating_command_block",
            "structure_block", "structure_void", "jigsaw",
            "end_portal", "end_portal_frame", "end_gateway", "nether_portal",
            "light", "spawner", "trial_spawner", "vault",
            "reinforced_deepslate", "moving_piston", "piston_head", "bubble_column"
    );

    enum Source { HOTBAR, INVENTORY, OFFHAND, LIST }
    enum Selection { RANDOM, SEQUENTIAL }
    enum DropMode { NONE, INVENTORY, NATURAL }

    record WeightedBlock(Material material, int weight) {}
    private record Candidate(Material material, int weight, int sourceSlot) {}

    @Nullable
    private final Plugin plugin;
    private final ProtectionService protection;
    private final RandomGenerator random;
    private final Map<String, AtomicInteger> sequenceCursors = new ConcurrentHashMap<>();

    public BlockReplaceAction(@Nullable Plugin plugin, ProtectionService protection, RandomGenerator random) {
        this.plugin = plugin;
        this.protection = Objects.requireNonNull(protection, "protection");
        this.random = Objects.requireNonNull(random, "random");
    }

    public void cleanupPlayer(UUID playerId) {
        if (playerId == null) return;
        String prefix = playerId + ":";
        sequenceCursors.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void clearAll() {
        sequenceCursors.clear();
    }

    @Override
    public boolean execute(PowerToolContext ctx, PowerToolAction action) {
        Block target = ctx.block();
        if (target == null) {
            return false;
        }
        Player player = ctx.player();
        if (BukkitServerThread.isFolia() && plugin != null
                && !BukkitServerThread.region(plugin, target.getWorld(), target.getX() >> 4, target.getZ() >> 4).isServerThread()) {
            return false;
        }

        Map<String, Object> params = action.params();
        boolean allowUnbreakable = boolParam(params, "allow-unbreakable", false);
        boolean allowContainers = boolParam(params, "allow-containers", false);
        boolean allowLiquids = boolParam(params, "allow-liquids", false);
        boolean skipSame = boolParam(params, "skip-same", true);
        boolean preserveData = boolParam(params, "preserve-data", true);
        boolean applyPhysics = boolParam(params, "physics", true);
        boolean requirePlainItems = boolParam(params, "require-plain-items", true);
        boolean weightedByStack = boolParam(params, "weighted-by-stack", false);
        boolean swing = boolParam(params, "swing", true);

        Material oldType = target.getType();
        if (!isReplaceableTarget(target, oldType, params, allowUnbreakable, allowContainers, allowLiquids)) {
            return false;
        }

        ProtectionDecision decision = protection.checkBlockBreak(player, target.getLocation(), ctx.toolId(), ctx.causeEvent());
        if (!decision.allowed()) {
            return false;
        }

        List<WeightedBlock> configuredBlocks = parseConfiguredBlocks(params.get("blocks"), action.value(), allowUnbreakable);
        Source source = parseSource(params.get("source"), !configuredBlocks.isEmpty());
        if (source == Source.LIST && configuredBlocks.isEmpty()) {
            return false;
        }
        Selection selection = parseSelection(params.get("selection"));
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        boolean defaultConsume = source != Source.LIST;
        boolean consume = boolParam(params, "consume", defaultConsume) && !creative;
        DropMode defaultDrop = consume ? DropMode.INVENTORY : DropMode.NONE;
        DropMode dropMode = creative ? DropMode.NONE : parseDropMode(params.get("drop-old"), defaultDrop);

        Candidate chosen = pickCandidate(
                ctx, oldType, source, selection, configuredBlocks,
                consume, skipSame, requirePlainItems, weightedByStack, allowUnbreakable
        );
        if (chosen == null) {
            return false;
        }

        // Capture old block drops and data BEFORE mutating the block.
        Collection<ItemStack> naturalDrops = null;
        if (dropMode == DropMode.NATURAL) {
            naturalDrops = captureDrops(target, ctx.item(), player);
        }
        BlockData previousData = null;
        if (preserveData && Bukkit.getServer() != null) {
            try {
                previousData = target.getBlockData();
            } catch (RuntimeException ignored) {
            }
        }

        // Consume one item from the player's inventory first in survival mode.
        if (consume && !consumeFromSlot(player.getInventory(), chosen.sourceSlot(), chosen.material())) {
            return false;
        }

        // Apply the new block (preserving compatible directional/slab/stair/waterlogged state when enabled).
        applyReplacement(target, chosen.material(), previousData, preserveData, applyPhysics);

        // Return or drop the replaced block in survival mode.
        if (dropMode == DropMode.INVENTORY && oldType.isItem()) {
            giveOrDrop(player, new ItemStack(oldType, 1));
        } else if (dropMode == DropMode.NATURAL && naturalDrops != null && target.getWorld() != null) {
            Location dropLoc = target.getLocation().add(0.5D, 0.5D, 0.5D);
            for (ItemStack drop : naturalDrops) {
                if (drop != null && !drop.getType().isAir() && drop.getAmount() > 0) {
                    target.getWorld().dropItemNaturally(dropLoc, drop);
                }
            }
        }

        // Tactile feedback.
        if (swing) {
            try {
                if (PowerToolBinding.HAND_OFF.equals(ctx.hand())) {
                    player.swingOffHand();
                } else {
                    player.swingMainHand();
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        playSound(target, params);
        return true;
    }

    private boolean isReplaceableTarget(
            Block target,
            Material oldType,
            Map<String, Object> params,
            boolean allowUnbreakable,
            boolean allowContainers,
            boolean allowLiquids
    ) {
        if (oldType == null || oldType.isAir()) {
            return false;
        }
        String key = oldType.name().toLowerCase(Locale.ROOT);
        if (!allowLiquids && ("water".equals(key) || "lava".equals(key))) {
            return false;
        }
        if (!allowUnbreakable && isUnbreakableOrTechnical(oldType, key)) {
            return false;
        }
        if (!allowContainers) {
            try {
                BlockState state = target.getState();
                if (state instanceof InventoryHolder) {
                    return false;
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        Set<String> allowSet = stringSetParam(params, "allow-replace");
        if (!allowSet.isEmpty() && !allowSet.contains(key)) {
            return false;
        }
        Set<String> denySet = stringSetParam(params, "deny-replace");
        return !denySet.contains(key);
    }

    @Nullable
    private Candidate pickCandidate(
            PowerToolContext ctx,
            Material oldType,
            Source source,
            Selection selection,
            List<WeightedBlock> configuredBlocks,
            boolean consume,
            boolean skipSame,
            boolean requirePlainItems,
            boolean weightedByStack,
            boolean allowUnbreakable
    ) {
        PlayerInventory inv = ctx.player().getInventory();
        int toolSlot = PowerToolBinding.HAND_OFF.equals(ctx.hand()) ? OFFHAND_SENTINEL : inv.getHeldItemSlot();

        if (source == Source.LIST) {
            List<Candidate> eligible = new ArrayList<>();
            for (WeightedBlock wb : configuredBlocks) {
                if (skipSame && wb.material() == oldType) {
                    continue;
                }
                int slot = -1;
                if (consume) {
                    slot = findMatchingStorageSlot(inv, wb.material(), toolSlot, requirePlainItems);
                    if (slot == -1) {
                        continue;
                    }
                }
                eligible.add(new Candidate(wb.material(), wb.weight(), slot));
            }
            if (eligible.isEmpty()) {
                return null;
            }
            return selectCandidate(ctx, eligible, selection);
        }

        // Build optional material whitelist/weight lookup when blocks are provided alongside hotbar/inventory/offhand.
        Map<Material, Integer> filterWeights = new LinkedHashMap<>();
        for (WeightedBlock wb : configuredBlocks) {
            filterWeights.merge(wb.material(), wb.weight(), Math::max);
        }

        List<Candidate> eligible = new ArrayList<>();
        if (source == Source.OFFHAND) {
            if (toolSlot != OFFHAND_SENTINEL) {
                addSlotCandidate(eligible, inv.getItemInOffHand(), OFFHAND_SENTINEL, oldType,
                        filterWeights, skipSame, requirePlainItems, weightedByStack, allowUnbreakable);
            }
        } else {
            ItemStack[] storage = inv.getStorageContents();
            int limit = Math.min(storage == null ? 0 : storage.length,
                    source == Source.HOTBAR ? HOTBAR_SLOTS : STORAGE_SLOTS);
            for (int slot = 0; slot < limit; slot++) {
                if (slot == toolSlot) {
                    continue;
                }
                addSlotCandidate(eligible, storage[slot], slot, oldType,
                        filterWeights, skipSame, requirePlainItems, weightedByStack, allowUnbreakable);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        return selectCandidate(ctx, eligible, selection);
    }

    private void addSlotCandidate(
            List<Candidate> eligible,
            @Nullable ItemStack stack,
            int slot,
            Material oldType,
            Map<Material, Integer> filterWeights,
            boolean skipSame,
            boolean requirePlainItems,
            boolean weightedByStack,
            boolean allowUnbreakable
    ) {
        if (stack == null || stack.getAmount() <= 0) {
            return;
        }
        Material mat = stack.getType();
        if (!isPlaceableMaterial(mat, allowUnbreakable)) {
            return;
        }
        if (skipSame && mat == oldType) {
            return;
        }
        if (!filterWeights.isEmpty() && !filterWeights.containsKey(mat)) {
            return;
        }
        if (requirePlainItems && isCustomItem(stack)) {
            return;
        }
        int baseWeight = filterWeights.isEmpty() ? 1 : filterWeights.get(mat);
        int weight = weightedByStack ? Math.max(1, stack.getAmount()) * baseWeight : baseWeight;
        eligible.add(new Candidate(mat, weight, slot));
    }

    private Candidate selectCandidate(PowerToolContext ctx, List<Candidate> eligible, Selection selection) {
        if (eligible.size() == 1) {
            return eligible.get(0);
        }
        if (selection == Selection.SEQUENTIAL) {
            String cursorKey = ctx.player().getUniqueId() + ":" + ctx.toolId();
            int index = Math.floorMod(
                    sequenceCursors.computeIfAbsent(cursorKey, k -> new AtomicInteger()).getAndIncrement(),
                    eligible.size()
            );
            return eligible.get(index);
        }
        long totalWeight = 0L;
        for (Candidate c : eligible) {
            totalWeight += Math.max(1, c.weight());
        }
        long roll = random.nextLong(totalWeight);
        long acc = 0L;
        for (Candidate c : eligible) {
            acc += Math.max(1, c.weight());
            if (roll < acc) {
                return c;
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    private static int findMatchingStorageSlot(PlayerInventory inv, Material material, int excludeSlot, boolean requirePlainItems) {
        ItemStack[] storage = inv.getStorageContents();
        if (storage != null) {
            int limit = Math.min(storage.length, STORAGE_SLOTS);
            for (int i = 0; i < limit; i++) {
                if (i == excludeSlot) continue;
                ItemStack stack = storage[i];
                if (stack != null && stack.getType() == material && stack.getAmount() > 0
                        && (!requirePlainItems || !isCustomItem(stack))) {
                    return i;
                }
            }
        }
        if (excludeSlot != OFFHAND_SENTINEL) {
            ItemStack offhand = inv.getItemInOffHand();
            if (offhand != null && offhand.getType() == material && offhand.getAmount() > 0
                    && (!requirePlainItems || !isCustomItem(offhand))) {
                return OFFHAND_SENTINEL;
            }
        }
        return -1;
    }

    private static boolean consumeFromSlot(PlayerInventory inv, int slot, Material expected) {
        if (slot == OFFHAND_SENTINEL) {
            ItemStack off = inv.getItemInOffHand();
            if (off == null || off.getType() != expected || off.getAmount() <= 0) {
                return false;
            }
            if (off.getAmount() == 1) {
                inv.setItemInOffHand(null);
            } else {
                ItemStack copy = off.clone();
                copy.setAmount(off.getAmount() - 1);
                inv.setItemInOffHand(copy);
            }
            return true;
        }
        if (slot < 0) {
            return false;
        }
        ItemStack[] storage = inv.getStorageContents();
        ItemStack stack = (storage != null && slot < storage.length) ? storage[slot] : inv.getItem(slot);
        if (stack == null || stack.getType() != expected || stack.getAmount() <= 0) {
            return false;
        }
        if (stack.getAmount() == 1) {
            inv.setItem(slot, null);
        } else {
            ItemStack copy = stack.clone();
            copy.setAmount(stack.getAmount() - 1);
            inv.setItem(slot, copy);
        }
        return true;
    }

    private static void applyReplacement(
            Block target,
            Material replacement,
            @Nullable BlockData previousData,
            boolean preserveData,
            boolean applyPhysics
    ) {
        if (preserveData && previousData != null && Bukkit.getServer() != null) {
            try {
                BlockData nextData = replacement.createBlockData();
                copyCompatibleData(previousData, nextData);
                target.setBlockData(nextData, applyPhysics);
                return;
            } catch (RuntimeException | LinkageError ignored) {
                // Fall back to plain Material replacement below.
            }
        }
        target.setType(replacement, applyPhysics);
    }

    static void copyCompatibleData(BlockData from, BlockData to) {
        if (from instanceof Directional f && to instanceof Directional t) {
            BlockFace facing = f.getFacing();
            if (t.getFaces().contains(facing)) {
                t.setFacing(facing);
            }
        }
        if (from instanceof Orientable f && to instanceof Orientable t) {
            if (t.getAxes().contains(f.getAxis())) {
                t.setAxis(f.getAxis());
            }
        }
        if (from instanceof Bisected f && to instanceof Bisected t) {
            t.setHalf(f.getHalf());
        }
        if (from instanceof Slab f && to instanceof Slab t) {
            t.setType(f.getType());
        }
        if (from instanceof Stairs f && to instanceof Stairs t) {
            t.setShape(f.getShape());
        }
        if (from instanceof Waterlogged f && to instanceof Waterlogged t) {
            t.setWaterlogged(f.isWaterlogged());
        }
        if (from instanceof Openable f && to instanceof Openable t) {
            t.setOpen(f.isOpen());
        }
        if (from instanceof Rotatable f && to instanceof Rotatable t) {
            t.setRotation(f.getRotation());
        }
        if (from instanceof MultipleFacing f && to instanceof MultipleFacing t) {
            Set<BlockFace> allowed = t.getAllowedFaces();
            for (BlockFace face : allowed) {
                t.setFace(face, f.getFaces().contains(face));
            }
        }
    }

    private static Collection<ItemStack> captureDrops(Block target, ItemStack tool, Player player) {
        try {
            Collection<ItemStack> drops = target.getDrops(tool, player);
            if (drops != null) {
                return new ArrayList<>(drops);
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        Material type = target.getType();
        return type.isItem() ? List.of(new ItemStack(type, 1)) : List.of();
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (overflow != null && !overflow.isEmpty() && player.getWorld() != null) {
            for (ItemStack extra : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), extra);
            }
        }
    }

    private static void playSound(Block target, Map<String, Object> params) {
        if (target.getWorld() == null) {
            return;
        }
        Object rawSound = params.get("sound");
        String sound = rawSound == null ? "auto" : String.valueOf(rawSound).trim();
        if (sound.isEmpty() || "none".equalsIgnoreCase(sound) || "false".equalsIgnoreCase(sound)) {
            return;
        }
        Location loc = target.getLocation().add(0.5D, 0.5D, 0.5D);
        if ("auto".equalsIgnoreCase(sound)) {
            if (Bukkit.getServer() == null) {
                return;
            }
            try {
                SoundGroup group = target.getBlockData().getSoundGroup();
                target.getWorld().playSound(loc, group.getPlaceSound(), group.getVolume(), group.getPitch());
            } catch (RuntimeException | LinkageError ignored) {
            }
            return;
        }
        String[] parts = sound.split("\\s+");
        float volume = parts.length >= 2 ? parseFloat(parts[1], 1.0F) : 1.0F;
        float pitch = parts.length >= 3 ? parseFloat(parts[2], 1.0F) : 1.0F;
        target.getWorld().playSound(loc, parts[0], volume, pitch);
    }

    private static boolean isCustomItem(ItemStack stack) {
        if (Bukkit.getServer() == null) {
            return false;
        }
        try {
            if (!stack.hasItemMeta()) {
                return false;
            }
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return false;
            }
            return meta.hasDisplayName()
                    || meta.hasLore()
                    || meta.hasEnchants()
                    || meta.hasCustomModelData()
                    || !meta.getPersistentDataContainer().getKeys().isEmpty();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static List<WeightedBlock> parseConfiguredBlocks(
            @Nullable Object blocksParam,
            @Nullable String actionValue,
            boolean allowUnbreakable
    ) {
        List<String> tokens = new ArrayList<>();
        collectTokens(blocksParam, tokens);
        collectTokens(actionValue, tokens);
        if (tokens.isEmpty()) {
            return List.of();
        }
        Map<Material, Integer> merged = new LinkedHashMap<>();
        for (String token : tokens) {
            WeightedBlock parsed = parseWeightedBlock(token, allowUnbreakable);
            if (parsed != null) {
                merged.merge(parsed.material(), parsed.weight(), Integer::sum);
            }
        }
        List<WeightedBlock> out = new ArrayList<>(merged.size());
        merged.forEach((mat, w) -> out.add(new WeightedBlock(mat, w)));
        return out;
    }

    private static void collectTokens(@Nullable Object raw, List<String> out) {
        if (raw == null) {
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object elem : list) {
                if (elem != null) {
                    collectTokens(String.valueOf(elem), out);
                }
            }
            return;
        }
        if (raw instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,\\s]+")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
    }

    @Nullable
    static WeightedBlock parseWeightedBlock(String raw, boolean allowUnbreakable) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        int weight = 1;
        int colon = s.lastIndexOf(':');
        if (colon > 0 && colon < s.length() - 1) {
            try {
                int parsed = Integer.parseInt(s.substring(colon + 1));
                if (parsed >= 1 && parsed <= 10_000) {
                    weight = parsed;
                    s = s.substring(0, colon);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        Material mat = Material.matchMaterial(s);
        if (!isPlaceableMaterial(mat, allowUnbreakable)) {
            return null;
        }
        return new WeightedBlock(mat, weight);
    }

    private static boolean isPlaceableMaterial(@Nullable Material mat, boolean allowUnbreakable) {
        if (mat == null || mat.isAir() || !mat.isBlock()) {
            return false;
        }
        String key = mat.name().toLowerCase(Locale.ROOT);
        if ("water".equals(key) || "lava".equals(key) || "fire".equals(key) || "soul_fire".equals(key)) {
            return false;
        }
        return allowUnbreakable || !isUnbreakableOrTechnical(mat, key);
    }

    private static boolean isUnbreakableOrTechnical(Material mat, String key) {
        if (UNBREAKABLE_TECHNICAL.contains(key)) {
            return true;
        }
        try {
            return mat.getHardness() < 0.0F;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static Source parseSource(@Nullable Object raw, boolean hasConfiguredBlocks) {
        if (raw == null) {
            return hasConfiguredBlocks ? Source.LIST : Source.HOTBAR;
        }
        return switch (String.valueOf(raw).trim().toLowerCase(Locale.ROOT)) {
            case "inventory", "inv" -> Source.INVENTORY;
            case "offhand", "off_hand", "off-hand" -> Source.OFFHAND;
            case "list", "blocks", "hardcoded" -> Source.LIST;
            default -> Source.HOTBAR;
        };
    }

    private static Selection parseSelection(@Nullable Object raw) {
        if (raw == null) {
            return Selection.RANDOM;
        }
        return switch (String.valueOf(raw).trim().toLowerCase(Locale.ROOT)) {
            case "sequential", "cycle", "order", "ordered" -> Selection.SEQUENTIAL;
            default -> Selection.RANDOM;
        };
    }

    private static DropMode parseDropMode(@Nullable Object raw, DropMode fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean b) {
            return b ? DropMode.NATURAL : DropMode.NONE;
        }
        return switch (String.valueOf(raw).trim().toLowerCase(Locale.ROOT)) {
            case "inventory", "inv", "give" -> DropMode.INVENTORY;
            case "natural", "drop", "true" -> DropMode.NATURAL;
            case "none", "false" -> DropMode.NONE;
            default -> fallback;
        };
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

    private static Set<String> stringSetParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        if (val instanceof List<?> list) {
            for (Object elem : list) {
                if (elem != null) {
                    String cleaned = String.valueOf(elem).trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
                    if (!cleaned.isEmpty()) out.add(cleaned);
                }
            }
        } else if (val instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,\\s]+")) {
                String cleaned = part.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
                if (!cleaned.isEmpty()) out.add(cleaned);
            }
        }
        return out;
    }

    private static float parseFloat(String raw, float fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
