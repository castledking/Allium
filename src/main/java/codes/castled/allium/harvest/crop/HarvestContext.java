package codes.castled.allium.harvest.crop;

import codes.castled.allium.harvest.crop.def.CropDefinition;
import codes.castled.allium.harvest.crop.def.CropPathDefinition;
import codes.castled.allium.harvest.crop.def.FertilizerDefinition;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Everything a {@link HarvestWeightModifier} may inspect when adjusting
 * quality weights for one harvest.
 */
public record HarvestContext(
    Player player,
    CropInstance crop,
    CropDefinition definition,
    CropPathDefinition path,
    Location location,
    @Nullable FertilizerDefinition fertilizer
) {}
