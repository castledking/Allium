package codes.castled.allium.harvest.crop.def;

import codes.castled.allium.harvest.item.ItemRef;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Holds the active immutable crop/fertilizer definitions. Reload builds a
 * complete new snapshot and swaps it atomically, so a failed reload never
 * leaves the registry half-updated and running crops always resolve against a
 * consistent view.
 */
public final class CropRegistry {

    private record Snapshot(
        Map<String, CropDefinition> crops,
        Map<String, FertilizerDefinition> fertilizers,
        Map<ItemRef, String> cropBySeed,
        Map<ItemRef, String> fertilizerByItem
    ) {}

    private volatile Snapshot snapshot =
        new Snapshot(Map.of(), Map.of(), Map.of(), Map.of());

    /** Atomically replaces the active definitions. */
    public void swap(Map<String, CropDefinition> crops, Map<String, FertilizerDefinition> fertilizers) {
        Map<ItemRef, String> bySeed = new LinkedHashMap<>();
        for (CropDefinition crop : crops.values()) {
            bySeed.putIfAbsent(crop.seed(), crop.id());
        }
        Map<ItemRef, String> byFertilizerItem = new LinkedHashMap<>();
        for (FertilizerDefinition fertilizer : fertilizers.values()) {
            byFertilizerItem.putIfAbsent(fertilizer.item(), fertilizer.id());
        }
        this.snapshot = new Snapshot(
            Map.copyOf(crops), Map.copyOf(fertilizers), bySeed, byFertilizerItem);
    }

    public Optional<CropDefinition> crop(String id) {
        return Optional.ofNullable(snapshot.crops().get(id));
    }

    public Collection<CropDefinition> crops() {
        return snapshot.crops().values();
    }

    public Optional<CropDefinition> cropBySeed(ItemRef seed) {
        Snapshot s = snapshot;
        String id = s.cropBySeed().get(seed);
        return id == null ? Optional.empty() : Optional.ofNullable(s.crops().get(id));
    }

    public Optional<FertilizerDefinition> fertilizer(String id) {
        return Optional.ofNullable(snapshot.fertilizers().get(id));
    }

    public Optional<FertilizerDefinition> fertilizerByItem(ItemRef item) {
        Snapshot s = snapshot;
        String id = s.fertilizerByItem().get(item);
        return id == null ? Optional.empty() : Optional.ofNullable(s.fertilizers().get(id));
    }

    public int size() {
        return snapshot.crops().size();
    }
}
