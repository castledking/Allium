package codes.castled.curios;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for Curios item and chat hover frames, combining:
 * <ul>
 *   <li>Bundled frames declared in {@code config.yml} ({@code frames:}),</li>
 *   <li>Optional custom frames authored in {@code frames.yml} and built into {@code frames-built.json},</li>
 *   <li>Admin overrides persisted in {@code frames-overrides.yml} ({@code /frame set|reset}), and</li>
 *   <li>Player choices persisted in {@code frames-choices.yml} ({@code /frame choose|reset}).</li>
 * </ul>
 *
 * <p>Reads are lock-free through an immutable {@link Snapshot} reference so PacketEvents Netty
 * threads and Folia region threads can resolve frames safely without blocking.
 */
final class FrameRegistry {

    static final String MANIFEST_FILE = "frames-built.json";
    static final String OVERRIDES_FILE = "frames-overrides.yml";
    static final String CHOICES_FILE = "frames-choices.yml";

    enum Source {
        ADMIN_OVERRIDE,
        PLAYER_CHOICE,
        PERMISSION_DEFAULT,
        FALLBACK
    }

    record Resolution(String frame, Source source) {}

    private record Snapshot(
            CustomFramesConfig customConfig,
            FramePanels panels,
            Map<String, String> permissions,
            Set<String> customNames,
            String fallback
    ) {}

    private final File dataFolder;
    private final Map<UUID, String> overrides = new ConcurrentHashMap<>();
    private final Map<UUID, String> choices = new ConcurrentHashMap<>();
    private final Object saveLock = new Object();
    private volatile Snapshot snapshot = new Snapshot(
            CustomFramesConfig.empty(), FramePanels.bundled(), Map.of(), Set.of(), CustomFramesConfig.DEFAULT_FALLBACK);

    FrameRegistry(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    /**
     * Loads {@code config.yml}'s {@code frames:} section, optional {@code frames.yml},
     * {@code frames-built.json}, {@code frames-overrides.yml}, and {@code frames-choices.yml}.
     *
     * @return non-fatal problems encountered while loading custom authoring files
     */
    List<String> reload(ConfigurationSection configFrames) {
        List<String> problems = new ArrayList<>();

        Map<String, String> basePermissions = new LinkedHashMap<>();
        if (configFrames != null) {
            for (String key : configFrames.getKeys(false)) {
                String name = CustomFramesConfig.normalizeName(key);
                if (!CustomFramesConfig.VALID_NAME.matcher(name).matches()
                        || name.equals("auto") || name.equals("off")) {
                    throw new IllegalArgumentException("Invalid frame: " + key);
                }
                basePermissions.put(name, configFrames.getString(key, "").trim());
            }
        }

        CustomFramesConfig customConfig = CustomFramesConfig.empty();
        if (dataFolder != null) {
            File customFile = new File(dataFolder, CustomFramesConfig.FILE);
            if (customFile.isFile()) {
                customConfig = CustomFramesConfig.load(YamlConfiguration.loadConfiguration(customFile), problems);
            }
        }

        JsonObject builtManifest = null;
        if (dataFolder != null) {
            File manifestFile = new File(dataFolder, MANIFEST_FILE);
            if (manifestFile.isFile()) {
                try {
                    builtManifest = JsonParser.parseString(Files.readString(manifestFile.toPath())).getAsJsonObject();
                } catch (IOException | RuntimeException e) {
                    problems.add("Could not read " + MANIFEST_FILE + ": " + e.getMessage());
                }
            }
        }

        Map<String, String> aliases = new LinkedHashMap<>();
        for (CustomFramesConfig.Entry entry : customConfig.frames().values()) {
            if (entry.isAlias()) {
                aliases.put(entry.name(), entry.aliasTarget());
            }
        }
        FramePanels panels = FramePanels.bundled().withCustom(builtManifest, aliases);

        for (CustomFramesConfig.Entry entry : customConfig.frames().values()) {
            if (entry.isSprite() && !panels.draws(entry.name())) {
                problems.add("Frame '" + entry.name() + "' is not built yet; run /frame build");
            } else if (entry.isAlias() && !panels.draws(entry.name())) {
                problems.add("Frame '" + entry.name() + "' aliases unknown or cyclic target '" + entry.aliasTarget() + "'");
            }
        }

        // Custom frames take priority order ahead of bundled config frames, matching legacy frames.yml.
        Map<String, String> mergedPermissions = new LinkedHashMap<>();
        for (CustomFramesConfig.Entry entry : customConfig.frames().values()) {
            mergedPermissions.put(entry.name(), entry.permission());
        }
        for (var entry : basePermissions.entrySet()) {
            mergedPermissions.putIfAbsent(entry.getKey(), entry.getValue());
        }

        String fallback = customConfig.fallback();
        if (!panels.draws(fallback)) {
            problems.add("Fallback frame '" + fallback + "' cannot be drawn; using '" + CustomFramesConfig.DEFAULT_FALLBACK + "'");
            fallback = CustomFramesConfig.DEFAULT_FALLBACK;
        }

        if (dataFolder != null) {
            reloadMap(new File(dataFolder, OVERRIDES_FILE), overrides, problems);
            reloadMap(new File(dataFolder, CHOICES_FILE), choices, problems);
        }

        snapshot = new Snapshot(
                customConfig,
                panels,
                Collections.unmodifiableMap(mergedPermissions),
                Collections.unmodifiableSet(customConfig.frames().keySet()),
                fallback
        );
        return problems;
    }

    FramePanels panels() {
        return snapshot.panels();
    }

    CustomFramesConfig customConfig() {
        return snapshot.customConfig();
    }

    Map<String, String> permissions() {
        return snapshot.permissions();
    }

    Set<String> customNames() {
        return snapshot.customNames();
    }

    String fallback() {
        return snapshot.fallback();
    }

    /** True when {@code name} is registered and can be drawn right now. */
    boolean isDrawable(String name) {
        String normalized = CustomFramesConfig.normalizeName(name);
        Snapshot current = snapshot;
        return current.permissions().containsKey(normalized) && current.panels().draws(normalized);
    }

    /** True when {@code player} is permitted to use {@code name} and it can be drawn. */
    boolean canUse(Player player, String name) {
        if (player == null) return false;
        String normalized = CustomFramesConfig.normalizeName(name);
        Snapshot current = snapshot;
        String permission = current.permissions().get(normalized);
        if (permission == null || !current.panels().draws(normalized)) return false;
        return permission.isEmpty() || player.hasPermission(permission);
    }

    /** All drawable frame names usable by {@code player}, in priority order. */
    List<String> usableNames(Player player) {
        Snapshot current = snapshot;
        List<String> out = new ArrayList<>();
        for (String name : current.permissions().keySet()) {
            if (player == null) {
                if (current.panels().draws(name)) out.add(name);
            } else if (canUse(player, name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** All registered drawable frame names, in priority order. */
    List<String> drawableNames() {
        Snapshot current = snapshot;
        List<String> out = new ArrayList<>();
        for (String name : current.permissions().keySet()) {
            if (current.panels().draws(name)) out.add(name);
        }
        return out;
    }

    /** Resolves a player's active automatic frame and the reason it was selected. */
    Resolution resolve(Player player) {
        Snapshot current = snapshot;
        if (player == null) {
            return new Resolution(current.fallback(), Source.FALLBACK);
        }
        UUID uuid = player.getUniqueId();
        String override = overrides.get(uuid);
        if (override != null && current.permissions().containsKey(override) && current.panels().draws(override)) {
            return new Resolution(override, Source.ADMIN_OVERRIDE);
        }
        String choice = choices.get(uuid);
        if (choice != null && canUse(player, choice)) {
            return new Resolution(choice, Source.PLAYER_CHOICE);
        }
        for (var entry : current.permissions().entrySet()) {
            String perm = entry.getValue();
            if (!perm.isEmpty() && player.hasPermission(perm) && current.panels().draws(entry.getKey())) {
                return new Resolution(entry.getKey(), Source.PERMISSION_DEFAULT);
            }
        }
        return new Resolution(current.fallback(), Source.FALLBACK);
    }

    String automaticFrame(Player player) {
        return resolve(player).frame();
    }

    String overrideFor(UUID player) {
        return overrides.get(player);
    }

    String choiceFor(UUID player) {
        return choices.get(player);
    }

    /** Sets or clears ({@code frame == null}) an admin override and persists {@code frames-overrides.yml}. */
    void setOverride(UUID player, String frame) throws IOException {
        synchronized (saveLock) {
            if (frame == null) overrides.remove(player);
            else overrides.put(player, CustomFramesConfig.normalizeName(frame));
            saveMap(OVERRIDES_FILE, overrides);
        }
    }

    /** Sets or clears ({@code frame == null}) a player's personal frame choice and persists {@code frames-choices.yml}. */
    void setChoice(UUID player, String frame) throws IOException {
        synchronized (saveLock) {
            if (frame == null) choices.remove(player);
            else choices.put(player, CustomFramesConfig.normalizeName(frame));
            saveMap(CHOICES_FILE, choices);
        }
    }

    private void reloadMap(File file, Map<UUID, String> target, List<String> problems) {
        Map<UUID, String> loaded = new LinkedHashMap<>();
        if (file.isFile()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String value = CustomFramesConfig.normalizeName(yaml.getString(key));
                    if (!value.isEmpty()) loaded.put(uuid, value);
                } catch (IllegalArgumentException ignored) {
                    problems.add(file.getName() + ": '" + key + "' is not a player UUID");
                }
            }
        }
        target.clear();
        target.putAll(loaded);
    }

    private void saveMap(String fileName, Map<UUID, String> source) throws IOException {
        if (dataFolder == null) return;
        Path dir = dataFolder.toPath();
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        Path temp = dir.resolve(fileName + ".tmp");
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : source.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        yaml.save(temp.toFile());
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
