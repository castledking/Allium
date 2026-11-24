package codes.castled.ores;

import codes.castled.allium.api.AddonCommands;
import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AlliumAddon;
import codes.castled.allium.command.spec.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Ores: legacy Allium's custom ore generation and /pregen as an addon. Ores are real CraftEngine
 * blocks, placed in fresh chunks of the configured worlds or by /pregen in existing ones.
 */
public final class Ores extends AlliumAddon {
    private static final List<String> ACTIONS = List.of("start", "world", "pause", "resume", "stop", "status", "reload");

    private AddonConnection core;
    private OreGeneration generation;
    private FreshChunks fresh;
    private AutoCloseable jade;

    @Override
    protected void onAddonEnable(AddonConnection connection) {
        core = connection;
        File config = new File(getDataFolder(), "config.yml");
        if (!config.exists()) importLegacySettings(config);
        saveDefaultConfig();
        reloadConfig();
        Settings settings = Settings.read(getConfig(), message -> getLogger().warning(message));
        generation = new OreGeneration(this, core, placement(), settings);
        startListeners();
        core.commands().register(new CommandSpec("pregen", "ores", "ores", List.of(), "Pre-generates chunks with the configured ores",
                usage(), usage(), "allium.pregen.status", null,
                List.of(CommandArgSpec.optional("action", null, SuggestionSpec.literals(ACTIONS, null))),
                List.of(), CommandMessageKeys.ofOutcomes(Map.of())), this::pregen, this::complete);
        getLogger().info("Loaded " + settings.ores().size() + " ore(s); generation is " + (settings.enabled() ? "on" : "off")
                + (generation.placement() == null ? "; CraftEngine is not installed, so no ores are placed." : "."));
    }

    @Override
    protected void onAddonDisable() {
        stopListeners();
        if (generation != null) generation.close();
    }

    private OrePlacement placement() {
        if (!getServer().getPluginManager().isPluginEnabled("CraftEngine")) return null;
        try {
            return new CraftEngineOres(getLogger());
        } catch (LinkageError incompatible) {
            getLogger().warning("This CraftEngine version is not supported: " + incompatible);
            return null;
        }
    }

    private void startListeners() {
        fresh = new FreshChunks(generation, core.scheduler());
        getServer().getPluginManager().registerEvents(fresh, this);
        fresh.scanLoadedWorlds(getServer().getWorlds());
        if (generation.placement() != null && generation.settings().jade()
                && getServer().getPluginManager().isPluginEnabled("packetevents")) {
            try {
                if (JadeOreOverlay.supported()) jade = new JadeOreOverlay(this, core, generation);
            } catch (LinkageError | RuntimeException unavailable) {
                getLogger().warning("Jade's ore overlay is unavailable: " + unavailable);
            }
        }
    }

    private void stopListeners() {
        if (fresh != null) fresh.close();
        fresh = null;
        if (jade != null) {
            try {
                jade.close();
            } catch (Exception failed) {
                getLogger().warning("Could not stop Jade's ore overlay: " + failed.getMessage());
            }
        }
        jade = null;
    }

    /** Copies legacy Allium's ore settings into a new config.yml, when there are any. */
    private void importLegacySettings(File config) {
        var allium = getServer().getPluginManager().getPlugin("Allium");
        if (allium == null) return;
        List<File> candidates = core != null
                ? core.legacyConfigSources().stream().map(java.nio.file.Path::toFile).toList()
                : List.of();
        if (candidates.isEmpty()) candidates = legacySources(allium.getDataFolder());
        for (File source : candidates) {
            String name = allium.getDataFolder().toPath().relativize(source.toPath()).toString();
            try {
                var legacy = new YamlConfiguration();
                legacy.load(source);
                if (!LegacyOres.present(legacy)) continue;
                var target = new YamlConfiguration();
                try (var reader = new InputStreamReader(Objects.requireNonNull(getResource("config.yml")), StandardCharsets.UTF_8)) {
                    target.load(reader);
                }
                List<String> written = LegacyOres.apply(legacy, target);
                getDataFolder().mkdirs();
                target.save(config);
                getLogger().info("Took " + written.size() + " ore setting(s) from plugins/Allium/" + name + " into config.yml.");
                return;
            } catch (IOException | InvalidConfigurationException unreadable) {
                getLogger().warning("Could not read plugins/Allium/" + name + " for ore settings: " + unreadable.getMessage());
            }
        }
    }

    /**
     * Legacy Allium's config, newest first: the copy Allium keeps after carrying it over, the same
     * copy after /allium import tidy archived it, then a config.yml that was never carried over.
     */
    static List<File> legacySources(File allium) {
        List<File> found = new java.util.ArrayList<>();
        File kept = new File(allium, "config.legacy.yml");
        if (kept.isFile()) found.add(kept);
        File[] archives = new File(allium, "import/backups").listFiles(file -> file.isDirectory() && file.getName().startsWith("tidy-"));
        if (archives != null) {
            java.util.Arrays.sort(archives, java.util.Comparator.comparing(File::getName).reversed());
            for (File archive : archives) {
                File archived = new File(archive, "config.legacy.yml");
                if (archived.isFile()) found.add(archived);
            }
        }
        File config = new File(allium, "config.yml");
        if (config.isFile()) found.add(config);
        return found;
    }

    private static String usage() {
        return "/pregen <start|world|pause|resume|stop|status|reload>";
    }

    private void tell(CommandSender sender, String message) {
        core.text().send(sender, message);
    }

    private void pregen(AddonCommands.Context ctx) {
        CommandSender sender = ctx.sender();
        String[] args = ctx.args();
        if (args.length == 0) {
            help(sender);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("allium.pregen." + action) && !sender.hasPermission("allium.pregen")) {
            tell(sender, "&cYou do not have permission to use this command.");
            return;
        }
        try {
            switch (action) {
                case "start", "world" -> start(sender, args, action.equals("world"));
                case "pause" -> {
                    generation.pauseGeneration();
                    tell(sender, generation.isPaused() ? "&ePre-generation paused." : "&7No pre-generation is running.");
                }
                case "resume" -> {
                    generation.resumeGeneration();
                    tell(sender, generation.isGenerating() ? "&aPre-generation resumed." : "&7No pre-generation is running.");
                }
                case "stop" -> {
                    generation.stopGeneration();
                    tell(sender, "&ePre-generation stopped.");
                }
                case "status" -> tell(sender, "&6Ore pre-generation: &f"
                        + (generation.isGenerating() ? (generation.isPaused() ? "paused" : "running") : "idle")
                        + " &7| " + generation.worldName() + " | " + generation.generatedChunks() + "/" + generation.totalChunks()
                        + " chunks | " + generation.generatedOres() + " ores | CraftEngine blocks "
                        + (generation.ready() ? "ready" : "missing or off"));
                case "reload" -> reload(sender);
                default -> help(sender);
            }
        } catch (IllegalArgumentException invalid) {
            tell(sender, "&c" + invalid.getMessage());
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("allium.pregen")) {
            tell(sender, "&cYou do not have permission to use this command.");
            return;
        }
        reloadConfig();
        stopListeners();
        generation.reload(Settings.read(getConfig(), message -> tell(sender, "&e" + message)));
        startListeners();
        tell(sender, "&aReloaded Ores: &f" + generation.definitions().size() + " &aore(s).");
    }

    private void start(CommandSender sender, String[] args, boolean explicit) {
        if (args.length < 3 || explicit && args.length == 4) {
            help(sender);
            return;
        }
        World world = Bukkit.getWorld(args[explicit ? 1 : 2]);
        if (world == null) throw new IllegalArgumentException("That world is not loaded.");
        int radius, x, z;
        try {
            radius = Integer.parseInt(args[explicit ? 2 : 1]);
            var center = sender instanceof Player player && player.getWorld().equals(world) && !explicit
                    ? player.getLocation() : world.getSpawnLocation();
            x = explicit && args.length >= 5 ? Integer.parseInt(args[3]) : center.getBlockX() >> 4;
            z = explicit && args.length >= 5 ? Integer.parseInt(args[4]) : center.getBlockZ() >> 4;
        } catch (NumberFormatException notNumbers) {
            throw new IllegalArgumentException("The radius and chunk coordinates must be whole numbers.");
        }
        var future = generation.startGeneration(world, x, z, radius);
        if (!future.isCompletedExceptionally()) {
            tell(sender, "&aPre-generating &f" + world.getName() + " &7around chunk " + x + ", " + z + " | "
                    + generation.totalChunks() + " chunks.");
        }
        future.whenComplete((ignored, error) -> core.runForSender(sender, () -> {
            if (!isEnabled()) return;
            Throwable cause = error;
            while (cause != null && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof CancellationException) return;
            if (sender instanceof Player player && !player.isOnline()) return;
            tell(sender, cause == null
                    ? "&aPre-generation finished: &f" + generation.generatedChunks() + " chunks, " + generation.generatedOres() + " ores."
                    : "&cPre-generation failed: " + cause.getMessage());
        }, () -> {}, 0L));
    }

    private void help(CommandSender sender) {
        tell(sender, "&6/pregen start <radius-in-chunks> <world>");
        tell(sender, "&6/pregen world <world> <radius-in-chunks> [center-chunk-x center-chunk-z]");
        tell(sender, "&6/pregen pause|resume|stop|status|reload");
        tell(sender, "&7Places the configured CraftEngine ores in the chunks it generates.");
    }

    private List<String> complete(AddonCommands.Context ctx) {
        CommandSender sender = ctx.sender();
        String[] args = ctx.args();
        if (!sender.hasPermission("allium.pregen") && !sender.hasPermission("allium.pregen.status")) return List.of();
        List<String> options = args.length <= 1 ? ACTIONS
                : args.length == 2 && args[0].equalsIgnoreCase("world") || args.length == 3 && args[0].equalsIgnoreCase("start")
                ? Bukkit.getWorlds().stream().map(World::getName).toList() : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
