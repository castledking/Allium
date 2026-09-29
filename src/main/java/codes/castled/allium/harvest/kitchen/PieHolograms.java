package codes.castled.allium.harvest.kitchen;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;

/** Floating instructions above pies being assembled. */
interface PieHolograms {

    /**
     * Shows or replaces the hologram for a pie. With more than one frame the
     * hologram cycles through them (e.g. one icon per possible filling).
     */
    void show(UUID pieId, Location location, List<List<String>> frames);

    void remove(UUID pieId);

    void removeAll();

    PieHolograms NONE = new PieHolograms() {
        @Override public void show(UUID pieId, Location location, List<List<String>> frames) {}
        @Override public void remove(UUID pieId) {}
        @Override public void removeAll() {}
    };
}
