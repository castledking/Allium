package codes.castled.allium.legacy;

import codes.castled.allium.portable.Homes;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Java 8 / 1.12.2-1.19.4 extended teleport, travel, and RTP module:
 * {@code /renamehome}, {@code /bed}, {@code /warpinfo}, {@code /tppos}, {@code /top}, {@code /bottom},
 * {@code /jump}, {@code /there}, {@code /tpo}, {@code /tpohere}, {@code /tpall}, {@code /tpaall},
 * {@code /tptoggle}, {@code /tpauto}, {@code /world}, {@code /rtp}, {@code /settpr}.
 */
final class LegacyTravelModule {

    private static final int DEFAULT_RTP_RADIUS = 2500;
    private static final int RTP_ATTEMPTS = 24;

    private final JavaPlugin plugin;
    private final LegacyBukkitRuntime runtime;
    private final Random random = new Random();
    private final Set<UUID> tpDisabled = new HashSet<UUID>();
    private final Set<UUID> tpAuto = new HashSet<UUID>();
    private final Map<String, Location> rtpCenters = new HashMap<String, Location>();

    LegacyTravelModule(JavaPlugin plugin, LegacyBukkitRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    void enable() {
        loadRtpCenters();
    }

    void reload() {
        loadRtpCenters();
    }

    void disable() {
        saveRtpCenters();
        tpDisabled.clear();
        tpAuto.clear();
    }

    boolean isTpDisabled(UUID id) {
        return tpDisabled.contains(id);
    }

    boolean isTpAuto(UUID id) {
        return tpAuto.contains(id);
    }

    boolean handles(String name) {
        switch (name) {
            case "renamehome":
            case "bed":
            case "warpinfo":
            case "tppos":
            case "top":
            case "bottom":
            case "jump":
            case "there":
            case "tpo":
            case "tpohere":
            case "tpall":
            case "tpaall":
            case "tptoggle":
            case "tpauto":
            case "world":
            case "rtp":
            case "settpr":
                return true;
            default:
                return false;
        }
    }

    boolean onCommand(CommandSender sender, String name, String[] args) {
        switch (name) {
            case "renamehome": return handleRenameHome(sender, args);
            case "bed": return handleBed(sender, args);
            case "warpinfo": return handleWarpInfo(sender, args);
            case "tppos": return handleTpPos(sender, args);
            case "top": return handleTop(sender);
            case "bottom": return handleBottom(sender);
            case "jump":
            case "there": return handleJump(sender);
            case "tpo": return handleTpo(sender, args, false);
            case "tpohere": return handleTpo(sender, args, true);
            case "tpall": return handleTpAll(sender, args);
            case "tpaall": return handleTpaAll(sender);
            case "tptoggle": return handleTpToggle(sender);
            case "tpauto": return handleTpAuto(sender);
            case "world": return handleWorld(sender, args);
            case "rtp": return handleRtp(sender, args);
            case "settpr": return handleSetTpr(sender);
            default: return false;
        }
    }

    List<String> onTabComplete(CommandSender sender, String name, String[] args, String prefix) {
        switch (name) {
            case "renamehome":
                if (args.length == 1 && sender instanceof Player) {
                    return runtime.homeSuggestions((Player) sender, prefix);
                }
                return Collections.emptyList();
            case "warpinfo":
                return args.length == 1 ? runtime.warpSuggestions(prefix) : Collections.<String>emptyList();
            case "bed":
            case "tpo":
            case "tpohere":
            case "tpall":
                return args.length == 1 ? onlinePlayers(prefix) : Collections.<String>emptyList();
            case "world":
                if (args.length == 1) return worldNames(prefix);
                if (args.length == 2) return onlinePlayers(prefix);
                return Collections.emptyList();
            case "rtp":
                return args.length == 1 ? worldNames(prefix) : Collections.<String>emptyList();
            case "tppos":
                return args.length == 4 ? worldNames(prefix) : Collections.<String>emptyList();
            default:
                return Collections.emptyList();
        }
    }

    private boolean handleRenameHome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + Homes.PLAYERS_ONLY);
            return true;
        }
        if (!sender.hasPermission("allium.renamehome") && !sender.hasPermission("allium.sethome")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to rename homes.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /renamehome <oldName> <newName>");
            return true;
        }
        final Player player = (Player) sender;
        runtime.homesFor(player).rename(args[0], args[1], new java.util.function.Consumer<String>() {
            @Override
            public void accept(String msg) {
                player.sendMessage(msg);
            }
        });
        return true;
    }

    private boolean handleBed(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.bed")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /bed.");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can teleport to a bed.");
            return true;
        }
        Player viewer = (Player) sender;
        Player owner = viewer;
        if (args.length >= 1) {
            if (!sender.hasPermission("allium.bed.others")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to teleport to another player's bed.");
                return true;
            }
            owner = plugin.getServer().getPlayerExact(args[0]);
            if (owner == null) owner = plugin.getServer().getPlayer(args[0]);
            if (owner == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online.");
                return true;
            }
        }
        Location bed = owner.getBedSpawnLocation();
        if (bed == null) {
            sender.sendMessage(ChatColor.RED + (owner.equals(viewer) ? "You have no bed spawn set." : owner.getName() + " has no bed spawn set."));
            return true;
        }
        viewer.teleport(bed);
        viewer.sendMessage(ChatColor.GREEN + "Teleported to " + (owner.equals(viewer) ? "your bed." : owner.getName() + "'s bed."));
        return true;
    }

    private boolean handleWarpInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.warpinfo") && !sender.hasPermission("allium.warp")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to view warp info.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /warpinfo <warp>");
            return true;
        }
        Location loc = runtime.getWarp(args[0]);
        if (loc == null) {
            sender.sendMessage(ChatColor.RED + "Warp '" + args[0] + "' does not exist.");
            return true;
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Warp '" + args[0].toLowerCase(Locale.ROOT) + "': "
                + ChatColor.WHITE + loc.getWorld().getName()
                + String.format(Locale.US, " (%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ()));
        return true;
    }

    private boolean handleTpPos(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /tppos.");
            return true;
        }
        if (!sender.hasPermission("allium.tppos")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /tppos.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /tppos <x> <y> <z> [world]");
            return true;
        }
        Player player = (Player) sender;
        double x, y, z;
        try {
            x = parseCoord(args[0], player.getLocation().getX());
            y = parseCoord(args[1], player.getLocation().getY());
            z = parseCoord(args[2], player.getLocation().getZ());
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Coordinates must be valid numbers.");
            return true;
        }
        World world = player.getWorld();
        if (args.length >= 4) {
            world = plugin.getServer().getWorld(args[3]);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world '" + args[3] + "'.");
                return true;
            }
        }
        Location dest = new Location(world, x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleport(dest);
        player.sendMessage(ChatColor.GREEN + String.format(Locale.US, "Teleported to %.1f, %.1f, %.1f in %s.", x, y, z, world.getName()));
        return true;
    }

    private boolean handleTop(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /top.");
            return true;
        }
        if (!sender.hasPermission("allium.top")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /top.");
            return true;
        }
        Player player = (Player) sender;
        Location loc = player.getLocation();
        int topY = loc.getWorld().getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        Location dest = new Location(loc.getWorld(), loc.getBlockX() + 0.5, topY + 1.0, loc.getBlockZ() + 0.5, loc.getYaw(), loc.getPitch());
        player.teleport(dest);
        player.sendMessage(ChatColor.GREEN + "Teleported to the highest surface block.");
        return true;
    }

    private boolean handleBottom(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /bottom.");
            return true;
        }
        if (!sender.hasPermission("allium.bottom")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /bottom.");
            return true;
        }
        Player player = (Player) sender;
        Location loc = player.getLocation();
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        for (int y = 1; y < Math.min(w.getMaxHeight() - 2, loc.getBlockY()); y++) {
            Block floor = w.getBlockAt(x, y, z);
            Block feet = w.getBlockAt(x, y + 1, z);
            Block head = w.getBlockAt(x, y + 2, z);
            if (floor.getType().isSolid() && !feet.getType().isSolid() && !head.getType().isSolid()) {
                player.teleport(new Location(w, x + 0.5, y + 1.0, z + 0.5, loc.getYaw(), loc.getPitch()));
                player.sendMessage(ChatColor.GREEN + "Teleported to the lowest safe spot.");
                return true;
            }
        }
        sender.sendMessage(ChatColor.RED + "No safe lower spot found below you.");
        return true;
    }

    private boolean handleJump(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /jump.");
            return true;
        }
        if (!sender.hasPermission("allium.jump")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /jump.");
            return true;
        }
        Player player = (Player) sender;
        Block target = player.getTargetBlock((Set<Material>) null, 120);
        if (target == null || target.getType() == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "No block in sight within 120 blocks.");
            return true;
        }
        Location dest = target.getLocation().add(0.5, 1.0, 0.5);
        dest.setYaw(player.getLocation().getYaw());
        dest.setPitch(player.getLocation().getPitch());
        player.teleport(dest);
        player.sendMessage(ChatColor.GREEN + "Jumped to target block.");
        return true;
    }

    private boolean handleTpo(CommandSender sender, String[] args, boolean here) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        String perm = here ? "allium.tpohere" : "allium.tpo";
        if (!sender.hasPermission(perm)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + (here ? "tpohere" : "tpo") + " <player>");
            return true;
        }
        Player self = (Player) sender;
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null) target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }
        if (here) {
            target.teleport(self.getLocation());
            self.sendMessage(ChatColor.GREEN + "Forced teleport of " + target.getName() + " to you.");
        } else {
            self.teleport(target.getLocation());
            self.sendMessage(ChatColor.GREEN + "Forced teleport to " + target.getName() + ".");
        }
        return true;
    }

    private boolean handleTpAll(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.tpall")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /tpall.");
            return true;
        }
        Player destPlayer;
        if (args.length >= 1) {
            destPlayer = plugin.getServer().getPlayerExact(args[0]);
            if (destPlayer == null) destPlayer = plugin.getServer().getPlayer(args[0]);
            if (destPlayer == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online.");
                return true;
            }
        } else if (sender instanceof Player) {
            destPlayer = (Player) sender;
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /tpall <player>");
            return true;
        }
        int count = 0;
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.equals(destPlayer)) continue;
            online.teleport(destPlayer.getLocation());
            online.sendMessage(ChatColor.GREEN + "Teleported to " + destPlayer.getName() + ".");
            count++;
        }
        sender.sendMessage(ChatColor.GREEN + "Teleported " + count + " player(s) to " + destPlayer.getName() + ".");
        return true;
    }

    private boolean handleTpaAll(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /tpaall.");
            return true;
        }
        if (!sender.hasPermission("allium.tpaall")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /tpaall.");
            return true;
        }
        Player self = (Player) sender;
        int sent = 0;
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.equals(self) || tpDisabled.contains(online.getUniqueId())) continue;
            runtime.sendTpaRequest(self, online, true);
            sent++;
        }
        sender.sendMessage(ChatColor.GREEN + "Sent teleport requests to " + sent + " player(s).");
        return true;
    }

    private boolean handleTpToggle(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can toggle teleports.");
            return true;
        }
        if (!sender.hasPermission("allium.tptoggle")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /tptoggle.");
            return true;
        }
        Player p = (Player) sender;
        if (tpDisabled.remove(p.getUniqueId())) {
            sender.sendMessage(ChatColor.GREEN + "Teleport requests enabled.");
        } else {
            tpDisabled.add(p.getUniqueId());
            sender.sendMessage(ChatColor.GRAY + "Teleport requests disabled.");
        }
        return true;
    }

    private boolean handleTpAuto(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can toggle auto-accept.");
            return true;
        }
        if (!sender.hasPermission("allium.tpauto")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /tpauto.");
            return true;
        }
        Player p = (Player) sender;
        if (tpAuto.remove(p.getUniqueId())) {
            sender.sendMessage(ChatColor.GRAY + "Auto-accepting teleport requests disabled.");
        } else {
            tpAuto.add(p.getUniqueId());
            sender.sendMessage(ChatColor.GREEN + "Auto-accepting teleport requests enabled.");
        }
        return true;
    }

    private boolean handleWorld(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.world")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /world.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "Worlds: " + ChatColor.WHITE + join(worldNames(""), ", "));
            return true;
        }
        World world = plugin.getServer().getWorld(args[0]);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "Unknown world '" + args[0] + "'.");
            return true;
        }
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("allium.world.others")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to teleport other players between worlds.");
                return true;
            }
            target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' is not online.");
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /world <world> <player>");
            return true;
        }
        target.teleport(world.getSpawnLocation());
        sender.sendMessage(ChatColor.GREEN + "Teleported " + (target.equals(sender) ? "" : target.getName() + " ") + "to world '" + world.getName() + "'.");
        return true;
    }

    private boolean handleRtp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /rtp.");
            return true;
        }
        if (!sender.hasPermission("allium.rtp")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /rtp.");
            return true;
        }
        Player player = (Player) sender;
        World world = player.getWorld();
        if (args.length >= 1) {
            world = plugin.getServer().getWorld(args[0]);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world '" + args[0] + "'.");
                return true;
            }
        }
        Location center = rtpCenters.get(world.getName());
        if (center == null) center = world.getSpawnLocation();
        int radius = DEFAULT_RTP_RADIUS;
        for (int attempt = 0; attempt < RTP_ATTEMPTS; attempt++) {
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dz = random.nextInt(radius * 2 + 1) - radius;
            int x = center.getBlockX() + dx;
            int z = center.getBlockZ() + dz;
            int y = world.getHighestBlockYAt(x, z);
            if (y <= 1 || y >= world.getMaxHeight() - 3) continue;
            Block ground = world.getBlockAt(x, y - 1, z);
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            if (isSafeGround(ground.getType()) && !feet.getType().isSolid() && !head.getType().isSolid()) {
                Location dest = new Location(world, x + 0.5, y + 0.1, z + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch());
                player.teleport(dest);
                player.sendMessage(ChatColor.GREEN + "Randomly teleported to " + x + ", " + y + ", " + z + " in " + world.getName() + ".");
                return true;
            }
        }
        sender.sendMessage(ChatColor.RED + "Could not find a safe random location after " + RTP_ATTEMPTS + " attempts. Try again.");
        return true;
    }

    private boolean handleSetTpr(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can set the RTP center.");
            return true;
        }
        if (!sender.hasPermission("allium.settpr")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use /settpr.");
            return true;
        }
        Player player = (Player) sender;
        rtpCenters.put(player.getWorld().getName(), player.getLocation().clone());
        saveRtpCenters();
        sender.sendMessage(ChatColor.GREEN + "Set RTP center for world '" + player.getWorld().getName() + "'.");
        return true;
    }

    private static boolean isSafeGround(Material type) {
        if (type == null || !type.isSolid()) return false;
        String name = type.name();
        return !name.contains("LAVA") && !name.contains("WATER") && !name.contains("FIRE")
                && !name.contains("CACTUS") && !name.contains("MAGMA") && !name.contains("LEAVES");
    }

    private static double parseCoord(String raw, double current) {
        if (raw.startsWith("~")) {
            String offset = raw.substring(1);
            return offset.isEmpty() ? current : current + Double.parseDouble(offset);
        }
        return Double.parseDouble(raw);
    }

    private void loadRtpCenters() {
        rtpCenters.clear();
        File file = new File(plugin.getDataFolder(), "rtp-centers.yml");
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("centers");
        if (section == null) return;
        for (String worldName : section.getKeys(false)) {
            Location loc = LegacyCompat.readLocation(plugin.getServer(), section.getConfigurationSection(worldName));
            if (loc != null) rtpCenters.put(worldName, loc);
        }
    }

    private void saveRtpCenters() {
        File file = new File(plugin.getDataFolder(), "rtp-centers.yml");
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Location> e : rtpCenters.entrySet()) {
            LegacyCompat.writeLocation(yml.createSection("centers." + e.getKey()), e.getValue());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save rtp-centers.yml: " + e.getMessage());
        }
    }

    private List<String> onlinePlayers(String prefix) {
        List<String> out = new ArrayList<String>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
        }
        Collections.sort(out);
        return out;
    }

    private List<String> worldNames(String prefix) {
        List<String> out = new ArrayList<String>();
        for (World w : plugin.getServer().getWorlds()) {
            if (w.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(w.getName());
        }
        Collections.sort(out);
        return out;
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
