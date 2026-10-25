package codes.castled.allium.listeners.items;

import java.util.Objects;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.World;
import org.bukkit.potion.PotionType;

import codes.castled.allium.items.CustomItemRegistry;
import codes.castled.allium.items.impl.BongItem;
import codes.castled.allium.util.SchedulerAdapter;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Drives the bong state machine that the item config cannot express.
 *
 * <p>The config half of the expansion is pure data: the bongs, the dirty water
 * variants, the drinking components and the effect commands all live in Nexo.
 * What is left here are the four things a recipe cannot do:
 *
 * <ul>
 *   <li><b>drinking</b> — a loaded bong is a real water bottle, so vanilla
 *       consumes it; a tick later this listener swaps the spent stack for the
 *       next state (dirtier water, one hit fewer, and no bowl piece at all once
 *       the third hit is taken). The smoke puff a few seconds later lives here
 *       because Nexo commands have no delay syntax.</li>
 *   <li><b>scrubbing</b> — dropping a sponge onto a dirty bong, in an inventory
 *       GUI, cleans the water and turns the sponge into a wet sponge.</li>
 *   <li><b>filling</b> — pouring a water bottle or bucket onto an empty bong.</li>
 *   <li><b>unloading</b> — taking the bowl piece off a loaded bong and handing
 *       it back, from the drop key, a sneak hand-swap, or a shift-click.</li>
 * </ul>
 *
 * <p>Creative mode is left alone throughout: creative inventory clicks are
 * client-authoritative and fight any cursor changes made on the server.
 */
public class BongListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Ticks after the last hit before the exhale, so the puff trails the drink. */
    private static final long PUFF_DELAY_TICKS = 60L;
    /** Ticks to wait after {@link PlayerItemConsumeEvent} before writing the next state. */
    private static final long CONSUME_SETTLE_TICKS = 1L;

    private final Plugin plugin;
    private final CustomItemRegistry registry;

    public BongListener(final Plugin plugin, final CustomItemRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    // ==================== drinking ====================

    /**
     * A loaded bong is a potion carrying a consumable component, so vanilla
     * drinks and consumes it. The stack in hand is therefore already gone by the
     * time this state has to be written back, which is why the replacement is a
     * tick late — and the leftover glass bottle a potion always leaves behind
     * has to be swept up before the slot can be written to.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(final PlayerItemConsumeEvent event) {
        final BongItem bong = bong();
        if (bong == null) {
            return;
        }
        final String consumedId = BongItem.nexoIdOf(event.getItem());
        final BongItem.State state = BongItem.State.parse(consumedId);
        if (state == null || !state.loaded()) {
            return;
        }

        final Player player = event.getPlayer();
        // Resolve the hand now: by the time the write-back runs, survival has
        // already swapped the bong for its use_remainder glass bottle, and
        // creative has not consumed it at all.
        final EquipmentSlot slot = handOf(player, consumedId);
        if (slot == null) {
            return;
        }
        final BongItem.State next = state.afterHit();

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.7f, 0.9f);
        puff(player.getLocation().add(0, 1.4, 0), 8);
        player.sendActionBar(next.loaded()
                ? MINI.deserialize("<gray>Hits left: <green>" + next.hits()
                        + "<gray>/" + BongItem.MAX_HITS)
                : MINI.deserialize("<gray>The bowl piece is empty."));

        SchedulerAdapter.runAtEntityLater(plugin, player, () ->
                replaceHeld(player, slot, consumedId, next.id()), CONSUME_SETTLE_TICKS);
        SchedulerAdapter.runAtEntityLater(plugin, player, () -> {
            exhale(player.getLocation().add(0, 1.5, 0));
            player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 0.6f, 0.8f);
        }, PUFF_DELAY_TICKS);
    }

    /**
     * Puts {@code nextId} in the hand that drank {@code previousId}.
     *
     * <p>Three things can be in that slot a tick later: nothing at all (survival
     * consumed the bong), the bong still sitting there (creative never consumes
     * anything), or the empty container its {@code use_remainder} left behind. All
     * three mean the same thing to a player — the bong was just hit — so all
     * three are replaced by the next state. The leftover container is kept and
     * handed over rather than deleted. Only if something entirely unrelated has
     * taken the slot is the next bong put in the inventory instead, so no item is
     * ever destroyed.
     */
    private void replaceHeld(final Player player, final EquipmentSlot slot, final String previousId,
                             final String nextId) {
        final BongItem bong = bong();
        if (bong == null) {
            return;
        }
        final ItemStack next = bong.create(nextId).orElse(null);
        if (next == null) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        final ItemStack current = inventory.getItem(slot);
        final boolean remainder = isRemainder(current);
        if (current == null || current.getType() == Material.AIR || remainder
                || Objects.equals(BongItem.nexoIdOf(current), previousId)) {
            if (remainder) {
                BongItem.give(player, current);
            }
            inventory.setItem(slot, next);
        } else {
            BongItem.give(player, next);
        }
    }

    // ==================== unloading ====================

    /**
     * The drop key is the vanilla way to get something off your hands, and the
     * lore says shift-click; this is that. Nothing is dropped — the bong stays,
     * minus its bowl piece, and the bowl piece comes back to the player.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        final BongItem bong = bong();
        if (bong == null) {
            return;
        }
        final String id = BongItem.nexoIdOf(event.getItemDrop().getItemStack());
        final BongItem.State state = BongItem.State.parse(id);
        if (state == null || !state.hasBowl()) {
            return;
        }
        final Player player = event.getPlayer();
        final EquipmentSlot slot = handOf(player, id);
        if (slot == null) {
            return; // dropped from somewhere else: let it fall
        }
        // Ask for the bare bong first: a burnt-in piece means the drop is not
        // intercepted at all, so a locked bong can still be thrown on the ground
        // or put in a chest like anything else.
        final ItemStack bare = takeBowlPiece(player, bong, state);
        if (bare == null) {
            return;
        }
        event.setCancelled(true);
        player.getInventory().setItem(slot, bare);
    }

    /** Sneak + hand swap, for players who read the lore as "shift-click". */
    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(final PlayerSwapHandItemsEvent event) {
        final BongItem bong = bong();
        if (bong == null || !event.getPlayer().isSneaking()) {
            return;
        }
        final PlayerInventory inventory = event.getPlayer().getInventory();
        for (final EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            final BongItem.State state = BongItem.State.parse(BongItem.nexoIdOf(inventory.getItem(slot)));
            if (state != null && state.hasBowl()) {
                final ItemStack bare = takeBowlPiece(event.getPlayer(), bong, state);
                if (bare == null) {
                    return; // burnt in: the swap happens as vanilla would do it
                }
                event.setCancelled(true);
                event.getPlayer().getInventory().setItem(slot, bare);
                return;
            }
        }
    }

    /**
     * Strips the bowl piece off a loaded bong and hands the piece back — full if
     * nothing has been smoked from it, empty if it has spent its weed. The caller
     * decides where the now-bare bong goes.
     *
     * <p>A piece that has been smoked from is burnt in and stays put; the player
     * is told why and the click, drop or hand-swap carries on as vanilla would
     * have done it, so a locked bong can still be put away.
     *
     * @return the bare bong, or null when there is nothing to take off
     */
    private ItemStack takeBowlPiece(final Player player, final BongItem bong,
                                    final BongItem.State state) {
        if (!state.removable()) {
            player.sendActionBar(MINI.deserialize(
                    "<gray>The bowl piece is burnt in — it goes out with the last hit."));
            return null;
        }
        final ItemStack bare = bong.create(state.emptied().id()).orElse(null);
        if (bare == null) {
            return null;
        }
        // A spent piece comes back empty, so it needs packing again; an untouched
        // one comes back exactly as it went on.
        final String pieceId = state.hits() > 0 ? BongItem.BOWL_PIECE_ID : BongItem.EMPTY_PIECE_ID;
        BongItem.give(player, bong.create(pieceId).orElse(null));
        player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 0.7f, 1.4f);
        player.sendActionBar(MINI.deserialize("<yellow>You take the bowl piece off."));
        return bare;
    }

    // ==================== inventory clicks ====================

    /**
     * The sponge and the water are applied by clicking one onto the other,
     * either way round, in any inventory. Every other click is left to vanilla.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        final BongItem bong = bong();
        if (bong == null) {
            return;
        }

        final ClickType click = event.getClick();
        final ItemStack cursor = event.getCursor();
        final ItemStack inSlot = event.getCurrentItem();

        // Shift-click: in the player's own inventory that means "take the bowl
        // piece off", which is what the lore promises.
        if (event.getClickedInventory() instanceof PlayerInventory
                && (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT)) {
            final BongItem.State state = BongItem.State.parse(BongItem.nexoIdOf(inSlot));
            if (state == null) {
                return;
            }
            if (!state.hasBowl()) {
                player.sendActionBar(MINI.deserialize("<gray>There is no bowl piece on this bong."));
                return;
            }
            // takeBowlPiece refuses a burnt-in piece and says why; a refusal means
            // the click is left to vanilla, so the bong still moves.
            final ItemStack bare = takeBowlPiece(player, bong, state);
            if (bare == null) {
                return;
            }
            event.setCurrentItem(bare);
            event.setCancelled(true);
            return;
        }
        if (click != ClickType.LEFT && click != ClickType.RIGHT && !click.isKeyboardClick()) {
            return;
        }

        // Sponge on the cursor, bong in the slot.
        if (isSponge(cursor)) {
            final BongItem.State state = BongItem.State.parse(BongItem.nexoIdOf(inSlot));
            if (state != null && scrub(player, bong, state)) {
                event.setCurrentItem(bong.create(state.scrubbed().id()).orElse(null));
                player.setItemOnCursor(wetSponge(cursor));
                event.setCancelled(true);
                return;
            }
        }
        // Bong on the cursor, sponge in the slot.
        if (isSponge(inSlot)) {
            final BongItem.State state = BongItem.State.parse(BongItem.nexoIdOf(cursor));
            if (state != null && scrub(player, bong, state)) {
                player.setItemOnCursor(bong.create(state.scrubbed().id()).orElse(null));
                event.setCurrentItem(wetSponge(inSlot));
                event.setCancelled(true);
                return;
            }
        }
        // A full bowl piece dropped onto a bong loads it, exactly as the
        // crafting recipe would, and the piece is used up. A bong whose piece is
        // already spent takes the new one just as happily.
        if (isBowlPiece(cursor)) {
            final BongItem.State state = BongItem.State.parse(BongItem.nexoIdOf(inSlot));
            if (state != null && state.loadable() && load(player, bong, state)) {
                player.setItemOnCursor(null);
                event.setCurrentItem(bong.create(state.filled().id()).orElse(null));
                event.setCancelled(true);
                return;
            }
        }
        // Same thing the other way round: bong on the cursor, piece in the slot.
        if (isBowlPiece(inSlot)) {
            final BongItem.State state = BongItem.State.parse(BongItem.nexoIdOf(cursor));
            if (state != null && state.loadable() && load(player, bong, state)) {
                event.setCurrentItem(null);
                player.setItemOnCursor(bong.create(state.filled().id()).orElse(null));
                event.setCancelled(true);
                return;
            }
        }
        // Water on the cursor, empty bong in the slot.
        if (isWater(cursor) && BongItem.EMPTY_ID.equals(BongItem.nexoIdOf(inSlot))
                && fill(player, bong)) {
            event.setCurrentItem(bong.create(BongItem.ITEM_ID).orElse(null));
            player.setItemOnCursor(spentContainer(cursor));
            event.setCancelled(true);
            return;
        }
        // Empty bong on the cursor, water in the slot.
        if (isWater(inSlot) && BongItem.EMPTY_ID.equals(BongItem.nexoIdOf(cursor))
                && fill(player, bong)) {
            player.setItemOnCursor(bong.create(BongItem.ITEM_ID).orElse(null));
            event.setCurrentItem(spentContainer(inSlot));
            event.setCancelled(true);
        }
    }

    /**
     * @return false when the loaded bong cannot be built, leaving the click alone.
     */
    private boolean load(final Player player, final BongItem bong, final BongItem.State state) {
        if (bong.create(state.filled().id()).isEmpty()) {
            return false;
        }
        player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, 0.7f, 1.6f);
        player.sendActionBar(MINI.deserialize("<green>Bowl piece on. <gray>"
                + BongItem.MAX_HITS + " hits left."));
        return true;
    }

    /**
     * @return false when the bong has nothing to scrub, so a clean bong is left
     *         to swap like any other item instead of wasting a sponge.
     */
    private boolean scrub(final Player player, final BongItem bong, final BongItem.State state) {
        if (!state.dirty() || bong.create(state.scrubbed().id()).isEmpty()) {
            return false;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_WOOL_PLACE, 0.8f, 1.2f);
        player.getWorld().spawnParticle(Particle.SPLASH,
                player.getLocation().add(0, 1.0, 0), 12, 0.3, 0.3, 0.3, 0.0);
        player.sendActionBar(MINI.deserialize("<green>The sponge scrubs the bong clean."));
        return true;
    }

    /** @return false when the filled bong cannot be built, leaving the click alone. */
    private boolean fill(final Player player, final BongItem bong) {
        if (bong.create(BongItem.ITEM_ID).isEmpty()) {
            return false;
        }
        player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, 0.8f, 1.2f);
        player.getWorld().spawnParticle(Particle.SPLASH,
                player.getLocation().add(0, 1.0, 0), 20, 0.3, 0.4, 0.3, 0.0);
        player.sendActionBar(MINI.deserialize("<blue>You fill the bong with water."));
        return true;
    }

    // ==================== pouring ====================

    /**
     * Right-clicking with water in one hand and an empty bong in the other fills
     * it — a bucket, or a plain water potion, since water bottles no longer
     * exist. The container comes back empty (a bucket stays a bucket, a potion
     * becomes a glass bottle), because the bong keeps that water until there is
     * a way to empty it, which there is not yet.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        final Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final BongItem bong = bong();
        if (bong == null) {
            return;
        }
        final Player player = event.getPlayer();
        final PlayerInventory inventory = player.getInventory();
        final ItemStack main = inventory.getItemInMainHand();
        final ItemStack off = inventory.getItemInOffHand();

        final EquipmentSlot bongSlot;
        final EquipmentSlot waterSlot;
        if (isWater(main) && BongItem.EMPTY_ID.equals(BongItem.nexoIdOf(off))) {
            bongSlot = EquipmentSlot.OFF_HAND;
            waterSlot = EquipmentSlot.HAND;
        } else if (isWater(off) && BongItem.EMPTY_ID.equals(BongItem.nexoIdOf(main))) {
            bongSlot = EquipmentSlot.HAND;
            waterSlot = EquipmentSlot.OFF_HAND;
        } else {
            return;
        }

        final ItemStack filled = fill(player, bong)
                ? bong.create(BongItem.ITEM_ID).orElse(null)
                : null;
        if (filled == null) {
            return;
        }
        inventory.setItem(bongSlot, filled);
        final ItemStack water = inventory.getItem(waterSlot);
        if (water != null && water.getAmount() > 1) {
            final ItemStack left = water.clone();
            left.setAmount(water.getAmount() - 1);
            inventory.setItem(waterSlot, left);
        } else {
            inventory.setItem(waterSlot, spentContainer(water));
        }
        event.setCancelled(true);
    }

    // ==================== internals ====================

    private BongItem bong() {
        return registry.getItem(BongItem.ITEM_ID) instanceof BongItem bong ? bong : null;
    }

    /** The hand holding the bong with that id, or null when neither hand has it. */
    private static EquipmentSlot handOf(final Player player, final String nexoId) {
        final PlayerInventory inventory = player.getInventory();
        if (Objects.equals(BongItem.nexoIdOf(inventory.getItemInMainHand()), nexoId)) {
            return EquipmentSlot.HAND;
        }
        if (Objects.equals(BongItem.nexoIdOf(inventory.getItemInOffHand()), nexoId)) {
            return EquipmentSlot.OFF_HAND;
        }
        return null;
    }

    private static boolean isSponge(final ItemStack item) {
        return item != null && item.getType() == Material.SPONGE;
    }

    /** A packed bowl piece, the one that loads a bong. */
    private static boolean isBowlPiece(final ItemStack item) {
        return item != null && BongItem.BOWL_PIECE_ID.equals(BongItem.nexoIdOf(item));
    }

    /**
     * Water bottles were removed from Minecraft in 26.x, so water travels either
     * in a bucket or as a plain water potion — a potion with no effects and no
     * real contents. Anything carrying a Nexo id is one of our own bongs, not a
     * water source, even though the loaded ones are potions too.
     */
    private static boolean isWater(final ItemStack item) {
        if (item == null || BongItem.nexoIdOf(item) != null) {
            return false;
        }
        return item.getType() == Material.WATER_BUCKET
                || (item.getType() == Material.POTION && isWaterPotion(item));
    }

    private static boolean isWaterPotion(final ItemStack item) {
        if (!(item.getItemMeta() instanceof PotionMeta meta)) {
            return false;
        }
        if (!meta.getAllEffects().isEmpty()) {
            return false; // an actual potion, not water in a bottle
        }
        return !meta.hasBasePotionType() || meta.getBasePotionType() == PotionType.WATER;
    }

    /**
     * What a drank bong leaves behind: the vanilla {@code use_remainder} of the
     * base material — a milk bucket leaves an empty bucket, a potion a glass
     * bottle. Only a plain one counts, so a player who happens to be holding a
     * decorated bucket in that slot is never mistaken for the bong.
     */
    private static boolean isRemainder(final ItemStack item) {
        if (item == null || item.hasItemMeta()) {
            return false;
        }
        return item.getType() == Material.GLASS_BOTTLE || item.getType() == Material.BUCKET;
    }

    /** A used sponge becomes a wet sponge; the stack size is kept as it was. */
    private static ItemStack wetSponge(final ItemStack sponge) {
        final ItemStack wet = sponge.clone();
        wet.setType(Material.WET_SPONGE);
        return wet;
    }

    /** What is left of a water container once it has been poured into a bong. */
    private static ItemStack spentContainer(final ItemStack water) {
        final ItemStack spent = water == null ? new ItemStack(Material.BUCKET) : water.clone();
        spent.setType(spent.getType() == Material.WATER_BUCKET ? Material.BUCKET : Material.GLASS_BOTTLE);
        spent.setAmount(Math.max(1, spent.getAmount()));
        return spent;
    }

    /**
     * The exhale, a few seconds after the last hit: a thick cloud of smoke with a
     * slower layer of big puffs drifting through it, spread over three ticks so
     * it hangs in the air instead of vanishing in one frame. Scheduled against
     * the location rather than the player, so walking away does not cut it off
     * and unloading the chunk drops it instead of spawning in a dead one.
     */
    private void exhale(final Location location) {
        final World world = location.getWorld();
        world.spawnParticle(Particle.SMOKE, location, 45, 0.45, 0.35, 0.45, 0.015);
        world.spawnParticle(Particle.LARGE_SMOKE, location, 12, 0.5, 0.4, 0.5, 0.01);
        drift(plugin, world, location.clone().add(0, 0.35, 0), 8L, 28, 6);
        drift(plugin, world, location.clone().add(0, 0.6, 0), 16L, 16, 0);
    }

    /** One more layer of the cloud, a few ticks later and a little higher. */
    private static void drift(final Plugin plugin, final World world, final Location at,
                              final long delayTicks, final int smoke, final int bigSmoke) {
        codes.castled.allium.scheduler.SchedulerAdapter.runAtLocationLater(plugin, at, () -> {
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                return;
            }
            world.spawnParticle(Particle.SMOKE, at, smoke, 0.6, 0.3, 0.6, 0.01);
            if (bigSmoke > 0) {
                world.spawnParticle(Particle.LARGE_SMOKE, at, bigSmoke, 0.7, 0.35, 0.7, 0.005);
            }
        }, delayTicks);
    }

    private static void puff(final Location location, final int count) {
        location.getWorld().spawnParticle(Particle.SMOKE, location, count, 0.35, 0.25, 0.35, 0.02);
    }
}