package codes.castled.allium.harvest.api;

import codes.castled.allium.harvest.crop.CropHarvestService;
import codes.castled.allium.harvest.crop.CropInstance;
import codes.castled.allium.harvest.crop.CropInstanceService;
import codes.castled.allium.harvest.crop.CropPlacementService;
import codes.castled.allium.harvest.crop.HarvestWeightModifier;
import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import codes.castled.allium.harvest.spawner.SpawnerTrackingService;
import codes.castled.allium.harvest.util.BlockPositionKey;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** ServicesManager-backed implementation of {@link AlliumHarvestApi}. */
public final class HarvestApiImpl implements AlliumHarvestApi {

    private final CropRegistry registry;
    private final CropInstanceService instances;
    private final CropPlacementService placement;
    private final CropHarvestService harvests;
    private final SpawnerTrackingService spawners;

    public HarvestApiImpl(
        CropRegistry registry,
        CropInstanceService instances,
        CropPlacementService placement,
        CropHarvestService harvests,
        SpawnerTrackingService spawners
    ) {
        this.registry = registry;
        this.instances = instances;
        this.placement = placement;
        this.harvests = harvests;
        this.spawners = spawners;
    }

    private static BlockPositionKey keyOf(Location location) {
        return new BlockPositionKey(location.getWorld().getUID(),
            location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static CropInstanceView view(CropInstance crop) {
        return new CropInstanceView(
            crop.instanceId(), crop.cropId(), crop.pathId(),
            crop.position().worldId(), crop.position().x(), crop.position().y(), crop.position().z(),
            crop.stage(), crop.state().name(), crop.plantedAt(), crop.planter(), crop.fertilizerId());
    }

    private static CropDefinitionView view(CropDefinition definition) {
        return new CropDefinitionView(
            definition.id(), definition.displayName(), definition.seed().toString(),
            List.copyOf(definition.paths().keySet()));
    }

    @Override
    public Optional<CropInstanceView> getCrop(Location location) {
        return instances.at(keyOf(location)).map(HarvestApiImpl::view);
    }

    @Override
    public Optional<CropDefinitionView> getCropDefinition(String id) {
        return registry.crop(id).map(HarvestApiImpl::view);
    }

    @Override
    public Collection<CropDefinitionView> getCropDefinitions() {
        return registry.crops().stream().map(HarvestApiImpl::view).toList();
    }

    @Override
    public PlantResult plantCrop(Player player, String cropId, Location location) {
        Optional<CropDefinition> definition = registry.crop(cropId);
        if (definition.isEmpty()) {
            return new PlantResult(false, null, "Unknown crop '" + cropId + "'");
        }
        CropPlacementService.PlantResult result = placement.plant(
            player, definition.get(), CropPlacementService.soilBelow(location), null, null);
        return new PlantResult(result.success(), result.pathId(), result.denyReason());
    }

    @Override
    public HarvestResult harvestCrop(Player player, Location location) {
        Optional<CropInstance> crop = instances.at(keyOf(location));
        if (crop.isEmpty()) {
            return new HarvestResult(false, null, List.of(), "No crop here");
        }
        CropHarvestService.HarvestResult result = harvests.harvest(player, crop.get());
        return new HarvestResult(result.success(), result.primaryOutcomeId(), result.drops(), result.denyReason());
    }

    @Override
    public void registerWeightModifier(HarvestWeightModifier modifier) {
        harvests.registerModifier(modifier);
    }

    @Override
    public Optional<TrackedSpawnerView> getTrackedSpawner(Location location) {
        return spawners.trackedAt(keyOf(location)).map(spawner -> new TrackedSpawnerView(
            spawner.position().worldId(), spawner.position().x(),
            spawner.position().y(), spawner.position().z(),
            spawner.entityType(), spawner.visualEntityId() != null));
    }

    @Override
    public void refreshSpawnerModel(Location location) {
        spawners.refresh(location.getBlock());
    }
}
