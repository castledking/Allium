package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

final class LegacyOfflineTeleport implements Listener {
    private static final String PERMISSION = "allium.tp.offline";
    private static final String SEE_VANISHED = "allium.vanish.see";
    private static final Set<String> HAZARDS = new HashSet<>(Arrays.asList("LAVA", "STATIONARY_LAVA",
            "FIRE", "SOUL_FIRE", "CACTUS", "MAGMA", "MAGMA_BLOCK", "POWDER_SNOW",
            "SWEET_BERRY_BUSH", "CAMPFIRE", "SOUL_CAMPFIRE", "WATER", "STATIONARY_WATER"));

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    private final LegacyModerationModule moderation;
    private final LegacyMessageManager messages;
    private final BiPredicate<Player, Player> visible;
    private final File file;
    private YamlConfiguration positions = new YamlConfiguration();
    private boolean writable;

    LegacyOfflineTeleport(JavaPlugin plugin, LegacyConfigManager config, LegacyModerationModule moderation,
                          LegacyMessageManager messages, BiPredicate<Player, Player> visible) {
        this.plugin = plugin;
        this.config = config;
        this.moderation = moderation;
        this.messages = messages;
        this.visible = visible;
        this.file = new File(plugin.getDataFolder(), "logout-locations-legacy.yml");
    }

    void reload() {
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            if (file.exists()) loaded.load(file);
            positions = loaded;
            writable = true;
        } catch (IOException | InvalidConfigurationException error) {
            writable = false;
            plugin.getLogger().warning("Could not load " + file.getName() + ": " + error.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        if (!writable || !config.isModuleEnabled("teleport")) return;
        Player player = event.getPlayer();
        if (!validName(player.getName())) return;
        Location position = player.getLocation();
        if (position == null || position.getWorld() == null) return;
        String key = player.getUniqueId().toString();
        ConfigurationSection previous = positions.getConfigurationSection(key);
        Map<String, Object> before = previous == null ? Collections.emptyMap() : previous.getValues(false);
        positions.createSection(key);
        positions.set(key + ".name", player.getName());
        positions.set(key + ".hidden", vanished(player));
        positions.set(key + ".world", position.getWorld().getUID().toString());
        positions.set(key + ".quit-at", System.currentTimeMillis());
        positions.set(key + ".x", position.getX());
        positions.set(key + ".y", position.getY());
        positions.set(key + ".z", position.getZ());
        positions.set(key + ".yaw", (double) position.getYaw());
        positions.set(key + ".pitch", (double) position.getPitch());
        try {
            LegacyCompat.saveYamlAtomically(positions, file);
        } catch (IOException error) {
            if (before.isEmpty()) positions.set(key, null);
            else positions.createSection(key, before);
            plugin.getLogger().warning("Could not save logout location for " + key + ": " + error.getMessage());
        }
    }

    boolean execute(CommandSender sender, String[] args) {
        if (args.length != 1) return false;
        if (!(sender instanceof Player) || !sender.hasPermission(PERMISSION)) {
            messages.sendPrefixed(sender, ChatColor.RED + "Only players with permission can use offline teleport.");
            return true;
        }
        Player mover = (Player) sender;
        String name = args[0];
        if (!validName(name)) return unavailable(mover);
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            if (!visible.test(mover, online)) return unavailable(mover);
            if (move(mover, online.getLocation())) messages.sendPrefixed(mover, ChatColor.GREEN + "Teleported to " + online.getName() + ".");
            return true;
        }
        ConfigurationSection newest = null;
        for (String key : positions.getKeys(false)) {
            ConfigurationSection candidate = positions.getConfigurationSection(key);
            if (candidate != null && name.equalsIgnoreCase(candidate.getString("name"))
                    && (newest == null || candidate.getLong("quit-at") > newest.getLong("quit-at"))) {
                newest = candidate;
            }
        }
        if (newest == null || (newest.getBoolean("hidden") && !mover.hasPermission(SEE_VANISHED))) {
            return unavailable(mover);
        }
        UUID target = parseUuid(newest.getName());
        UUID worldId = parseUuid(newest.getString("world"));
        if (target == null || worldId == null || plugin.getServer().getPlayer(target) != null) return unavailable(mover);
        World world = plugin.getServer().getWorld(worldId);
        if (world == null) return unavailable(mover);
        Location destination = new Location(world, newest.getDouble("x", Double.NaN), newest.getDouble("y", Double.NaN),
                newest.getDouble("z", Double.NaN), (float) newest.getDouble("yaw"), (float) newest.getDouble("pitch"));
        if (move(mover, destination)) {
            plugin.getLogger().info("Offline teleport audit actor=" + mover.getUniqueId() + " target=" + target
                    + " world=" + worldId);
            messages.send(mover, "teleport.offline-success", "{prefix} &aTeleported to {target}'s last saved logout location.",
                    "{target}", newest.getString("name", name));
        }
        return true;
    }

    List<String> complete(CommandSender sender, String prefix) {
        if (!sender.hasPermission(PERMISSION)) return Collections.emptyList();
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (sender instanceof Player) {
            Player mover = (Player) sender;
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (visible.test(mover, online)) names.add(online.getName());
            }
        }
        boolean seeVanished = sender.hasPermission(SEE_VANISHED);
        for (String key : positions.getKeys(false)) {
            ConfigurationSection position = positions.getConfigurationSection(key);
            if (position == null || (position.getBoolean("hidden") && !seeVanished)) continue;
            UUID player = parseUuid(key);
            String name = position.getString("name");
            if (player == null || !validName(name) || plugin.getServer().getPlayer(player) != null
                    || plugin.getServer().getPlayerExact(name) != null) continue;
            names.add(name);
        }
        return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(64)
                .collect(Collectors.toList());
    }

    private boolean move(Player mover, Location destination) {
        if (destination == null || destination.getWorld() == null || !inBounds(destination)
                || (!mover.hasPermission("allium.teleport.unsafe") && !safe(destination))) {
            messages.send(mover, "teleport.unsafe", "{prefix} &cThat teleport destination is unsafe.");
            return false;
        }
        if (mover.teleport(destination)) return true;
        messages.sendPrefixed(mover, ChatColor.RED + "The teleport was cancelled.");
        return false;
    }

    private static boolean inBounds(Location point) {
        World world = point.getWorld();
        return Math.abs(point.getX()) < 30_000_000 && Math.abs(point.getZ()) < 30_000_000
                && point.getY() >= minimumHeight(world) && point.getY() < world.getMaxHeight()
                && Float.isFinite(point.getYaw()) && Float.isFinite(point.getPitch());
    }

    private static boolean safe(Location point) {
        Material feet = point.getBlock().getType();
        Material head = point.clone().add(0, 1, 0).getBlock().getType();
        Material floor = point.clone().subtract(0, 1, 0).getBlock().getType();
        return !feet.isSolid() && !head.isSolid() && floor.isSolid()
                && !HAZARDS.contains(feet.name()) && !HAZARDS.contains(head.name()) && !HAZARDS.contains(floor.name());
    }

    private static int minimumHeight(World world) {
        try {
            Method minimum = World.class.getMethod("getMinHeight");
            return (Integer) minimum.invoke(world);
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            return 0;
        }
    }

    private boolean vanished(Player player) {
        if (moderation.isVanished(player.getUniqueId())) return true;
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) return true;
        }
        return false;
    }

    private boolean unavailable(Player mover) {
        messages.send(mover, "teleport.offline-unavailable", "{prefix} &cNo saved offline location is available for that player.");
        return true;
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.-]{1,32}");
    }

    private static UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
