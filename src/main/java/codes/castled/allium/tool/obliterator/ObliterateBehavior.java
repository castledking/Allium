package codes.castled.allium.tool.obliterator;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.message.LangMessage;
import codes.castled.allium.protection.ProtectionService;
import codes.castled.allium.tool.BukkitParticles;
import codes.castled.allium.tool.definition.ToolSound;
import codes.castled.allium.tool.disarmer.DisarmerCharges;
import codes.castled.allium.tool.disarmer.DisarmerRules;
import codes.castled.allium.tool.disarmer.LegacyDisarmerImport;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * The {@code allium:obliterate_phantoms} behavior: right-click air with the main hand to kill the
 * unnamed phantoms around you. Each definition supplies parameters, messages, sounds, particles
 * and permissions; the steps are fixed here.
 *
 * <p>A use runs in this order:
 * <ol>
 *   <li>identify the held tool (it is privileged, so only a valid signed stack acts);</li>
 *   <li>check the permission, a single stack, broken or recharging, and the click guard;</li>
 *   <li>scan loaded entities inside the radius sphere and ask protection for each target;</li>
 *   <li>with no target left, report and spend nothing;</li>
 *   <li>re-read the held stack (protection listeners run arbitrary code);</li>
 *   <li><b>spend first</b>: write the consumed charge; a rejected write kills nothing;</li>
 *   <li>kill, by default as damage from the player, so damage listeners can still spare a target;</li>
 *   <li>if nothing died, write the old state back: no charge is lost for nothing.</li>
 * </ol>
 * All methods run on the server thread.
 */
public final class ObliterateBehavior {

    /** The Phantom Obliterator's identity; pre-recode tools are imported as this definition. */
    public static final String PHANTOM_OBLITERATOR_ID = "allium:phantom_obliterator";

    static final NamespacedKey LEGACY_ITEM_ID = new NamespacedKey("allium", "custom_item_id");
    static final NamespacedKey LEGACY_NEXO_ID = new NamespacedKey("nexo", "id");
    static final NamespacedKey LEGACY_CHARGES = new NamespacedKey("allium", "phantom_obliterator_charges");
    static final NamespacedKey LEGACY_RECHARGE_AT = new NamespacedKey("allium", "phantom_obliterator_recharge_at");
    static final NamespacedKey LEGACY_BROKEN = new NamespacedKey("allium", "phantom_obliterator_broken");
    static final NamespacedKey LEGACY_USES = new NamespacedKey("allium", "phantom_obliterator_uses");
    /** Indexed by remaining charges, as the pre-recode tool wrote them. */
    static final List<String> LEGACY_VARIANTS = List.of(
            "phantom_obliterator_0-3", "phantom_obliterator_1-3", "phantom_obliterator_2-3", "phantom_obliterator");

    public enum Outcome {
        IGNORED, REFUSED, NOTHING_IN_RANGE, GUARDED, STALE, WRITE_FAILED, REFUNDED, OBLITERATED, BROKE
    }

    @FunctionalInterface
    public interface Notifier {
        void send(Player player, LangMessage message, String... replacements);
    }

    private final ItemService items;
    private final ProtectionService protection;
    private final Notifier notifier;
    private final Consumer<String> diagnostics;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final Map<String, ObliterateTool> tools;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ObliterateBehavior(ItemService items, ProtectionService protection, Notifier notifier, Consumer<String> diagnostics,
                              LongSupplier clock, RandomGenerator random, Collection<ObliterateTool> tools) {
        this.items = Objects.requireNonNull(items, "items");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        Map<String, ObliterateTool> byId = new LinkedHashMap<>();
        for (ObliterateTool tool : tools) {
            if (byId.put(tool.id(), tool) != null) {
                throw new IllegalArgumentException("two obliterate tools share the identity " + tool.id());
            }
        }
        this.tools = Map.copyOf(byId);
    }

    public Collection<ObliterateTool> tools() {
        return tools.values();
    }

    @Nullable
    private ObliterateTool toolFor(@Nullable ItemRef ref) {
        return ref == null ? null : ref.behaviorId().map(tools::get).orElse(null);
    }

    private void send(Player player, ObliterateTool tool, String slot, String... replacements) {
        notifier.send(player, tool.message(slot), replacements);
    }

    /** A refusal or status line, rate-limited per player by the definition's status cooldown. */
    private void status(Player player, ObliterateTool tool, long now, String slot, String... replacements) {
        if (tool.admitStatus(player.getUniqueId(), now)) {
            send(player, tool, slot, replacements);
        }
    }

    public Outcome handleUse(Player player, EquipmentSlot hand) {
        if (hand != EquipmentSlot.HAND) {
            return Outcome.IGNORED;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack held = importLegacyIfPresent(player, inventory.getItemInMainHand());
        if (held == null) {
            return Outcome.IGNORED;
        }
        ItemRef ref = items.identify(held).orElse(null);
        ObliterateTool tool = toolFor(ref);
        if (tool == null) {
            return Outcome.IGNORED;
        }
        ObliterateParameters config = tool.parameters();
        long now = clock.getAsLong();
        if (!player.hasPermission(tool.usePermission())) {
            status(player, tool, now, ObliterateBehaviorType.MSG_NO_PERMISSION);
            return Outcome.REFUSED;
        }
        if (held.getAmount() != 1) {
            status(player, tool, now, ObliterateBehaviorType.MSG_SINGLE_ITEM);
            return Outcome.REFUSED;
        }
        DisarmerRules rules = config.rules();
        int max = rules.maxCharges();
        DisarmerCharges stored = DisarmerCharges.fromState(ref.stateData(), max);
        DisarmerCharges charges = stored.settle(now, max);
        boolean bypass = player.hasPermission(tool.bypassPermission());
        DisarmerCharges.Availability availability = charges.availability(now);
        if (availability.kind() == DisarmerCharges.Availability.Kind.BROKEN) {
            status(player, tool, now, ObliterateBehaviorType.MSG_BROKEN);
            return Outcome.REFUSED;
        }
        if (!bypass && availability.kind() == DisarmerCharges.Availability.Kind.RECHARGING) {
            if (tool.admitStatus(player.getUniqueId(), now)) {
                send(player, tool, ObliterateBehaviorType.MSG_RECHARGING, "{time}", formatDuration(availability.remainingMillis()));
                playSound(player, tool.sound(ObliterateBehaviorType.SOUND_RECHARGING));
            }
            return Outcome.REFUSED;
        }
        if (!tool.clickGuard().tryAcquire(player.getUniqueId())) {
            return Outcome.GUARDED;
        }

        List<LivingEntity> targets = scan(player, config);
        int found = targets.size();
        targets.removeIf(target -> !protection.checkEntityInteraction(player, target, tool.id(), null).allowed());
        if (targets.isEmpty()) {
            if (!charges.equals(stored) && items.updateState(held, charges.toState())) {
                inventory.setItemInMainHand(held);
            }
            status(player, tool, now, found > 0 ? ObliterateBehaviorType.MSG_PROTECTED : ObliterateBehaviorType.MSG_NOTHING_IN_RANGE,
                    "{radius}", formatRadius(config.radius()), "{charges}", Integer.toString(charges.charges()), "{max}", Integer.toString(max));
            return Outcome.NOTHING_IN_RANGE;
        }

        // Protection listeners run arbitrary code: commit only to the exact stack checked above.
        String uuid = pdcString(held, ItemService.KEY_INSTANCE_UUID);
        String signature = pdcString(held, ItemService.KEY_SIGNATURE);
        int slot = inventory.getHeldItemSlot();
        ItemStack live = inventory.getItemInMainHand();
        if (!player.isOnline() || player.isDead() || inventory.getHeldItemSlot() != slot || live == null
                || !Objects.equals(uuid, pdcString(live, ItemService.KEY_INSTANCE_UUID))
                || !Objects.equals(signature, pdcString(live, ItemService.KEY_SIGNATURE))
                || toolFor(items.identify(live).orElse(null)) != tool) {
            return Outcome.STALE;
        }

        DisarmerCharges next;
        boolean broke = false;
        if (bypass) {
            next = charges.recordBypassUse();
        } else {
            DisarmerCharges.Consumption consumption = charges.consume(now, rules, random.nextDouble());
            next = consumption.next();
            broke = consumption.broke();
        }
        if (!items.updateState(live, next.toState())) {
            warnOnce("state-write:" + tool.id(), tool.name() + " state write was rejected for " + player.getName()
                    + "; nothing was obliterated and nothing was spent.");
            send(player, tool, ObliterateBehaviorType.MSG_UPDATE_FAILED);
            return Outcome.WRITE_FAILED;
        }
        inventory.setItemInMainHand(live);

        List<LivingEntity> killed = new ArrayList<>();
        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) {
                continue;
            }
            if (config.attributedKill()) {
                target.damage(target.getHealth() + 1000.0D, player);
            } else {
                target.setHealth(0.0D);
            }
            if (target.isDead() || target.getHealth() <= 0.0D) {
                killed.add(target);
            }
        }
        if (killed.isEmpty()) {
            // Every target was spared by a damage listener: put the charge back onto the same instance UUID.
            ItemStack currentHand = inventory.getItemInMainHand();
            String currentUuid = pdcString(currentHand, ItemService.KEY_INSTANCE_UUID);
            if (uuid == null || !uuid.equals(currentUuid) || inventory.getHeldItemSlot() != slot) {
                diagnostics.accept(tool.name() + " refund failed: item with UUID " + uuid
                        + " has moved or is gone from " + player.getName() + "'s main hand; crediting nothing.");
                status(player, tool, now, ObliterateBehaviorType.MSG_PROTECTED);
                return Outcome.REFUNDED;
            }
            if (!items.updateState(currentHand, charges.toState())) {
                warnOnce("refund:" + tool.id(), tool.name() + " could not refund " + player.getName()
                        + "'s charge after every target was spared.");
            }
            inventory.setItemInMainHand(currentHand);
            status(player, tool, now, ObliterateBehaviorType.MSG_PROTECTED);
            return Outcome.REFUNDED;
        }
        syncAppearance(inventory, live, next, tool);

        for (LivingEntity target : killed) {
            particles(target.getWorld(), target.getLocation().add(0.0D, target.getHeight() * 0.5D, 0.0D),
                    tool.particles(ObliterateBehaviorType.PARTICLES_TARGET), 24, 0.5D, target.getHeight() * 0.3D, 0.02D, tool);
        }
        particles(player.getWorld(), player.getLocation().add(0.0D, 1.0D, 0.0D), tool.particles(ObliterateBehaviorType.PARTICLES_USER),
                80, 1.0D, 0.8D, 0.8D, tool);
        playSound(player, tool.sound(ObliterateBehaviorType.SOUND_OBLITERATE));

        String count = Integer.toString(killed.size());
        if (bypass) {
            send(player, tool, ObliterateBehaviorType.MSG_OBLITERATED_BYPASS, "{count}", count);
        } else {
            send(player, tool, ObliterateBehaviorType.MSG_OBLITERATED, "{count}", count,
                    "{charges}", Integer.toString(next.charges()), "{max}", Integer.toString(max));
            if (broke) {
                send(player, tool, ObliterateBehaviorType.MSG_BROKE);
            } else if (next.charges() == 0) {
                send(player, tool, ObliterateBehaviorType.MSG_LAST_CHARGE, "{time}", formatDuration(rules.rechargeMillis()));
            }
        }
        return broke ? Outcome.BROKE : Outcome.OBLITERATED;
    }

    /** Loaded entities only, nearest first: the world never loads a chunk to answer this. */
    List<LivingEntity> scan(Player player, ObliterateParameters config) {
        Location origin = player.getLocation();
        World world = origin.getWorld();
        if (world == null) {
            return new ArrayList<>();
        }
        double radius = config.radius();
        double radiusSquared = radius * radius;
        List<LivingEntity> found = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(origin, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || entity instanceof Player || !entity.isValid() || entity.isDead()) {
                continue;
            }
            if (!config.entityTypes().contains(typeKey(entity))) {
                continue;
            }
            if (config.requireUnnamed() && entity.getCustomName() != null) {
                continue;
            }
            if (config.excludeNpcs() && entity.hasMetadata("NPC")) {
                continue;
            }
            if (entity.getLocation().distanceSquared(origin) > radiusSquared) {
                continue;
            }
            found.add(living);
        }
        found.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(origin)));
        return found.size() > config.maxTargets() ? new ArrayList<>(found.subList(0, config.maxTargets())) : found;
    }

    /**
     * Converts a pre-recode Phantom Obliterator in the main hand, in place, keeping its enchantments
     * and name. Only a netherite sword whose {@code allium:custom_item_id} and {@code nexo:id} both
     * name the same legacy variant qualifies; model data alone never does. Returns the stack now in
     * hand, or {@code null} when a legacy tool could not be imported (it then does nothing).
     */
    @Nullable
    ItemStack importLegacyIfPresent(Player player, @Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta == null ? null : meta.getPersistentDataContainer();
        if (pdc == null || pdc.has(ItemService.KEY_BEHAVIOR_ID, PersistentDataType.STRING)) {
            return stack;
        }
        String customId = read(pdc, LEGACY_ITEM_ID, PersistentDataType.STRING);
        String nexoId = read(pdc, LEGACY_NEXO_ID, PersistentDataType.STRING);
        if (customId == null || !LEGACY_VARIANTS.contains(customId)) {
            return stack;
        }
        ObliterateTool target = tools.get(PHANTOM_OBLITERATOR_ID);
        if (target == null || !target.parameters().importLegacy()) {
            warnOnce("legacy:off", "Legacy Phantom Obliterator held by " + player.getName() + " was left unchanged: "
                    + (target == null ? "the " + PHANTOM_OBLITERATOR_ID + " definition is not loaded." : "legacy.import is off."));
            return null;
        }
        if (stack.getType() != Material.NETHERITE_SWORD || !customId.equals(nexoId) || stack.getAmount() != 1) {
            warnOnce("legacy:mismatch:" + player.getName(), "Legacy Phantom Obliterator held by " + player.getName()
                    + " was not imported: it needs to be a single netherite sword whose allium:custom_item_id and nexo:id"
                    + " name the same variant.");
            return null;
        }
        DisarmerCharges imported = LegacyDisarmerImport.importCharges(
                read(pdc, LEGACY_CHARGES, PersistentDataType.INTEGER),
                read(pdc, LEGACY_RECHARGE_AT, PersistentDataType.LONG),
                Boolean.TRUE.equals(read(pdc, LEGACY_BROKEN, PersistentDataType.BOOLEAN)),
                read(pdc, LEGACY_USES, PersistentDataType.LONG),
                target.parameters().rules().maxCharges());
        // A provider that recognizes the stack (Nexo, by its nexo:id) stays its appearance, so the
        // signature is made for what identify() will report.
        ProviderKey nativeAppearance = items.identify(stack).map(ItemRef::appearance).filter(key -> !key.isVanilla()).orElse(null);
        ProviderKey appearance = nativeAppearance != null ? nativeAppearance : target.definition().appearance();
        if (!items.adopt(stack, appearance, PHANTOM_OBLITERATOR_ID, imported.toState())) {
            return null;
        }
        player.getInventory().setItemInMainHand(stack);
        diagnostics.accept("Imported legacy Phantom Obliterator for " + player.getName() + " as " + appearance
                + " with " + imported.charges() + " charge(s)" + (imported.broken() ? " (broken)" : "")
                + (imported.rechargeAt() > 0L ? ", recharging until " + imported.rechargeAt() : "") + ", uses " + imported.uses() + ".");
        return player.getInventory().getItemInMainHand();
    }

    private void syncAppearance(PlayerInventory inventory, ItemStack stack, DisarmerCharges state, ObliterateTool tool) {
        ProviderKey current = items.identify(stack).map(ItemRef::appearance).orElse(null);
        if (current == null) {
            return;
        }
        Optional<ProviderKey> variant = tool.parameters().variantFor(current, state);
        if (variant.isEmpty() || variant.get().equals(current)) {
            return;
        }
        ResolutionResult swapped = items.swapOrRefreshAppearance(stack, variant.get());
        if (swapped.isResolved()) {
            inventory.setItemInMainHand(swapped.itemStack().orElseThrow());
        } else {
            warnOnce("variant:" + variant.get(), tool.name() + " charge variant " + variant.get()
                    + " is unavailable; keeping " + current + " (" + swapped.diagnosticMessage() + ")");
        }
    }

    public void forget(UUID playerId) {
        tools.values().forEach(tool -> tool.forget(playerId));
    }

    private void particles(World world, Location at, List<String> names, int count, double spread, double spreadY, double speed,
                           ObliterateTool tool) {
        if (world == null) {
            return;
        }
        for (String name : names) {
            Optional<Particle> particle = BukkitParticles.resolve(name);
            if (particle.isEmpty()) {
                warnOnce("particle:" + name, tool.name() + " particle '" + name + "' is not available on this server.");
                continue;
            }
            try {
                world.spawnParticle(particle.get(), at, count, spread, spreadY, spread, speed);
            } catch (RuntimeException e) {
                warnOnce("particle:" + name, tool.name() + " particle '" + name + "' could not be shown: " + e.getMessage());
            }
        }
    }

    private void playSound(Player player, ToolSound sound) {
        if (sound.isNone()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), sound.sound(), sound.volume(), sound.pitch());
        } catch (RuntimeException e) {
            warnOnce("sound:" + sound.sound(), "Tool sound '" + sound.sound() + "' could not play: " + e.getMessage());
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            diagnostics.accept(message);
        }
    }

    private static String typeKey(Entity entity) {
        try {
            return entity.getType().getKey().toString();
        } catch (RuntimeException e) {
            return "minecraft:" + entity.getType().name().toLowerCase(Locale.ROOT);
        }
    }

    @Nullable
    private static String pdcString(@Nullable ItemStack stack, NamespacedKey key) {
        ItemMeta meta = stack == null ? null : stack.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    @Nullable
    private static <T> T read(PersistentDataContainer pdc, NamespacedKey key, PersistentDataType<?, T> type) {
        try {
            return pdc.get(key, type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String formatRadius(double radius) {
        return radius == Math.rint(radius) ? Long.toString((long) radius) : Double.toString(radius);
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
}
