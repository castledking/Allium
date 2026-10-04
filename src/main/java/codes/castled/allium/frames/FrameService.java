package codes.castled.allium.frames;

import codes.castled.allium.tradingcards.card.Tier;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

/**
 * Which frame a name, or a player, comes out as.
 *
 * <p>{@code auto} is a player's frame: one set for them with {@code /frame set},
 * else the first frame in frames.yml whose permission they have, else the
 * fallback. Each frame's permission is registered as default-false, so an
 * operator does not land on the first frame in the file just for being one.
 *
 * <p>Held statically, because the packet listener that draws frames on items
 * and hovers runs on netty threads with no other way to reach it. A server
 * without frames.yml still gets the five trading card frames.
 */
public final class FrameService {

    public static final String MANIFEST = "frames-built.json";
    public static final String OVERRIDES = "frames-overrides.yml";

    private static final Pattern AUTO =
        Pattern.compile("[\\[<]frame(?::\\s*auto\\s*)?[\\]>]", Pattern.CASE_INSENSITIVE);

    private static volatile FrameService instance =
        new FrameService(null, FramesConfig.empty(), Map.of(), new ConcurrentHashMap<>());

    private final File dataFolder;
    private final FramesConfig config;
    private final Map<String, LineFrame> built;
    private final Map<UUID, String> overrides;

    private FrameService(File dataFolder, FramesConfig config, Map<String, LineFrame> built,
                         Map<UUID, String> overrides) {
        this.dataFolder = dataFolder;
        this.config = config;
        this.built = built;
        this.overrides = overrides;
    }

    public static FrameService get() {
        return instance;
    }

    /**
     * Loads frames.yml, the built frames and the overrides.
     *
     * @return problems worth telling whoever ran the reload
     */
    public static List<String> load(Plugin plugin) {
        List<String> problems = new ArrayList<>();
        File folder = plugin.getDataFolder();
        File file = new File(folder, FramesConfig.FILE);
        if (!file.exists()) {
            try (InputStream in = plugin.getResource(FramesConfig.FILE)) {
                if (in != null) {
                    folder.mkdirs();
                    Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                problems.add("Could not write the default frames.yml: " + e.getMessage());
            }
        }
        FramesConfig config = FramesConfig.load(YamlConfiguration.loadConfiguration(file), problems);

        Map<String, LineFrame> built = new HashMap<>();
        File manifest = new File(folder, MANIFEST);
        if (manifest.isFile()) {
            try {
                JsonObject root = new JsonParser().parse(Files.readString(manifest.toPath())).getAsJsonObject();
                JsonObject frames = root.getAsJsonObject("frames");
                for (var e : frames.entrySet()) {
                    built.put(e.getKey(), new SpriteFrame(root, e.getValue().getAsJsonObject()));
                }
            } catch (IOException | RuntimeException e) {
                problems.add("Could not read " + MANIFEST + ": " + e.getMessage());
            }
        }
        for (FramesConfig.Entry entry : config.spriteFrames()) {
            if (!built.containsKey(entry.name())) {
                problems.add("Frame '" + entry.name() + "' is not built yet; run /frame build");
            }
        }

        Map<UUID, String> overrides = new ConcurrentHashMap<>();
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(new File(folder, OVERRIDES));
        for (String key : saved.getKeys(false)) {
            try {
                overrides.put(UUID.fromString(key), FramesConfig.name(saved.getString(key)));
            } catch (IllegalArgumentException ignored) {
                problems.add(OVERRIDES + ": '" + key + "' is not a player id");
            }
        }

        var pm = Bukkit.getPluginManager();
        for (FramesConfig.Entry entry : config.frames().values()) {
            if (pm.getPermission(entry.permission()) == null) {
                pm.addPermission(new Permission(entry.permission(),
                    "Gives the '" + entry.name() + "' lore frame to [frame:auto]", PermissionDefault.FALSE));
            }
        }
        instance = new FrameService(folder, config, built, overrides);
        return problems;
    }

    public FramesConfig config() {
        return config;
    }

    public File dataFolder() {
        return dataFolder;
    }

    /** True for any name a marker can use: a frames.yml entry or a card tier. */
    public boolean exists(String name) {
        String n = FramesConfig.name(name);
        return config.frames().containsKey(n) || tier(n) != null;
    }

    /** Every name a marker can use, frames.yml first. */
    public List<String> names() {
        List<String> out = new ArrayList<>(config.frames().keySet());
        for (Tier t : Tier.values()) {
            String n = t.name().toLowerCase(Locale.ROOT);
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }

    /** The frame {@code auto} gives this player. */
    public String resolve(Player player) {
        if (player == null) {
            return config.fallback();
        }
        String override = overrides.get(player.getUniqueId());
        if (override != null && exists(override)) {
            return override;
        }
        for (FramesConfig.Entry entry : config.frames().values()) {
            if (player.hasPermission(entry.permission())) {
                return entry.name();
            }
        }
        return config.fallback();
    }

    /** The player's {@code /frame set} override, or null. */
    public String override(UUID player) {
        return overrides.get(player);
    }

    /**
     * The frame a marker names, for this player when it says {@code auto}. An
     * unknown or unbuilt name falls back rather than leaving the lore unframed,
     * since the marker line itself would otherwise show.
     */
    public LineFrame frame(String name, Player player) {
        String n = FramesConfig.name(name);
        if (n.isEmpty() || n.equals("auto")) {
            n = resolve(player);
        }
        LineFrame frame = lookup(n);
        if (frame == null) frame = lookup(config.fallback());
        return frame != null ? frame : new CardTierFrame(Tier.SIMPLE);
    }

    private LineFrame lookup(String name) {
        FramesConfig.Entry entry = config.frames().get(name);
        if (entry != null) {
            if (!entry.sprite()) {
                Tier t = tier(entry.card());
                return t == null ? null : new CardTierFrame(t);
            }
            return built.get(name);
        }
        Tier t = tier(name);
        return t == null ? null : new CardTierFrame(t);
    }

    /**
     * Swaps {@code [frame:auto]} in text for the player's frame, for text that
     * knows whose it is, like a chat hover written for its sender.
     */
    public String substituteAuto(String text, Player player) {
        if (text == null || text.indexOf('f') < 0) return text;
        Matcher m = AUTO.matcher(text);
        if (!m.find()) return text;
        return m.replaceAll(Matcher.quoteReplacement("[frame:" + resolve(player) + "]"));
    }

    /** Sets or clears ({@code frame} null) a player's override, and saves it. */
    public void setOverride(UUID player, String frame) throws IOException {
        if (frame == null) overrides.remove(player);
        else overrides.put(player, FramesConfig.name(frame));
        if (dataFolder == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        overrides.forEach((id, f) -> yaml.set(id.toString(), f));
        yaml.save(new File(dataFolder, OVERRIDES));
    }

    private static Tier tier(String name) {
        if (name == null) return null;
        try {
            return Tier.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
