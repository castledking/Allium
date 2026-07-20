package codes.castled.allium.harvest.api;

import codes.castled.allium.harvest.crop.HarvestWeightModifier;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Public API of the Allium harvest module, obtainable through Bukkit's
 * ServicesManager:
 *
 * <pre>{@code
 * AlliumHarvestApi api = Bukkit.getServicesManager().load(AlliumHarvestApi.class);
 * }</pre>
 *
 * Views expose immutable copies — internal model objects are never leaked.
 */
public interface AlliumHarvestApi {

    // ==================== views ====================

    record CropInstanceView(
        UUID instanceId,
        String cropId,
        String pathId,
        UUID worldId,
        int x, int y, int z,
        int stage,
        String state,
        long plantedAt,
        UUID planter,
        String fertilizerId
    ) {}

    record CropDefinitionView(
        String id,
        String displayName,
        String seedItem,
        List<String> pathIds
    ) {}

    record TrackedSpawnerView(
        UUID worldId,
        int x, int y, int z,
        String entityType,
        boolean hasVisual
    ) {}

    record PlantResult(boolean success, String pathId, String denyReason) {}

    record HarvestResult(boolean success, String primaryOutcomeId, List<ItemStack> drops, String denyReason) {}

    // ==================== crops ====================

    /** The crop occupying the given block position (any footprint cell), if loaded. */
    Optional<CropInstanceView> getCrop(Location location);

    Optional<CropDefinitionView> getCropDefinition(String id);

    Collection<CropDefinitionView> getCropDefinitions();

    /**
     * Plants a crop at the location (the block below must be valid soil).
     * Runs the full plant pipeline including events and the weighted path
     * roll; no seed item is consumed. Must be called on the thread owning the
     * location's region.
     */
    PlantResult plantCrop(Player player, String cropId, Location location);

    /** Harvests the crop at the location. Region thread required. */
    HarvestResult harvestCrop(Player player, Location location);

    /**
     * Registers a quality weight modifier that runs before every mutually
     * exclusive primary-outcome selection (skills, boosters, seasons, …).
     */
    void registerWeightModifier(HarvestWeightModifier modifier);

    // ==================== spawners ====================

    Optional<TrackedSpawnerView> getTrackedSpawner(Location location);

    /**
     * Idempotently re-converges the spawner visual at the location. Safe to
     * call repeatedly. Region thread required.
     */
    void refreshSpawnerModel(Location location);
}
