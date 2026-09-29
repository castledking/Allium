package codes.castled.allium.harvest.kitchen;

import codes.castled.allium.scheduler.SchedulerAdapter;
import codes.castled.allium.scheduler.TaskHandle;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * DecentHolograms-backed pie holograms. Touches DecentHolograms classes
 * directly, so it is only created when that plugin is enabled.
 *
 * <p>Holograms are created with {@code saveToFile = false}: the pie entity is
 * the source of truth and they are rebuilt whenever a pie's chunk loads, so
 * nothing is ever written to DecentHolograms' own files.
 */
final class DecentPieHolograms implements PieHolograms {

    private static final String PREFIX = "allium_pie_";

    private final Logger logger;
    private final Map<UUID, Entry> shown = new ConcurrentHashMap<>();
    private final TaskHandle cycleTask;
    private boolean warned;

    private static final class Entry {
        final String name;
        final List<List<String>> frames;
        int frame;

        Entry(String name, List<List<String>> frames) {
            this.name = name;
            this.frames = frames;
        }
    }

    DecentPieHolograms(Plugin plugin, long cycleTicks) {
        this.logger = plugin.getLogger();
        this.cycleTask = SchedulerAdapter.runRepeatingGlobal(plugin, this::cycle, cycleTicks, cycleTicks);
    }

    @Override
    public void show(UUID pieId, Location location, List<List<String>> frames) {
        if (frames.isEmpty()) {
            remove(pieId);
            return;
        }
        String name = PREFIX + pieId;
        try {
            Hologram hologram = DHAPI.getHologram(name);
            if (hologram == null) {
                DHAPI.createHologram(name, location, false, frames.get(0));
            } else {
                DHAPI.setHologramLines(hologram, frames.get(0));
            }
            shown.put(pieId, new Entry(name, List.copyOf(frames)));
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    @Override
    public void remove(UUID pieId) {
        Entry entry = shown.remove(pieId);
        try {
            DHAPI.removeHologram(entry == null ? PREFIX + pieId : entry.name);
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    @Override
    public void removeAll() {
        cycleTask.cancel();
        for (UUID id : List.copyOf(shown.keySet())) {
            remove(id);
        }
    }

    private void cycle() {
        for (Entry entry : shown.values()) {
            if (entry.frames.size() < 2) continue;
            entry.frame = (entry.frame + 1) % entry.frames.size();
            try {
                Hologram hologram = DHAPI.getHologram(entry.name);
                if (hologram != null) {
                    DHAPI.setHologramLines(hologram, entry.frames.get(entry.frame));
                }
            } catch (RuntimeException e) {
                warnOnce(e);
            }
        }
    }

    private void warnOnce(RuntimeException e) {
        if (!warned) {
            warned = true;
            logger.warning("[Kitchen] DecentHolograms call failed: " + e);
        }
    }
}
