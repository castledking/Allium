package codes.castled.allium.tool;

import org.bukkit.Particle;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves vanilla particle names ({@code soul_fire_flame}, {@code enchant}) to Bukkit particles on
 * every supported version. 1.20.1 has an enum with no keys and older names
 * ({@code ENCHANTMENT_TABLE}); 1.20.5+ keys each particle by its vanilla name. Only particles that
 * need no extra data (colour, block, item) are accepted.
 */
public final class BukkitParticles {

    /** Pre-1.20.5 enum names whose vanilla name differs. */
    private static final Map<String, String> LEGACY_NAMES = Map.of(
            "ENCHANTMENT_TABLE", "enchant",
            "VILLAGER_HAPPY", "happy_villager",
            "VILLAGER_ANGRY", "angry_villager",
            "SPELL_WITCH", "witch",
            "SPELL_MOB", "entity_effect",
            "EXPLOSION_NORMAL", "poof",
            "SMOKE_NORMAL", "smoke",
            "FIREWORKS_SPARK", "firework",
            "TOTEM", "totem_of_undying",
            "WATER_SPLASH", "splash");

    private static final Map<String, Optional<Particle>> CACHE = new ConcurrentHashMap<>();

    private BukkitParticles() {
    }

    public static Optional<Particle> resolve(String vanillaName) {
        String name = vanillaName.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        return CACHE.computeIfAbsent(name, BukkitParticles::find);
    }

    private static Optional<Particle> find(String name) {
        Object[] all;
        try {
            all = (Object[]) Particle.class.getMethod("values").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
        Method getKey = null;
        try {
            getKey = Particle.class.getMethod("getKey");
        } catch (NoSuchMethodException ignored) {
            // 1.20.1: no keys
        }
        for (Object value : all) {
            Particle particle = (Particle) value;
            String key = null;
            if (getKey != null) {
                try {
                    Object namespaced = getKey.invoke(particle);
                    key = namespaced == null ? null : namespaced.toString().replace("minecraft:", "");
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    key = null;
                }
            }
            if (key == null) {
                String enumName = ((Enum<?>) value).name();
                key = LEGACY_NAMES.getOrDefault(enumName, enumName.toLowerCase(Locale.ROOT));
            }
            if (key.equals(name)) {
                return particle.getDataType() == Void.class ? Optional.of(particle) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
