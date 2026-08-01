package codes.castled.allium.harvest.config;

import codes.castled.allium.harvest.crop.LiquidResponse;
import codes.castled.allium.harvest.crop.def.GrowthSettings;
import codes.castled.allium.harvest.crop.def.InteractionSettings;
import codes.castled.allium.harvest.storage.HarvestDatabase;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Global harvest-module settings from {@code harvest/config.yml}. Crop
 * behavior lives in per-crop files; this holds storage, engine pacing and
 * visual defaults.
 */
public record HarvestConfig(
    boolean enabled,
    HarvestDatabase.Settings storage,
    Growth growthEngine,
    Visuals visuals,
    Spawners spawners,
    Soil soil,
    Sprinklers sprinklers,
    Liquids liquids,
    CropDefaults cropDefaults
) {

    public record Growth(
        int checkIntervalTicks,
        int checksPerTick,
        int flushIntervalTicks
    ) {}

    /**
     * Per-crop settings that most servers want to set once. Every one of these
     * can be overridden by an individual crop file, so this is a starting point
     * rather than a policy.
     */
    public record CropDefaults(
        InteractionSettings interaction,
        double growthRandomness
    ) {}

    /**
     * @param displayTransform which of the model's authored display contexts to
     *                         render. This decides orientation and base size —
     *                         see {@link #displayTransform} for why it matters.
     */
    /**
     * @param clickable        whether each crop gets an {@code Interaction}
     *                         hitbox so it can be right-clicked and punched
     *                         directly. Display entities have no hitbox of
     *                         their own, so without this a crop can only be
     *                         reached through the block under it.
     * @param interactionWidth hitbox width in blocks
     * @param interactionHeight hitbox height in blocks
     */
    public record Visuals(
        float viewRange,
        float scale,
        double yOffset,
        ItemDisplayTransform displayTransform,
        boolean clickable,
        float interactionWidth,
        float interactionHeight
    ) {}

    /**
     * Soil lifecycle settings.
     *
     * @param lifetimeMillis     how long untracked/new soil lasts before it
     *                           wears out; {@code 0} means soil never wears out
     * @param forgetAfterMillis  how long a worn-out record is remembered before
     *                           the spot counts as new ground again. This is
     *                           what stops break-and-replace from resetting the
     *                           timer, so it should comfortably exceed the time
     *                           a player would wait them out.
     * @param blockPlantMessage  shown when planting on worn-out soil
     */
    public record Soil(
        boolean enabled,
        long lifetimeMillis,
        long forgetAfterMillis,
        String blockPlantMessage
    ) {}

    /** Sprinkler settings; per-tier behaviour lives in sprinklers.yml. */
    public record Sprinklers(
        boolean enabled,
        int maxPerChunk
    ) {}

    /**
     * What water and lava do to a crop they reach.
     *
     * <p>Crops are display entities standing in air, so nothing in vanilla
     * stops a fluid flowing straight through one. Left alone that reads as a
     * bug — a stream runs over a field and the plants keep standing in it — so
     * the vanilla outcome is reproduced deliberately instead.
     *
     * @param enabled turning this off leaves crops untouched by liquids
     *                entirely, which is the only setting under which a crop can
     *                end up standing inside a fluid block
     */
    public record Liquids(
        boolean enabled,
        LiquidResponse water,
        LiquidResponse lava
    ) {
        public static final Liquids DEFAULT =
            new Liquids(true, LiquidResponse.DROP, LiquidResponse.BURN);
    }

    public record Spawners(
        boolean enabled,
        boolean scanChunkOnLoad,
        int maxBlockEntitiesPerTick,
        float viewRange,
        ItemDisplayTransform displayTransform
    ) {}

    /**
     * Lifts crop models clear of the ground they stand on.
     *
     * <p>{@code GROUND} is the dropped-item display context, and a dropped item
     * is drawn centred on its position — so a model rendered at a block's own
     * y-coordinate has roughly its lower half buried in the soil. This raises it
     * back out.
     *
     * <p>The value is tied to the transform, not to any particular model: it is
     * right for {@code GROUND}, and wrong for {@code NONE}, which applies no
     * transform of its own and needs no correction. Crops using a different
     * transform should override {@code y-offset} alongside it.
     */
    private static final double DEFAULT_Y_OFFSET = 0.43D;

    public static HarvestConfig from(FileConfiguration yaml) {
        boolean enabled = yaml.getBoolean("enabled", true);

        String typeRaw = yaml.getString("storage.type", "H2").toUpperCase(Locale.ROOT);
        HarvestDatabase.Dialect dialect;
        switch (typeRaw) {
            case "SQLITE" -> dialect = HarvestDatabase.Dialect.SQLITE;
            case "MYSQL", "MARIADB" -> dialect = HarvestDatabase.Dialect.MYSQL;
            default -> dialect = HarvestDatabase.Dialect.H2;
        }
        HarvestDatabase.Settings storage = new HarvestDatabase.Settings(
            dialect,
            yaml.getString("storage.host", "localhost"),
            yaml.getInt("storage.port", 3306),
            yaml.getString("storage.database", "allium_harvest"),
            yaml.getString("storage.username", "allium"),
            yaml.getString("storage.password", "change-me"),
            yaml.getString("storage.table-prefix", "")
        );

        ConfigurationSection growth = yaml.getConfigurationSection("growth-engine");
        Growth growthEngine = new Growth(
            clamp(growth == null ? 20 : growth.getInt("check-interval-ticks", 20), 1, 1200),
            clamp(growth == null ? 100 : growth.getInt("checks-per-tick", 100), 1, 10_000),
            clamp(growth == null ? 100 : growth.getInt("flush-interval-ticks", 100), 20, 12_000)
        );

        ConfigurationSection visualsSection = yaml.getConfigurationSection("crop-visuals");
        Visuals visuals = new Visuals(
            (float) (visualsSection == null ? 48.0 : visualsSection.getDouble("view-range", 48.0)),
            (float) (visualsSection == null ? 1.0 : visualsSection.getDouble("scale", 1.0)),
            visualsSection == null ? DEFAULT_Y_OFFSET
                : visualsSection.getDouble("y-offset", DEFAULT_Y_OFFSET),
            displayTransform(visualsSection, ItemDisplayTransform.GROUND),
            visualsSection == null || visualsSection.getBoolean("clickable", true),
            (float) clampDouble(visualsSection == null ? 0.7
                : visualsSection.getDouble("interaction-width", 0.7), 0.1, 4.0),
            (float) clampDouble(visualsSection == null ? 1.0
                : visualsSection.getDouble("interaction-height", 1.0), 0.1, 4.0)
        );

        ConfigurationSection spawnerSection = yaml.getConfigurationSection("spawner-models");
        Spawners spawners = new Spawners(
            spawnerSection == null || spawnerSection.getBoolean("enabled", true),
            spawnerSection == null
                || spawnerSection.getBoolean("reconciliation.scan-spawner-blocks-on-chunk-load", true),
            clamp(spawnerSection == null ? 50
                : spawnerSection.getInt("reconciliation.maximum-block-entities-per-tick", 50), 1, 1000),
            (float) (spawnerSection == null ? 48.0 : spawnerSection.getDouble("view-range", 48.0)),
            displayTransform(spawnerSection, ItemDisplayTransform.GROUND)
        );

        ConfigurationSection soilSection = yaml.getConfigurationSection("soil");
        Soil soil = new Soil(
            soilSection != null && soilSection.getBoolean("enabled", false),
            duration(soilSection, "lifetime", 7L * 24 * 60 * 60 * 1000),
            duration(soilSection, "forget-exhausted-after", 14L * 24 * 60 * 60 * 1000),
            soilSection == null
                ? "This soil is exhausted — use a soil retainer to restore it."
                : soilSection.getString("exhausted-message",
                    "This soil is exhausted — use a soil retainer to restore it.")
        );

        ConfigurationSection sprinklerSection = yaml.getConfigurationSection("sprinklers");
        Sprinklers sprinklers = new Sprinklers(
            sprinklerSection == null || sprinklerSection.getBoolean("enabled", true),
            clamp(sprinklerSection == null ? 64 : sprinklerSection.getInt("maximum-per-chunk", 64), 1, 4096)
        );

        ConfigurationSection liquidSection = yaml.getConfigurationSection("liquids");
        Liquids liquids = liquids(liquidSection);

        ConfigurationSection defaultsSection = yaml.getConfigurationSection("crop-defaults");
        CropDefaults cropDefaults = cropDefaults(defaultsSection);

        return new HarvestConfig(
            enabled, storage, growthEngine, visuals, spawners, soil, sprinklers,
            liquids, cropDefaults);
    }

    /**
     * Reads the {@code liquids} block. An unrecognised response falls back to
     * the vanilla one for that fluid rather than failing — a typo here should
     * cost you the setting, not leave crops floating in water.
     */
    private static Liquids liquids(ConfigurationSection section) {
        if (section == null) {
            return Liquids.DEFAULT;
        }
        return new Liquids(
            section.getBoolean("enabled", Liquids.DEFAULT.enabled()),
            LiquidResponse.parse(section.getString("water")).orElse(Liquids.DEFAULT.water()),
            LiquidResponse.parse(section.getString("lava")).orElse(Liquids.DEFAULT.lava()));
    }

    /**
     * Reads the {@code crop-defaults} block. Unparseable values fall back
     * rather than failing — a typo in a default must not take down every crop
     * on the server.
     */
    private static CropDefaults cropDefaults(ConfigurationSection section) {
        InteractionSettings fallback = InteractionSettings.DEFAULT;
        if (section == null) {
            return new CropDefaults(fallback, GrowthSettings.DEFAULT.randomness());
        }
        ConfigurationSection interactionSection = section.getConfigurationSection("interaction");
        InteractionSettings interaction = interactionSection == null ? fallback
            : new InteractionSettings(
                interactionSection.getBoolean("right-click-harvest", fallback.rightClickHarvest()),
                interactionSection.getBoolean("break-harvest", fallback.breakHarvest()),
                progressDisplay(interactionSection.getString("progress-check"), fallback.progressDisplay()));

        double randomness = section.getDouble("growth.randomness", GrowthSettings.DEFAULT.randomness());
        return new CropDefaults(interaction, randomness);
    }

    private static InteractionSettings.ProgressDisplay progressDisplay(
        String raw, InteractionSettings.ProgressDisplay fallback) {
        return InteractionSettings.ProgressDisplay.parse(raw).orElse(fallback);
    }

    /**
     * Reads the {@code display-transform} setting.
     *
     * <p>An item model is authored with a separate transform per context —
     * held in hand, on a head, dropped on the ground, in an item frame. An
     * {@code ItemDisplay} picks one of those, and the choice decides both the
     * orientation and the base scale of what players see.
     *
     * <p>{@code FIXED} is the item-frame context, and item frames lay their
     * contents flat — which renders an upright crop model on its side at full
     * size. {@code GROUND} is the dropped-item context, which pack models
     * author upright and at half scale, matching how the item looks in the
     * world. Hence the default.
     *
     * <p>An unrecognised value falls back rather than failing: a typo here
     * should cost you the right orientation, not the whole module.
     */
    private static ItemDisplayTransform displayTransform(
        ConfigurationSection section, ItemDisplayTransform fallback) {
        if (section == null) {
            return fallback;
        }
        String raw = section.getString("display-transform");
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return ItemDisplayTransform.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Reads a duration string ("7d", "12h") into millis, falling back to the
     * default when absent or unparseable — a bad duration here must not stop
     * the module from starting.
     */
    private static long duration(ConfigurationSection section, String path, long fallbackMillis) {
        if (section == null) {
            return fallbackMillis;
        }
        String raw = section.getString(path);
        if (raw == null || raw.isBlank()) {
            return fallbackMillis;
        }
        if (raw.trim().equals("0") || raw.equalsIgnoreCase("never")) {
            return 0L;
        }
        try {
            return codes.castled.allium.harvest.util.Durations.parseMillis(raw);
        } catch (IllegalArgumentException e) {
            return fallbackMillis;
        }
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
