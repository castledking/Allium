package codes.castled.allium.spawnercraft;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code spawner_heads.yml}: which mobs drop an Allium head, at what odds,
 * and whether a "More Mob Heads" datapack head is removed for those mobs so only
 * one source drops.
 *
 * The file is authoritative: a mob that is absent (or disabled) drops no Allium head.
 */
public final class SpawnerHeadConfig {

    public static final String FILE_NAME = "spawner_heads.yml";

    /** One mob's head: drop odds plus the components that identify it. */
    public record MobHead(double chance, double lootingChance, double lootingPerLevel, String sound, String texture) {}

    private static volatile Map<EntityType, MobHead> heads = Map.of();
    private static volatile boolean overrideDatapackHeads = true;

    private SpawnerHeadConfig() {}

    public static void reload(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        overrideDatapackHeads = config.getBoolean("settings.override-moremobs-head-drop", true);
        heads = parse(config.getConfigurationSection("heads"), plugin);
        heads.forEach((type, head) -> MobHeadRegistry.registerSoundMapping(soundMob(head.sound()), type));
    }

    static Map<EntityType, MobHead> parse(ConfigurationSection section, Plugin plugin) {
        Map<EntityType, MobHead> parsed = new HashMap<>();
        if (section == null) {
            return parsed;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null || !entry.getBoolean("enabled", true)) {
                continue;
            }
            EntityType type;
            try {
                type = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warn(plugin, "Unknown mob '" + key + "' in " + FILE_NAME);
                continue;
            }
            String sound = entry.getString("sound", "");
            String texture = entry.getString("texture", "");
            if (sound.isBlank() || texture.isBlank()) {
                warn(plugin, key + " in " + FILE_NAME + " needs both a sound and a texture; skipping it.");
                continue;
            }
            double chance = entry.getDouble("chance", 0D);
            parsed.put(type, new MobHead(
                    chance,
                    entry.getDouble("looting-chance", chance),
                    entry.getDouble("looting-chance-per-level", 0D),
                    sound,
                    texture));
        }
        return parsed;
    }

    /** "entity.magma_cube.squish" -> "magma_cube" */
    static String soundMob(String sound) {
        String key = sound.startsWith("minecraft:") ? sound.substring(10) : sound;
        if (!key.startsWith("entity.")) return "";
        String rest = key.substring(7);
        int dot = rest.indexOf('.');
        return dot > 0 ? rest.substring(0, dot) : rest;
    }

    private static void warn(Plugin plugin, String message) {
        if (plugin != null) {
            plugin.getLogger().warning("[SpawnerHeads] " + message);
        }
    }

    public static MobHead get(EntityType type) {
        return heads.get(type);
    }

    /** Mobs that currently drop a head, lower-case and sorted. */
    public static List<String> mobNames() {
        return mobNames(heads.keySet());
    }

    static List<String> mobNames(Collection<EntityType> types) {
        return types.stream()
                .map(type -> type.name().toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
    }

    public static boolean handles(EntityType type) {
        return heads.containsKey(type);
    }

    /** True when a datapack's own head for a configured mob is removed on death. */
    public static boolean overrideDatapackHeads() {
        return overrideDatapackHeads;
    }
}
