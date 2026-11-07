package codes.castled.allium.tool.disarmer;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.message.Messages;
import codes.castled.allium.protection.ProtectionDecision;
import codes.castled.allium.protection.ProtectionService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * Mob Disarmer behavior ({@code allium:mob_disarmer}), independent of the item's appearance.
 *
 * <p>Only the main hand activates the tool, once per click. A click is processed in this order:
 * identify the held tool; check permission, target, distance and removable equipment; check
 * the tool's charges; admit one committing click per player within the guard window; ask
 * protection; re-read the held stack and target; then commit. The commit takes every removable
 * piece out of its slot, writes the new charge state (restoring the equipment if the write is
 * rejected), decides each piece's drop once per the configured {@link DisarmDropRule} and
 * zeroes the slot's drop chance. A disarm that removes nothing spends nothing. All methods run on
 * the server thread.
 */
public final class MobDisarmerService {

    public static final String BEHAVIOR_ID = "allium:mob_disarmer";
    public static final String PERMISSION_USE = "allium.mobdisarmer.use";
    public static final String PERMISSION_BYPASS = "allium.mobdisarmer.bypass";

    /** Hands first, so a fully kitted mob reads as disarmed. */
    static final EquipmentSlot[] DISARM_SLOTS = {
            EquipmentSlot.HAND,
            EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    static final NamespacedKey LEGACY_ITEM_ID = new NamespacedKey("allium", "custom_item_id");
    static final NamespacedKey LEGACY_CHARGES = new NamespacedKey("allium", "mob_disarmer_charges");
    static final NamespacedKey LEGACY_RECHARGE_AT = new NamespacedKey("allium", "mob_disarmer_recharge_at");
    static final NamespacedKey LEGACY_BROKEN = new NamespacedKey("allium", "mob_disarmer_broken");
    static final NamespacedKey LEGACY_USES = new NamespacedKey("allium", "mob_disarmer_uses");

    public enum Outcome {
        /** Not a main-hand Mob Disarmer click, or not a target this tool handles. */
        IGNORED,
        /** Refused before anything changed, with a message. */
        REFUSED,
        /** The target had nothing removable; nothing was spent and the click passes through. */
        NOTHING_TO_REMOVE,
        /** A duplicate committing click inside the guard window. */
        GUARDED,
        /** Protection denied the action. */
        DENIED,
        /** The held stack or target changed during checks; nothing was committed. */
        STALE,
        /** The tool state could not be written; equipment was restored. */
        WRITE_FAILED,
        DISARMED,
        /** Disarmed, and the tool broke. */
        BROKE
    }

    /** Sends a localized message; production uses {@code MessageManager#send}. */
    @FunctionalInterface
    public interface Notifier {
        void send(Player player, Messages message, String... replacements);
    }

    private final ItemService items;
    private final ProtectionService protection;
    private final Notifier notifier;
    private final Consumer<String> diagnostics;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final PickupSuppression pickups;
    private final Map<UUID, Long> lastStatus = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile MobDisarmerSettings settings;
    private volatile ClickGuard clickGuard;

    public MobDisarmerService(
            ItemService items,
            ProtectionService protection,
            Notifier notifier,
            Consumer<String> diagnostics,
            LongSupplier clock,
            LongSupplier nanoClock,
            RandomGenerator random,
            MobDisarmerSettings settings
    ) {
        this.items = Objects.requireNonNull(items, "items");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.pickups = new PickupSuppression(clock);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clickGuard = new ClickGuard(settings.clickGuardMillis(), nanoClock);
        bindAppearances(settings);
    }

    public MobDisarmerSettings settings() {
        return settings;
    }

    public PickupSuppression pickups() {
        return pickups;
    }

    private void bindAppearances(MobDisarmerSettings snapshot) {
        items.setPreferredAppearanceForBehavior(BEHAVIOR_ID, snapshot.appearance());
        for (ProviderKey key : snapshot.boundAppearances()) {
            items.bindAppearanceToBehavior(key, BEHAVIOR_ID);
        }
    }

    public Outcome handleEntityInteraction(Player player, Entity target, EquipmentSlot hand, Cancellable event, @Nullable Event cause) {
        MobDisarmerSettings config = settings;
        if (!config.enabled() || hand != EquipmentSlot.HAND) {
            return Outcome.IGNORED;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack tool = importLegacyIfPresent(player, inventory.getItemInMainHand(), config);
        if (tool == null) {
            return Outcome.IGNORED;
        }
        ItemRef ref = items.identify(tool).orElse(null);
        if (ref == null || !ref.behaviorId().map(BEHAVIOR_ID::equals).orElse(false)) {
            return Outcome.IGNORED;
        }

        if (!player.hasPermission(PERMISSION_USE)) {
            notifier.send(player, Messages.NO_PERMISSION);
            return Outcome.REFUSED;
        }
        DisarmTargetPolicy.Verdict verdict = config.targetPolicy().evaluate(facts(target));
        switch (verdict) {
            case PLAYER -> {
                event.setCancelled(true);
                notifier.send(player, Messages.TOOL_DISARMER_PLAYER);
                return Outcome.REFUSED;
            }
            case NOT_A_MOB -> {
                return Outcome.IGNORED;
            }
            case EXCLUDED_TYPE, NPC -> {
                notifier.send(player, Messages.TOOL_DISARMER_EXCLUDED, "{target}", describe(target));
                return Outcome.REFUSED;
            }
            case ALLOWED -> { }
        }
        Mob mob = (Mob) target;
        if (config.radius() > 0.0D && !withinRadius(player, mob, config.radius())) {
            notifier.send(player, Messages.TOOL_DISARMER_TOO_FAR);
            return Outcome.REFUSED;
        }
        if (!hasRemovable(mob)) {
            return Outcome.NOTHING_TO_REMOVE;
        }
        if (tool.getAmount() != 1) {
            event.setCancelled(true);
            notifier.send(player, Messages.TOOL_DISARMER_SINGLE);
            return Outcome.REFUSED;
        }

        long now = clock.getAsLong();
        int max = config.rules().maxCharges();
        DisarmerCharges stored = DisarmerCharges.fromState(ref.stateData(), max);
        DisarmerCharges charges = stored.settle(now, max);
        boolean bypass = player.hasPermission(PERMISSION_BYPASS);
        DisarmerCharges.Availability availability = charges.availability(now);
        if (availability.kind() == DisarmerCharges.Availability.Kind.BROKEN) {
            event.setCancelled(true);
            notifier.send(player, Messages.TOOL_DISARMER_BROKEN);
            return Outcome.REFUSED;
        }
        if (!bypass && availability.kind() == DisarmerCharges.Availability.Kind.RECHARGING) {
            event.setCancelled(true);
            notifier.send(player, Messages.TOOL_DISARMER_RECHARGING, "{time}", formatDuration(availability.remainingMillis()));
            return Outcome.REFUSED;
        }
        if (!clickGuard.tryAcquire(player.getUniqueId())) {
            event.setCancelled(true);
            return Outcome.GUARDED;
        }

        ToolFingerprint before = fingerprint(inventory, tool, ref);
        ProtectionDecision decision = protection.checkEntityInteraction(player, mob, BEHAVIOR_ID, cause);
        if (!decision.allowed()) {
            if (decision.message() != null && !decision.message().isBlank()) {
                notifier.send(player, Messages.TOOL_DISARMER_PROTECTED_REASON, "{reason}", decision.message());
            } else {
                notifier.send(player, Messages.TOOL_DISARMER_PROTECTED);
            }
            return Outcome.DENIED;
        }

        // Protection listeners run arbitrary code. Commit only to the exact tool and live target checked above.
        ItemStack liveTool = inventory.getItemInMainHand();
        ItemRef liveRef = items.identify(liveTool).orElse(null);
        if (liveRef == null || !before.equals(fingerprint(inventory, liveTool, liveRef))
                || !mob.isValid() || mob.isDead() || !player.isOnline() || player.isDead()) {
            return Outcome.STALE;
        }

        DisarmerCharges next;
        boolean broke = false;
        if (bypass) {
            next = charges.recordBypassUse();
        } else {
            DisarmerCharges.Consumption consumption = charges.consume(now, config.rules(), random.nextDouble());
            next = consumption.next();
            broke = consumption.broke();
        }

        List<Removed> removed = takeEquipment(mob);
        if (removed.isEmpty()) {
            return Outcome.NOTHING_TO_REMOVE;
        }
        if (!items.updateState(liveTool, next.toState())) {
            restore(mob, removed);
            warnOnce("state-write", "Mob Disarmer state write was rejected for " + player.getName()
                    + "; equipment was restored and nothing was spent.");
            notifier.send(player, Messages.TOOL_DISARMER_UPDATE_FAILED);
            return Outcome.WRITE_FAILED;
        }
        inventory.setItemInMainHand(liveTool);
        syncAppearance(inventory, liveTool, liveRef.appearance(), next, config);

        Location dropAt = mob.getLocation().add(0.0D, 0.5D, 0.0D);
        int dropped = 0;
        for (Removed piece : removed) {
            if (config.dropRule().drops(piece.dropChance(), piece.guaranteed(), random.nextFloat())) {
                mob.getWorld().dropItemNaturally(dropAt, piece.stack());
                dropped++;
            }
        }
        if (config.pickupBlockMillis() > 0L) {
            pickups.block(mob, config.pickupBlockMillis());
        }
        playSound(mob, config.sound());
        event.setCancelled(true);

        String name = describe(mob);
        if (bypass) {
            notifier.send(player, Messages.TOOL_DISARMER_DISARMED_BYPASS, "{target}", name,
                    "{removed}", Integer.toString(removed.size()), "{dropped}", Integer.toString(dropped));
        } else {
            notifier.send(player, Messages.TOOL_DISARMER_DISARMED, "{target}", name,
                    "{removed}", Integer.toString(removed.size()), "{dropped}", Integer.toString(dropped),
                    "{charges}", Integer.toString(next.charges()), "{max}", Integer.toString(max));
            if (broke) {
                notifier.send(player, Messages.TOOL_DISARMER_BROKE);
            } else if (next.charges() == 0) {
                notifier.send(player, Messages.TOOL_DISARMER_LAST_CHARGE, "{time}", formatDuration(config.rules().rechargeMillis()));
            }
        }
        return broke ? Outcome.BROKE : Outcome.DISARMED;
    }

    /**
     * Right-clicking air with the tool settles a finished recharge (writing it back) and reports
     * the charge state; the message is rate-limited, the state work is not.
     */
    public void handleAirUse(Player player, EquipmentSlot hand) {
        MobDisarmerSettings config = settings;
        if (!config.enabled() || hand != EquipmentSlot.HAND) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack tool = importLegacyIfPresent(player, inventory.getItemInMainHand(), config);
        if (tool == null) {
            return;
        }
        ItemRef ref = items.identify(tool).orElse(null);
        if (ref == null || !ref.behaviorId().map(BEHAVIOR_ID::equals).orElse(false) || tool.getAmount() != 1) {
            return;
        }
        long now = clock.getAsLong();
        int max = config.rules().maxCharges();
        DisarmerCharges stored = DisarmerCharges.fromState(ref.stateData(), max);
        DisarmerCharges charges = stored.settle(now, max);
        if (!charges.equals(stored)) {
            if (items.updateState(tool, charges.toState())) {
                inventory.setItemInMainHand(tool);
                syncAppearance(inventory, tool, ref.appearance(), charges, config);
            }
        }
        if (!canSendStatus(player.getUniqueId(), now, config)) {
            return;
        }
        DisarmerCharges.Availability availability = charges.availability(now);
        if (availability.kind() == DisarmerCharges.Availability.Kind.BROKEN) {
            notifier.send(player, Messages.TOOL_DISARMER_BROKEN);
        } else if (player.hasPermission(PERMISSION_BYPASS)) {
            notifier.send(player, Messages.TOOL_DISARMER_STATUS_BYPASS);
        } else if (availability.kind() == DisarmerCharges.Availability.Kind.RECHARGING) {
            notifier.send(player, Messages.TOOL_DISARMER_RECHARGING, "{time}", formatDuration(availability.remainingMillis()));
        } else {
            notifier.send(player, Messages.TOOL_DISARMER_STATUS,
                    "{charges}", Integer.toString(charges.charges()), "{max}", Integer.toString(max));
        }
    }

    /**
     * Brings one stack's charge visual up to date: settles a finished recharge (the state write
     * restamps the model) or restamps a stale model, and applies charge-variant appearances.
     * Event-driven only (the tool becomes held, is swapped between hands, or on join and container
     * open); nothing polls. Returns the stack to put back, or empty when nothing changed.
     */
    public Optional<ItemStack> refreshVisual(@Nullable ItemStack stack) {
        MobDisarmerSettings config = settings;
        if (!config.enabled() || isEmpty(stack) || stack.getAmount() != 1) {
            return Optional.empty();
        }
        ItemRef ref = items.identify(stack).orElse(null);
        if (ref == null || !ref.behaviorId().map(BEHAVIOR_ID::equals).orElse(false)) {
            return Optional.empty();
        }
        int max = config.rules().maxCharges();
        DisarmerCharges stored = DisarmerCharges.fromState(ref.stateData(), max);
        DisarmerCharges settled = stored.settle(clock.getAsLong(), max);
        boolean changed;
        if (!settled.equals(stored)) {
            changed = items.updateState(stack, settled.toState());
            if (!changed) {
                return Optional.empty();
            }
        } else {
            changed = items.refreshModelSelection(stack);
        }
        ItemStack result = stack;
        Optional<ProviderKey> variant = config.variantFor(ref.appearance(), settled);
        if (variant.isPresent() && !variant.get().equals(ref.appearance())) {
            ResolutionResult swapped = items.swapOrRefreshAppearance(stack, variant.get());
            if (swapped.isResolved()) {
                result = swapped.itemStack().orElseThrow();
                changed = true;
            } else {
                warnOnce("variant:" + variant.get(), "Mob Disarmer charge variant " + variant.get()
                        + " is unavailable; keeping " + ref.appearance() + " (" + swapped.diagnosticMessage() + ")");
            }
        }
        return changed ? Optional.of(result) : Optional.empty();
    }

    /** Refreshes the disarmer in one inventory slot; returns whether it changed. */
    public boolean refreshSlot(Player player, int slot) {
        PlayerInventory inventory = player.getInventory();
        Optional<ItemStack> refreshed = refreshVisual(inventory.getItem(slot));
        refreshed.ifPresent(stack -> inventory.setItem(slot, stack));
        return refreshed.isPresent();
    }

    /** Refreshes every disarmer in the player's inventory, armor and off hand included; returns how many changed. */
    public int refreshInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        int changed = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            Optional<ItemStack> refreshed = refreshVisual(contents[slot]);
            if (refreshed.isPresent()) {
                inventory.setItem(slot, refreshed.get());
                changed++;
            }
        }
        return changed;
    }

    public void forget(UUID playerId) {
        clickGuard.forget(playerId);
        lastStatus.remove(playerId);
    }

    private boolean canSendStatus(UUID playerId, long now, MobDisarmerSettings config) {
        Long last = lastStatus.get(playerId);
        if (last != null && now - last < config.statusCooldownMillis()) {
            return false;
        }
        lastStatus.put(playerId, now);
        return true;
    }

    /**
     * Converts a pre-recode Mob Disarmer in the main hand into an Allium item carrying the
     * imported charges. Returns the stack now in hand, or {@code null} when a legacy tool could
     * not be converted (it is then left untouched and never acts with default charges).
     */
    @Nullable
    ItemStack importLegacyIfPresent(Player player, @Nullable ItemStack stack, MobDisarmerSettings config) {
        if (stack == null || stack.getType() == Material.AIR || stack.getType().isAir()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta == null ? null : meta.getPersistentDataContainer();
        if (pdc == null || pdc.has(ItemService.KEY_BEHAVIOR_ID, PersistentDataType.STRING)
                || !LegacyDisarmerImport.isLegacyVariant(read(pdc, LEGACY_ITEM_ID, PersistentDataType.STRING))) {
            return stack;
        }
        DisarmerCharges imported = LegacyDisarmerImport.importCharges(
                read(pdc, LEGACY_CHARGES, PersistentDataType.INTEGER),
                read(pdc, LEGACY_RECHARGE_AT, PersistentDataType.LONG),
                Boolean.TRUE.equals(read(pdc, LEGACY_BROKEN, PersistentDataType.BOOLEAN)),
                read(pdc, LEGACY_USES, PersistentDataType.LONG),
                config.rules().maxCharges());
        ProviderKey nativeAppearance = items.identify(stack).map(ItemRef::appearance).orElse(null);
        ProviderKey appearance = config.appearance();
        if (nativeAppearance != null && !nativeAppearance.isVanilla()) {
            appearance = config.variantFor(nativeAppearance, imported).orElse(nativeAppearance);
        }
        ResolutionResult created = items.create(new ItemRef(appearance, BEHAVIOR_ID, 1, imported.toState()));
        if (!created.isResolved() || stack.getAmount() != 1) {
            warnOnce("legacy:" + appearance, "Legacy Mob Disarmer held by " + player.getName()
                    + " was left unchanged: " + (stack.getAmount() != 1 ? "stacked legacy tools are not split"
                    : created.diagnosticMessage()));
            return null;
        }
        ItemStack converted = created.itemStack().orElseThrow();
        player.getInventory().setItemInMainHand(converted);
        diagnostics.accept("Imported legacy Mob Disarmer for " + player.getName() + " as " + appearance
                + " with " + imported.charges() + " charge(s)" + (imported.broken() ? " (broken)" : "")
                + (imported.rechargeAt() > 0L ? ", recharging until " + imported.rechargeAt() : "") + ".");
        return player.getInventory().getItemInMainHand();
    }

    private void syncAppearance(PlayerInventory inventory, ItemStack tool, ProviderKey current, DisarmerCharges state, MobDisarmerSettings config) {
        Optional<ProviderKey> variant = config.variantFor(current, state);
        if (variant.isEmpty() || variant.get().equals(current)) {
            return;
        }
        ResolutionResult swapped = items.swapOrRefreshAppearance(tool, variant.get());
        if (swapped.isResolved()) {
            inventory.setItemInMainHand(swapped.itemStack().orElseThrow());
        } else {
            warnOnce("variant:" + variant.get(), "Mob Disarmer charge variant " + variant.get()
                    + " is unavailable; keeping " + current + " (" + swapped.diagnosticMessage() + ")");
        }
    }

    private static DisarmTargetPolicy.TargetFacts facts(Entity target) {
        return new DisarmTargetPolicy.TargetFacts(
                entityTypeKey(target),
                target instanceof Player,
                target instanceof Mob,
                target.hasMetadata("NPC"));
    }

    private static String entityTypeKey(Entity target) {
        try {
            return target.getType().getKey().toString();
        } catch (RuntimeException e) {
            return "minecraft:" + target.getType().name().toLowerCase(Locale.ROOT);
        }
    }

    private static boolean withinRadius(Player player, Entity target, double radius) {
        Location from = player.getLocation();
        Location to = target.getLocation();
        return from.getWorld() != null && from.getWorld().equals(to.getWorld()) && from.distanceSquared(to) <= radius * radius;
    }

    private static boolean hasRemovable(Mob mob) {
        EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            for (EquipmentSlot slot : DISARM_SLOTS) {
                if (!isEmpty(slotItem(equipment, slot))) {
                    return true;
                }
            }
        }
        return carriedBlockItem(mob) != null;
    }

    /**
     * Takes every removable piece out of the mob before anything drops, so a piece can only
     * exist once. A slot that does not report empty after clearing keeps its piece. Villager
     * gear and an enderman's carried block are guaranteed drops, as in pre-recode Allium.
     */
    private static List<Removed> takeEquipment(Mob mob) {
        List<Removed> removed = new ArrayList<>();
        boolean guaranteed = mob instanceof Villager;
        EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            for (EquipmentSlot slot : DISARM_SLOTS) {
                ItemStack piece = slotItem(equipment, slot);
                if (isEmpty(piece)) {
                    continue;
                }
                float chance = dropChance(equipment, slot);
                ItemStack taken = piece.clone();
                equipment.setItem(slot, null);
                if (!isEmpty(slotItem(equipment, slot))) {
                    continue;
                }
                setDropChance(equipment, slot, 0.0F);
                removed.add(new Removed(slot, taken, chance, guaranteed, null));
            }
        }
        if (mob instanceof Enderman enderman) {
            Material material = carriedBlockItem(mob);
            if (material != null) {
                BlockData original = enderman.getCarriedBlock();
                enderman.setCarriedBlock(null);
                if (enderman.getCarriedBlock() == null) {
                    // Vanilla always drops a carried block when an enderman dies.
                    removed.add(new Removed(null, new ItemStack(material), 1.0F, true, original));
                }
            }
        }
        return removed;
    }

    private static void restore(Mob mob, List<Removed> removed) {
        EntityEquipment equipment = mob.getEquipment();
        for (Removed piece : removed) {
            if (piece.slot() != null && equipment != null) {
                equipment.setItem(piece.slot(), piece.stack());
                setDropChance(equipment, piece.slot(), piece.dropChance());
            } else if (piece.carriedBlock() != null && mob instanceof Enderman enderman) {
                enderman.setCarriedBlock(piece.carriedBlock());
            }
        }
    }

    @Nullable
    private static Material carriedBlockItem(Mob mob) {
        if (!(mob instanceof Enderman enderman)) {
            return null;
        }
        BlockData carried = enderman.getCarriedBlock();
        if (carried == null) {
            return null;
        }
        Material material = carried.getMaterial();
        return material.isAir() || !material.isItem() ? null : material;
    }

    @Nullable
    private static ItemStack slotItem(EntityEquipment equipment, EquipmentSlot slot) {
        try {
            return equipment.getItem(slot);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static float dropChance(EntityEquipment equipment, EquipmentSlot slot) {
        return switch (slot) {
            case HAND -> equipment.getItemInMainHandDropChance();
            case OFF_HAND -> equipment.getItemInOffHandDropChance();
            case HEAD -> equipment.getHelmetDropChance();
            case CHEST -> equipment.getChestplateDropChance();
            case LEGS -> equipment.getLeggingsDropChance();
            case FEET -> equipment.getBootsDropChance();
            default -> 0.0F;
        };
    }

    private static void setDropChance(EntityEquipment equipment, EquipmentSlot slot, float chance) {
        switch (slot) {
            case HAND -> equipment.setItemInMainHandDropChance(chance);
            case OFF_HAND -> equipment.setItemInOffHandDropChance(chance);
            case HEAD -> equipment.setHelmetDropChance(chance);
            case CHEST -> equipment.setChestplateDropChance(chance);
            case LEGS -> equipment.setLeggingsDropChance(chance);
            case FEET -> equipment.setBootsDropChance(chance);
            default -> { }
        }
    }

    private static boolean isEmpty(@Nullable ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    private ToolFingerprint fingerprint(PlayerInventory inventory, ItemStack tool, ItemRef ref) {
        ItemMeta meta = tool.getItemMeta();
        PersistentDataContainer pdc = meta == null ? null : meta.getPersistentDataContainer();
        return new ToolFingerprint(
                inventory.getHeldItemSlot(),
                ref,
                pdc == null ? null : pdc.get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING),
                pdc == null ? null : pdc.get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));
    }

    private void playSound(Entity at, String sound) {
        if (sound.isEmpty() || at.getWorld() == null) {
            return;
        }
        try {
            at.getWorld().playSound(at.getLocation(), sound, 1.0F, 1.2F);
        } catch (RuntimeException e) {
            warnOnce("sound:" + sound, "Mob Disarmer sound '" + sound + "' could not play: " + e.getMessage());
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            diagnostics.accept(message);
        }
    }

    @Nullable
    private static <T> T read(PersistentDataContainer pdc, NamespacedKey key, PersistentDataType<?, T> type) {
        try {
            return pdc.get(key, type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String describe(Entity target) {
        String custom = target.getCustomName();
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        String type = target.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return type.isEmpty() ? "mob" : Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    static String formatDuration(long millis) {
        long seconds = Math.max(0L, (millis + 999L) / 1000L);
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        return remainder == 0L ? minutes + "m" : minutes + "m " + remainder + "s";
    }

    /** {@code dropChance} is the slot's chance before removal, restored if the write is rejected. */
    private record Removed(@Nullable EquipmentSlot slot, ItemStack stack, float dropChance, boolean guaranteed,
                           @Nullable BlockData carriedBlock) {
    }

    private record ToolFingerprint(int heldSlot, ItemRef ref, @Nullable String instanceUuid, @Nullable String signature) {
    }
}
