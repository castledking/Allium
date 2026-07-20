package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.HarvestBranding;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.event.CropRemoveEvent;
import codes.castled.allium.harvest.item.ItemRef;
import codes.castled.allium.harvest.item.ItemResolverChain;
import codes.castled.allium.harvest.soil.SoilService;
import codes.castled.allium.harvest.sprinkler.SprinklerService;
import codes.castled.allium.harvest.storage.CropStorage;
import codes.castled.allium.harvest.util.BlockPositionKey;
import codes.castled.allium.harvest.util.Durations;
import codes.castled.allium.harvest.visual.VisualTags;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
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
            if (crop.state() == CropState.MATURE) {
                if (!player.hasPermission(HarvestBranding.PERMISSION_ROOT + ".crop.harvest")) {
                    return;
                }
                CropHarvestService.HarvestResult result = harvests.harvest(player, crop);
                if (!result.success() && result.denyReason() != null) {
                    player.sendActionBar(MiniMessage.miniMessage()
                        .deserialize("<red>" + result.denyReason() + "</red>"));
                }
                event.setCancelled(true);
            } else {
                sendGrowthProgress(player, crop);
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

    private void sendGrowthProgress(Player player, CropInstance crop) {
        long remaining = Math.max(0L, crop.nextGrowthAt() - System.currentTimeMillis());
        player.sendActionBar(MiniMessage.miniMessage().deserialize(
            "<yellow>Growing — next stage in " + Durations.format(remaining) + "</yellow>"));
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
        harvests.removeCrop(crop, CropRemoveEvent.Reason.BROKEN);
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
        if (crop.state() != CropState.MATURE) {
            sendGrowthProgress(player, crop);
            return;
        }
        if (!player.hasPermission(HarvestBranding.PERMISSION_ROOT + ".crop.harvest")) {
            return;
        }
        CropHarvestService.HarvestResult result = harvests.harvest(player, crop);
        if (!result.success() && result.denyReason() != null) {
            player.sendActionBar(MiniMessage.miniMessage()
                .deserialize("<red>" + result.denyReason() + "</red>"));
        }
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
        harvests.removeCrop(crop, CropRemoveEvent.Reason.BROKEN);
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            Block destination = block.getRelative(event.getDirection());
            if (instances.isOccupied(keyOf(block)) || instances.isOccupied(keyOf(destination))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            Block destination = block.getRelative(event.getDirection());
            if (instances.isOccupied(keyOf(block)) || instances.isOccupied(keyOf(destination))) {
                event.setCancelled(true);
                return;
            }
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
