package codes.castled.allium.harvest.crop.craftengine;

import codes.castled.allium.harvest.crop.CropBlockRenderer;
import codes.castled.allium.harvest.crop.CropInstanceService;
import codes.castled.allium.harvest.crop.def.CropRegistry;
import java.util.logging.Logger;
import org.bukkit.event.Listener;

/**
 * The single place where Allium reaches into CraftEngine.
 *
 * <p>Everything in this package touches CraftEngine classes directly, so the
 * classes must only be loaded once CraftEngine is installed. Keeping the
 * creation of the two integrations behind this factory means the rest of the
 * harvest module never mentions a CraftEngine class at all.</p>
 */
public final class CraftEngineSupport {

    private CraftEngineSupport() {}

    /** The renderer that draws crop stages as CraftEngine blocks. */
    public static CropBlockRenderer createBlockRenderer(Logger logger) {
        return new CraftEngineBlockService(logger);
    }

    /** The listener that keeps CraftEngine's block behaviour off crop blocks. */
    public static Listener createCropListener(CropInstanceService instances, CropRegistry registry) {
        return new CraftEngineCropListener(instances, registry);
    }
}
