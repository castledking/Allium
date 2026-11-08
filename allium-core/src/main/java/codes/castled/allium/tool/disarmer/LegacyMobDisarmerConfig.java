package codes.castled.allium.tool.disarmer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves a pre-definition {@code tools.mob-disarmer} section from {@code config.yml} into the
 * {@code tools/mob_disarmer.yml} layout. Values are copied as they are, so an invalid old value
 * makes the new definition invalid and is reported, rather than being replaced by a default.
 */
public final class LegacyMobDisarmerConfig {

    /** Old key (under {@code tools.mob-disarmer}) to its path in the definition file. */
    public static final Map<String, String> PATHS = paths();

    private LegacyMobDisarmerConfig() {
    }

    /**
     * @param legacy the old section as nested maps
     * @return the settings to write, by new path, and the old keys that have no new home
     */
    public static Migration migrate(Map<String, ?> legacy) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        for (Map.Entry<String, ?> entry : legacy.entrySet()) {
            String key = entry.getKey();
            if (key.equals("break-chance") && entry.getValue() instanceof Map<?, ?> breakChance) {
                for (Map.Entry<?, ?> nested : breakChance.entrySet()) {
                    String path = PATHS.get("break-chance." + nested.getKey());
                    if (path == null) {
                        unmapped.add("break-chance." + nested.getKey());
                    } else {
                        values.put(path, nested.getValue());
                    }
                }
                continue;
            }
            String path = PATHS.get(key);
            if (path == null) {
                unmapped.add(key);
            } else {
                values.put(path, entry.getValue());
            }
        }
        return new Migration(values, unmapped);
    }

    public record Migration(Map<String, Object> values, List<String> unmapped) {
        public Migration {
            values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
            unmapped = List.copyOf(unmapped);
        }
    }

    private static Map<String, String> paths() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("enabled", "enabled");
        paths.put("appearance", "appearance.default");
        paths.put("aliases", "appearance.aliases");
        paths.put("charge-variants", "appearance.charge-variants");
        paths.put("max-charges", "charges.max");
        paths.put("recharge-seconds", "charges.recharge-seconds");
        paths.put("break-chance.preset", "charges.break-chance.preset");
        paths.put("break-chance.per-charge", "charges.break-chance.per-charge");
        paths.put("break-chance.final-charge", "charges.break-chance.final-charge");
        paths.put("radius", "targets.radius");
        paths.put("excluded-mobs", "targets.excluded-mobs");
        paths.put("exclude-npcs", "targets.exclude-npcs");
        paths.put("drop-rule", "disarm.drop-rule");
        paths.put("pickup-block-seconds", "disarm.pickup-block-seconds");
        paths.put("click-guard-millis", "timing.click-guard-millis");
        paths.put("status-cooldown-millis", "timing.status-cooldown-millis");
        paths.put("sound", "sounds.disarm.sound");
        return java.util.Collections.unmodifiableMap(paths);
    }
}
