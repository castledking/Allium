package codes.castled.allium.explosion;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code explosions.yml}: which explosions regenerate, how fast, and the explosive commands' limits.
 *
 * @param sources         world explosions that put their blocks back
 * @param commands        explosions of Allium's commands that put their blocks back
 * @param delayTicks      wait after an explosion before its blocks start coming back
 * @param blocksPerSecond blocks put back per second, per explosion
 * @param disabledWorlds  lower-case world names where nothing regenerates
 * @param ignored         blocks that never come back; they break and drop as usual
 * @param antiochAnnounce whether {@code /antioch} broadcasts Essentials' Holy Hand Grenade lines
 */
public record ExplosionSettings(
        Map<Source, Boolean> sources,
        Map<Explosive, Boolean> commands,
        long delayTicks,
        double blocksPerSecond,
        boolean effects,
        Set<String> disabledWorlds,
        boolean skipClaims,
        Set<Material> ignored,
        int maxBlocks,
        boolean protectHanging,
        double fireballMaxSpeed,
        int explodeMinPower,
        int explodeMaxPower,
        int explodeDefaultPower,
        boolean explodeIncendiary,
        boolean antiochAnnounce
) {

    /** Explosions that do not come from Allium's commands. */
    public enum Source {
        CREEPER("creeper", true),
        GHAST_FIREBALL("ghast-fireball", true),
        WITHER("wither", false),
        TNT("tnt", false),
        END_CRYSTAL("end-crystal", false),
        BLOCK("block", false),
        OTHER("other", false);

        private final String key;
        private final boolean byDefault;

        Source(String key, boolean byDefault) {
            this.key = key;
            this.byDefault = byDefault;
        }

        public String key() {
            return key;
        }
    }

    /** Allium's explosive commands; the tag their TNT, projectiles and fireballs carry. */
    public enum Explosive {
        ANTIOCH("antioch", true),
        FIREBALL("fireball", true),
        EXPLODE("explode", true),
        /** Never breaks blocks. */
        NUKE("nuke", false),
        BEEZOOKA("beezooka", false),
        KITTYCANNON("kittycannon", false);

        private final String key;
        private final boolean regenerates;

        Explosive(String key, boolean regenerates) {
            this.key = key;
            this.regenerates = regenerates;
        }

        public String key() {
            return key;
        }

        /** Whether {@code regeneration.commands} has a switch for it: the others break no blocks. */
        public boolean regenerates() {
            return regenerates;
        }

        public static Explosive of(String key) {
            for (Explosive explosive : values()) {
                if (explosive.key.equals(key)) return explosive;
            }
            return null;
        }
    }

    public static final int MAX_POWER = 127;

    public ExplosionSettings {
        sources = Collections.unmodifiableMap(new EnumMap<>(sources));
        commands = Collections.unmodifiableMap(new EnumMap<>(commands));
        disabledWorlds = Set.copyOf(disabledWorlds);
        ignored = ignored.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(ignored));
    }

    public boolean regenerates(Source source) {
        return sources.getOrDefault(source, false);
    }

    public boolean regenerates(Explosive explosive) {
        return explosive.regenerates() && commands.getOrDefault(explosive, false);
    }

    public boolean worldEnabled(String world) {
        return !disabledWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    /** Blocks put back per tick, per explosion: {@code blocksPerSecond / 20}. */
    public double blocksPerTick() {
        return blocksPerSecond / 20.0D;
    }

    /** Reads {@code explosions.yml}; unknown or out-of-range values fall back to defaults with a warning. */
    public static ExplosionSettings read(ConfigurationSection root, Consumer<String> warn) {
        ConfigurationSection regen = root.getConfigurationSection("regeneration");
        if (regen == null) {
            regen = root.createSection("regeneration");
        }
        Map<Source, Boolean> sources = new EnumMap<>(Source.class);
        ConfigurationSection sourceSection = regen.getConfigurationSection("sources");
        for (Source source : Source.values()) {
            sources.put(source, sourceSection == null ? source.byDefault : sourceSection.getBoolean(source.key, source.byDefault));
        }
        if (sourceSection != null) {
            for (String key : sourceSection.getKeys(false)) {
                if (java.util.Arrays.stream(Source.values()).noneMatch(source -> source.key.equals(key))) {
                    warn.accept("explosions.yml: regeneration.sources." + key + " is not an explosion source; known: "
                            + String.join(", ", java.util.Arrays.stream(Source.values()).map(Source::key).toList()) + ".");
                }
            }
        }
        Map<Explosive, Boolean> commands = new EnumMap<>(Explosive.class);
        ConfigurationSection commandSection = regen.getConfigurationSection("commands");
        for (Explosive explosive : Explosive.values()) {
            if (!explosive.regenerates) continue;
            commands.put(explosive, commandSection == null || commandSection.getBoolean(explosive.key, true));
        }
        if (commandSection != null) {
            for (String key : commandSection.getKeys(false)) {
                Explosive explosive = Explosive.of(key);
                if (explosive == null || !explosive.regenerates) {
                    warn.accept("explosions.yml: regeneration.commands." + key + " has no switch; only antioch, fireball and explode"
                            + " break blocks.");
                }
            }
        }
        double delaySeconds = regen.getDouble("delay-seconds", 5.0D);
        if (!Double.isFinite(delaySeconds) || delaySeconds < 0 || delaySeconds > 3600) {
            warn.accept("explosions.yml: regeneration.delay-seconds must be 0 to 3600; using 5.");
            delaySeconds = 5.0D;
        }
        double perSecond = regen.getDouble("blocks-per-second", 40.0D);
        if (!Double.isFinite(perSecond) || perSecond < 1 || perSecond > 20_000) {
            warn.accept("explosions.yml: regeneration.blocks-per-second must be 1 to 20000; using 40.");
            perSecond = 40.0D;
        }
        Set<String> worlds = new LinkedHashSet<>();
        for (String world : regen.getStringList("disabled-worlds")) {
            if (world != null && !world.isBlank()) worlds.add(world.trim().toLowerCase(Locale.ROOT));
        }
        Set<Material> ignored = EnumSet.noneOf(Material.class);
        for (String name : regen.getStringList("ignored-blocks")) {
            Material material = name == null ? null : Material.matchMaterial(name.trim());
            if (material == null || !material.isBlock()) {
                warn.accept("explosions.yml: regeneration.ignored-blocks entry '" + name + "' names no block.");
                continue;
            }
            ignored.add(material);
        }
        int maxBlocks = regen.getInt("max-blocks", 10_000);
        if (maxBlocks < 1 || maxBlocks > 200_000) {
            warn.accept("explosions.yml: regeneration.max-blocks must be 1 to 200000; using 10000.");
            maxBlocks = 10_000;
        }
        double maxSpeed = root.getDouble("fireball.max-speed", 8.0D);
        if (!Double.isFinite(maxSpeed) || maxSpeed < 0 || maxSpeed > 100) {
            warn.accept("explosions.yml: fireball.max-speed must be 0 to 100; using 8.");
            maxSpeed = 8.0D;
        }
        int min = root.getInt("explode.min-power", 1);
        int max = root.getInt("explode.max-power", 10);
        int fallback = root.getInt("explode.default-power", 2);
        if (min < 1 || max > MAX_POWER || min > max) {
            warn.accept("explosions.yml: explode needs 1 <= min-power <= max-power <= " + MAX_POWER + "; using 1 to 10.");
            min = 1;
            max = 10;
        }
        if (fallback < min || fallback > max) {
            warn.accept("explosions.yml: explode.default-power must be between min-power and max-power; using " + Math.min(max, Math.max(min, 2)) + ".");
            fallback = Math.min(max, Math.max(min, 2));
        }
        return new ExplosionSettings(sources, commands, Math.round(delaySeconds * 20.0D), perSecond,
                regen.getBoolean("effects", true), worlds, regen.getBoolean("skip-claims", true), ignored, maxBlocks,
                regen.getBoolean("protect-hanging", true), maxSpeed, min, max, fallback,
                root.getBoolean("explode.incendiary", true), root.getBoolean("antioch.announce", true));
    }
}
