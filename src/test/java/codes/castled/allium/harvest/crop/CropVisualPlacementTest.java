package codes.castled.allium.harvest.crop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import codes.castled.allium.harvest.crop.def.StageDefinition.VisualOverride;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.junit.jupiter.api.Test;

class CropVisualPlacementTest {
    @Test void normalizedModelBaseFollowsFarmlandAndFullSoil() {
        var visual = new VisualOverride(ItemDisplayTransform.GROUND, 1f, 0d, true);
        assertEquals(-0.0625, CropVisualService.heightAboveCell(visual, 0, -0.0625));
        assertEquals(0, CropVisualService.heightAboveCell(visual, 0, 0));
    }

    @Test void scalingANormalizedModelDoesNotMoveItsBase() {
        for (float scale : new float[]{0.5f, 1f, 1.7f, 3f}) {
            var visual = new VisualOverride(ItemDisplayTransform.GROUND, scale, 0.01d, true);
            assertEquals(-0.0525, CropVisualService.heightAboveCell(visual, 0, -0.0625), 1e-9);
        }
    }

    @Test void existingStagesRetainTheirConfiguredOffset() {
        assertEquals(0.435, CropVisualService.heightAboveCell(
            new VisualOverride(null, null, 0.31), 0, -0.0625), 1e-9);
        assertEquals(0.375, CropVisualService.heightAboveCell(VisualOverride.NONE, 0.25, 0));
    }
}
