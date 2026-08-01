package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.HarvestBranding;
import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.crop.def.HarvestSource;
import codes.castled.allium.harvest.crop.def.InteractionSettings;
import codes.castled.allium.harvest.event.CropRemoveEvent;
import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.item.ItemResolverChain;
import codes.castled.allium.harvest.soil.SoilService;
import codes.castled.allium.harvest.sprinkler.SprinklerService;
import codes.castled.allium.harvest.storage.CropStorage;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.Durations;
import codes.castled.allium.harvest.visual.VisualTags;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Player and world interaction for crops. Crops render as display entities
 * with no physical block, so interaction is detected through the underlying
 * anchor/soil block: right-click the soil (or into a crop cell) to plant,
 * fertilize or harvest; break the soil (or a footprint cell) to remove.
 */
public final class CropListeners implements Listener {

    private final CropRegistry registry;
    private final CropInstanceService instances;
    private final CropPlacementService placement;
    private final CropHarvestService harvests;
    private final CropStorage storage;
    private final ItemResolverChain items;
    private final SoilService soils;
    private final SprinklerService sprinklers;

    public CropListeners(
        CropRegistry registry,
        CropInstanceService instances,
        CropPlacementService placement,
        CropHarvestService harvests,
        CropStorage storage,
        ItemResolverChain items,
        SoilService soils,
        SprinklerService sprinklers
    ) {
        this.registry = registry;
        this.instances = instances;
        this.placement = placement;
        this.harvests = harvests;
        this.storage = storage;
        this.items = items;
        this.soils = soils;
        this.sprinklers = sprinklers;
    }

    // ==================== interaction ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return; // never double-fire from off hand
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        Player player = event.getPlayer();
        BlockPositionKey clickedKey = keyOf(clicked);
        BlockPositionKey aboveKey = clickedKey.offset(0, 1, 0);

        // A crop occupies either the clicked cell (clicking "into" the plant)
        // or the cell above the clicked soil block.
        CropInstance crop = instances.at(clickedKey)
            .or(() -> instances.at(aboveKey))
            .orElse(null);
        ItemStack held = event.getItem();

        if (crop != null) {
            if (tryFertilize(player, crop, held)) {
                event.setCancelled(true);
                return;
            }
            if (tryRightClickHarvest(player, crop)) {
                event.setCancelled(true);
            }
            return;
        }

        // No crop here — either work a fertilizer into the soil, or plant.
        if (held == null || held.getType().isAir()) return;
        Optional<ItemRef> heldRef = items.identify(held);
        if (heldRef.isEmpty()) return;
        if (tryFertilizeSoil(player, clicked, heldRef.get(), held)) {
            event.setCancelled(true);
            return;
        }
        registry.cropBySeed(heldRef.get()).ifPresent(definition -> {
            if (!player.hasPermission(HarvestBranding.PERMISSION_ROOT + ".crop.plant")) {
                return;
            }
            CropPlacementService.PlantResult result =
                placement.plant(player, definition, clicked, held, null);
            if (result.success()) {
                event.setCancelled(true);
            } else if (result.denyReason() != null) {
                player.sendActionBar(MiniMessage.miniMessage()
                    .deserialize("<red>" + result.denyReason() + "</red>"));
                event.setCancelled(true);
            }
        });
    }

    /**
     * Works a fertilizer into a bare soil block.
     *
     * <p>This is the only way variation and soil-retain effects can be used:
     * the growth path is rolled once when the seed goes in, so a variation
     * fertilizer has to already be in the ground by then.
     */
    private boolean tryFertilizeSoil(Player player, Block soil, ItemRef heldRef, ItemStack held) {
        return registry.fertilizerByItem(heldRef).map(fertilizer -> {
            if (!fertilizer.appliesToSoil()) {
                return false;
            }
            if (!player.hasPermission(HarvestBranding.PERMISSION_ROOT + ".crop.plant")) {
                return false;
            }
            // Only meaningful on ground a crop could actually be planted on.
            if (!soil.getType().isSolid()) {
                return false;
            }
            long now = System.currentTimeMillis();
            BlockPositionKey soilKey = keyOf(soil);
            soils.applyFertilizer(soilKey, fertilizer.id(), fertilizer.soilRetainMillis(), now);
            if (player.getGameMode() != GameMode.CREATIVE) {
                held.setAmount(held.getAmount() - 1);
            }
            String message = fertilizer.soilRetainMillis() > 0L
                ? "<green>Soil restored — <white>"
                    + Durations.format(soils.remainingMillis(soilKey, now)) + "</white> of life left.</green>"
                : "<green>Worked into the soil — plant here to use it.</green>";
            player.sendActionBar(MiniMessage.miniMessage().deserialize(message));
            player.getWorld().playSound(player.getLocation(), "item.bone_meal.use", 1.0F, 1.0F);
            return true;
        }).orElse(false);
    }

    private boolean tryFertilize(Player player, CropInstance crop, ItemStack held) {
        if (held == null || held.getType().isAir()) return false;
        Optional<ItemRef> heldRef = items.identify(held);
        if (heldRef.isEmpty()) return false;
        return registry.fertilizerByItem(heldRef.get()).map(fertilizer -> {
            if (crop.state() != CropState.GROWING) {
                return false;
            }
            if (fertilizer.id().equals(crop.fertilizerId())) {
                return false;
            }
            if (!fertilizer.appliesToCrop()) {
                player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>This must be worked into the soil before planting.</red>"));
                return false;
            }
            crop.setFertilizerId(fertilizer.id());
            // Speed is cached on the instance, so it has to be recomputed the
            // moment one of its inputs changes.
            crop.setSpeedMultiplier(GrowthSpeed.combine(
                fertilizer.growthSpeedMultiplier(),
                sprinklers.speedMultiplierAt(crop.position())));
            if (player.getGameMode() != GameMode.CREATIVE) {
                held.setAmount(held.getAmount() - 1);
            }
            storage.saveNow(crop);
            player.sendActionBar(MiniMessage.miniMessage()
                .deserialize("<green>Fertilizer applied.</green>"));
            player.getWorld().playSound(player.getLocation(), "item.bone_meal.use", 1.0F, 1.0F);
            return true;
        }).orElse(false);
    }

    /**
     * The shared right-click-on-a-crop behaviour, whether the click landed on
     * the plant's hitbox or on the block under it.
     *
     * @return whether the click was consumed and the event should be cancelled
     */
    private boolean tryRightClickHarvest(Player player, CropInstance crop) {
        InteractionSettings settings = interactionFor(crop);
        if (crop.state() != CropState.MATURE) {
            sendGrowthProgress(player, crop, settings);
            // A click that produced no message still belongs to the crop, so it
            // must not fall through to placing a block inside the plant.
            return true;
        }
        if (!settings.rightClickHarvest()) {
            // Right-click is reserved for inspection on this crop: say the crop
            // is ready rather than leaving the player clicking at nothing.
            if (settings.progressDisplay() != InteractionSettings.ProgressDisplay.OFF) {
                player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<green>Ready — break it to harvest.</green>"));
            }
            return true;
        }
        if (!player.hasPermission(HarvestBranding.PERMISSION_ROOT + ".crop.harvest")) {
            return false;
        }
        CropHarvestService.HarvestResult result =
            harvests.harvest(player, crop, HarvestSource.RIGHT_CLICK);
        if (!result.success() && result.denyReason() != null) {
            player.sendActionBar(MiniMessage.miniMessage()
                .deserialize("<red>" + result.denyReason() + "</red>"));
        }
        return true;
    }

    /**
     * Removes a crop the player destroyed, harvesting it first when it was
     * mature and the crop allows break-harvesting.
     *
     * <p>An immature crop is never harvested this way — there is nothing to
     * take yet, so it falls through to {@code break-drops.immature}.
     */
    private void breakCrop(Player player, CropInstance crop) {
        if (crop.state() == CropState.MATURE
            && interactionFor(crop).breakHarvest()
            && player.hasPermission(HarvestBranding.PERMISSION_ROOT + ".crop.harvest")) {
            CropHarvestService.HarvestResult result =
                harvests.harvest(player, crop, HarvestSource.BREAK);
            if (result.success()) {
                return;
            }
        }
        harvests.removeCrop(crop, CropRemoveEvent.Reason.BROKEN);
    }

    private InteractionSettings interactionFor(CropInstance crop) {
        return registry.crop(crop.cropId())
            .map(CropDefinition::interaction)
            .orElse(InteractionSettings.DEFAULT);
    }

    /**
     * Tells the player how a growing crop is doing.
     *
     * <p>{@code VAGUE} reports a share of the whole path rather than the current
     * stage, because "nearly ready" should mean nearly harvestable — a player
     * has no idea how many stages are left, so per-stage progress would read as
     * a promise the crop then breaks.
     */
    private void sendGrowthProgress(Player player, CropInstance crop, InteractionSettings settings) {
        switch (settings.progressDisplay()) {
            case OFF -> { /* the crop keeps its own counsel */ }
            case TIME -> {
                long remaining = Math.max(0L, crop.nextGrowthAt() - System.currentTimeMillis());
                player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<yellow>Growing — next stage in " + Durations.format(remaining) + "</yellow>"));
            }
            case HINT -> player.sendActionBar(MiniMessage.miniMessage()
                .deserialize(progressHint(crop)));
        }
    }

    private String progressHint(CropInstance crop) {
        double progress = registry.crop(crop.cropId())
            .flatMap(definition -> definition.path(crop.pathId()))
            .map(path -> {
                int matureStage = Math.max(1, path.matureStage());
                long stageMs = Math.max(1L, crop.nextGrowthAt() - crop.stageStartedAt());
                double withinStage = Math.max(0.0D, Math.min(1.0D,
                    (System.currentTimeMillis() - crop.stageStartedAt()) / (double) stageMs));
                return Math.min(1.0D, (crop.stage() + withinStage) / matureStage);
            })
            .orElse(0.0D);

        if (progress < 0.25D) return "<gray>A young seedling.</gray>";
        if (progress < 0.50D) return "<yellow>Coming along.</yellow>";
        if (progress < 0.75D) return "<yellow>Growing well.</yellow>";
        if (progress < 0.95D) return "<green>Nearly ready.</green>";
        return "<green>Almost ready to harvest.</green>";
    }

    // ==================== protection of crop cells ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockPositionKey key = keyOf(block);
        // Breaking a footprint cell removes the crop; breaking the soil below
        // the anchor removes it too.
        CropInstance crop = instances.at(key)
            .or(() -> instances.at(key.offset(0, 1, 0)))
            .orElse(null);
        if (crop == null) return;
        breakCrop(event.getPlayer(), crop);
    }

    /**
     * Right-clicking a crop's hitbox: same behaviour as clicking the block it
     * stands on, so a crop can be fertilized and harvested by clicking the
     * plant itself rather than hunting for the soil under it.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        CropInstance crop = cropOfEntity(event.getRightClicked());
        if (crop == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();

        if (tryFertilize(player, crop, held)) {
            return;
        }
        tryRightClickHarvest(player, crop);
    }

    /**
     * Punching a crop's hitbox destroys it, returning its configured break
     * drops. This is what removes the need for a real block to stand in for
     * the plant just so it can be broken.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        CropInstance crop = cropOfEntity(event.getEntity());
        if (crop == null) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission(HarvestBranding.PERMISSION_ROOT + ".crop.harvest")) {
            return;
        }
        breakCrop(player, crop);
        player.getWorld().playSound(player.getLocation(), "block.crop.break", 1.0F, 1.0F);
    }

    /** Resolves one of our tagged hitboxes back to its live crop, if any. */
    private CropInstance cropOfEntity(org.bukkit.entity.Entity entity) {
        if (!VisualTags.isManaged(entity)
            || VisualTags.kindOf(entity).orElse(null) != VisualTags.Kind.CROP) {
            return null;
        }
        return VisualTags.cropInstanceOf(entity)
            .flatMap(instances::byInstanceId)
            .filter(crop -> crop.state() != CropState.REMOVED)
            .orElse(null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (instances.isOccupied(keyOf(event.getBlock()))) {
            event.setCancelled(true);
        }
    }

    /**
     * A piston shears any crop it reaches, the way vanilla pistons treat wheat
     * or a flower.
     *
     * <p>The extending arm itself counts: pushing into an empty cell moves no
     * blocks at all, so {@link BlockPistonExtendEvent#getBlocks()} is empty and
     * the head is the only thing that enters the crop's space.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        breakCropsAlong(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        breakCropsAlong(null, event.getBlocks(), event.getDirection());
    }

    /**
     * Destroys every crop standing in a piston's way.
     *
     * <p>Both ends of each moving block matter: a crop is broken if a block
     * lands on it, and also if the block it was anchored to is the one that
     * moved out from under it.
     *
     * @param piston    the piston body, whose head advances one cell on extend;
     *                  null when retracting, since a retracting head only frees
     *                  space
     * @param moved     the blocks the piston is about to shift
     * @param direction the direction those blocks travel
     */
    private void breakCropsAlong(Block piston, List<Block> moved, BlockFace direction) {
        Set<BlockPositionKey> entered = new HashSet<>();
        Set<BlockPositionKey> vacated = new HashSet<>();

        if (piston != null) {
            entered.add(keyOf(piston.getRelative(direction)));
        }
        for (Block block : moved) {
            vacated.add(keyOf(block));
            entered.add(keyOf(block.getRelative(direction)));
        }

        // A tall crop occupies several cells, so the same instance can be hit
        // more than once; collect first, remove once.
        Set<CropInstance> doomed = new LinkedHashSet<>();
        for (BlockPositionKey key : entered) {
            instances.at(key).ifPresent(doomed::add);
        }
        // A block sliding out of a cell also takes the crop rooted on top of
        // it, the same way breaking that block by hand does. Only vacated
        // cells get this treatment: a block arriving *under* a crop is a new
        // floor, not a reason to destroy it.
        for (BlockPositionKey key : vacated) {
            instances.at(key).ifPresent(doomed::add);
            instances.at(key.offset(0, 1, 0)).ifPresent(doomed::add);
        }

        doomed.removeIf(crop -> crop.state() == CropState.REMOVED);
        for (CropInstance crop : doomed) {
            harvests.removeCrop(crop, CropRemoveEvent.Reason.PISTON, true);
        }
    }

    // ==================== chunk lifecycle ====================

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        instances.onChunkLoad(event.getChunk());
        soils.onChunkLoad(event.getChunk());
        sprinklers.onChunkLoad(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        instances.onChunkUnload(event.getChunk());
        soils.onChunkUnload(event.getChunk());
        sprinklers.onChunkUnload(event.getChunk());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        instances.onWorldUnload(event.getWorld().getUID());
        soils.onWorldUnload(event.getWorld());
        sprinklers.onWorldUnload(event.getWorld());
    }

    private static BlockPositionKey keyOf(Block block) {
        return new BlockPositionKey(
            block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
