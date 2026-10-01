package codes.castled.allium.tradingcards.xp;

import codes.castled.allium.tradingcards.item.TradingCardData;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Anti-farm state: which advancements have paid out, and which mobs have been
 * bred recently.
 *
 * <p>Persisted rather than held in memory, because that is the whole point of
 * each. An advancement that pays every time it fires is a repeatable xp
 * fountain, and a breeding cooldown that resets on reconnect is not a cooldown.
 * Both gates are worthless if a restart clears them.
 *
 * <p>Backed by a concurrent map so a listener on a region thread and a save
 * from the main thread never contend. Saves are debounced rather than written
 * on every award: breeding is a rare event, but an advancement completing in a
 * burst of twenty would otherwise be twenty disk writes.
 */
public final class XpAntiFarmStore {

    private static final String FILE_NAME = "xp_anti_farm.yml";

    /** Advancements that have already paid, per player. */
    private final Map<UUID, Set<String>> advancements = new ConcurrentHashMap<>();

    /** Last breeding time per player per mob type. */
    private final Map<UUID, Map<String, Long>> breedTimes = new ConcurrentHashMap<>();

    /** Last award time per player per source, for plain cooldowns. */
    private final Map<UUID, Map<String, Long>> sourceTimes = new ConcurrentHashMap<>();

    private final File file;
    private volatile boolean dirty;

    public XpAntiFarmStore(File dataFolder) {
        this.file = new File(dataFolder, FILE_NAME);
        load();
    }

    // ==================== advancements ====================

    /**
     * True the first time an advancement is seen for a player, and false after.
     *
     * <p>Records the claim as a side effect, so a caller that gets true knows
     * it may pay. Not atomic on its own: two awards in the same tick for the
     * same advancement would both see true, so {@link #claimAdvancement} is the
     * method to use when paying.
     */
    public boolean claimAdvancement(UUID player, String advancementKey) {
        if (advancementKey == null) return false;
        Set<String> claimed = advancements.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
        boolean fresh = claimed.add(advancementKey);
        if (fresh) {
            dirty = true;
        }
        return fresh;
    }

    public int advancementsClaimed(UUID player) {
        return advancements.getOrDefault(player, Set.of()).size();
    }

    // ==================== cooldowns ====================

    /**
     * True when {@code source} is off cooldown for a player, and records the
     * award.
     *
     * <p>Claim and check in one call so a caller cannot check, be interrupted,
     * and then award twice.
     */
    public boolean claimCooldown(UUID player, String source, long cooldownMillis, long now) {
        if (cooldownMillis <= 0L) {
            return true;
        }
        Map<String, Long> times = sourceTimes.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        Long last = times.get(source);
        if (last != null && now - last < cooldownMillis) {
            return false;
        }
        times.put(source, now);
        dirty = true;
        return true;
    }

    /** Milliseconds until a source is available again, or 0 when it is. */
    public long cooldownRemaining(UUID player, String source, long cooldownMillis, long now) {
        if (cooldownMillis <= 0L) return 0L;
        Long last = sourceTimes.getOrDefault(player, Map.of()).get(source);
        if (last == null) return 0L;
        long remaining = cooldownMillis - (now - last);
        return Math.max(0L, remaining);
    }

    // ==================== per-mob cooldowns ====================

    /**
     * True when this mob type is off cooldown for a player, and records it.
     *
     * <p>Keyed per mob type because breeding a chicken should not put cows on
     * a cooldown, nor the reverse. "Once per mob type" is the whole rule.
     */
    public boolean claimMobCooldown(UUID player, String mobKey, long cooldownMillis, long now) {
        if (cooldownMillis <= 0L) {
            return true;
        }
        // Normalised here rather than relying on the caller: the key is written
        // by an entity type name and read by a config id, and the two must
        // reach the same record or the gate is keyed on spelling.
        String key = mobKey == null ? "" : mobKey.toLowerCase(java.util.Locale.ROOT);
        Map<String, Long> mobs = breedTimes.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        Long last = mobs.get(key);
        if (last != null && now - last < cooldownMillis) {
            return false;
        }
        mobs.put(key, now);
        dirty = true;
        return true;
    }

    public long mobCooldownRemaining(UUID player, String mobKey, long cooldownMillis, long now) {
        if (cooldownMillis <= 0L) return 0L;
        String key = mobKey == null ? "" : mobKey.toLowerCase(java.util.Locale.ROOT);
        Long last = breedTimes.getOrDefault(player, Map.of()).get(key);
        if (last == null) return 0L;
        return Math.max(0L, cooldownMillis - (now - last));
    }

    // ==================== persistence ====================

    public boolean isDirty() {
        return dirty;
    }

    public synchronized void save() {
        if (!dirty) return;
        YamlConfiguration yaml = new YamlConfiguration();
        advancements.forEach((player, claimed) ->
            yaml.set("advancements." + player, new java.util.ArrayList<>(claimed)));
        breedTimes.forEach((player, mobs) -> {
            ConfigurationSection section = yaml.createSection("breed." + player);
            mobs.forEach((mob, at) -> section.set(mob, at));
        });
        sourceTimes.forEach((player, sources) -> {
            ConfigurationSection section = yaml.createSection("sources." + player);
            sources.forEach((id, at) -> section.set(id, at));
        });
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            // Written to a sibling and moved into place, so a crash mid-write
            // cannot leave a half-written file that silently re-opens every
            // gate the store exists to close.
            File temp = new File(parent, FILE_NAME + ".tmp");
            yaml.save(temp);
            if (!temp.renameTo(file)) {
                yaml.save(file);
                temp.deleteOnExit();
            }
            dirty = false;
        } catch (Exception e) {
            // The in-memory state is still correct for this session; a failed
            // write means the gates re-open on restart, which is worse than an
            // error but not a crash.
            dirty = true;
        }
    }

    /**
     * Reads the file back.
     *
     * <p>Two shapes are written, and both must be read: advancements are a
     * plain list under each player, while the cooldowns are a section of
     * key/timestamp pairs. Reading a list as a section silently yields null
     * for every player, which would drop every advancement claim on load and
     * re-open that gate on each restart — a failure with no symptom beyond the
     * gate quietly not existing.
     */
    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection advanced = yaml.getConfigurationSection("advancements");
        if (advanced != null) {
            for (String key : advanced.getKeys(false)) {
                UUID player = parsePlayer(key);
                if (player == null) continue;
                Set<String> claimed = ConcurrentHashMap.newKeySet();
                claimed.addAll(advanced.getStringList(key));
                advancements.put(player, claimed);
            }
        }
        readTimedSection(yaml.getConfigurationSection("breed"), breedTimes);
        readTimedSection(yaml.getConfigurationSection("sources"), sourceTimes);
    }

    private static UUID parsePlayer(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            // A corrupt key is skipped rather than aborting the load: the rest
            // of the file still closes gates for real players.
            return null;
        }
    }

    private static void readTimedSection(ConfigurationSection root,
                                         Map<UUID, Map<String, Long>> target) {
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            UUID player = parsePlayer(key);
            if (player == null) continue;
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            Map<String, Long> times = new ConcurrentHashMap<>();
            for (String id : section.getKeys(false)) {
                times.put(id, section.getLong(id));
            }
            target.put(player, times);
        }
    }

    /** Drops a player's state entirely, for a data reset. */
    public void forget(UUID player) {
        advancements.remove(player);
        breedTimes.remove(player);
        sourceTimes.remove(player);
        dirty = true;
    }

    public int trackedPlayers() {
        Set<UUID> all = new HashSet<>(advancements.keySet());
        all.addAll(breedTimes.keySet());
        all.addAll(sourceTimes.keySet());
        return all.size();
    }

    public File file() {
        return file;
    }

    static Map<String, Long> emptyMap() {
        return new HashMap<>();
    }
}
