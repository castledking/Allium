package net.survivalfun.core.commands.utils;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TeleportCommandManager implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final Lang lang;

    // Map command aliases to their respective permission and handler
    private final Map<String, CommandInfo> commandAliases = new HashMap<>();

    // Maps for teleport requests
    private final Map<UUID, UUID> teleportRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportRequestTimestamps = new ConcurrentHashMap<>();
    private final Set<UUID> teleportToggled = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Location> lastLocation = new ConcurrentHashMap<>();

    // Teleport request expiration time (2 minutes)
    private static final long REQUEST_EXPIRATION = 120000L;

    // Static class to store command metadata
    private static class CommandInfo {
        final String permission;
        final CommandHandler handler;

        CommandInfo(String permission, CommandHandler handler) {
            this.permission = permission;
            this.handler = handler;
        }
    }

    // Functional interface for command handlers
    @FunctionalInterface
    private interface CommandHandler {
        boolean handle(Player player, String[] args);
    }

    public TeleportCommandManager(PluginStart plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();

        // Register all teleport commands with their permissions and handlers
        registerCommands();

        // Schedule task to clear expired teleport requests
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::clearExpiredRequests, 1200L, 1200L);
    }

    private void registerCommands() {
        // Basic teleport to player
        commandAliases.put("tp", new CommandInfo("core.tp", this::handleTeleport));
        commandAliases.put("teleport", new CommandInfo("core.tp", this::handleTeleport));

        // Teleport request
        commandAliases.put("tpa", new CommandInfo("core.tpa", this::handleTeleportRequest));

        // Teleport to coordinates
        commandAliases.put("tppos", new CommandInfo("core.tppos", this::handleTeleportPosition));

        // Teleport player to you
        commandAliases.put("tphere", new CommandInfo("core.tphere", this::handleTeleportHere));
        commandAliases.put("s", new CommandInfo("core.tphere", this::handleTeleportHere));

        // Teleport all
        commandAliases.put("tpall", new CommandInfo("core.tpall", this::handleTeleportAll));

        // Teleport request responses
        commandAliases.put("tpaccept", new CommandInfo("core.tpa", this::handleTeleportAccept));
        commandAliases.put("tpyes", new CommandInfo("core.tpa", this::handleTeleportAccept));

        commandAliases.put("tpdeny", new CommandInfo("core.tpa", this::handleTeleportDeny));
        commandAliases.put("tpno", new CommandInfo("core.tpa", this::handleTeleportDeny));

        // Cancel teleport request
        commandAliases.put("tpcancel", new CommandInfo("core.tpa", this::handleTeleportCancel));
        commandAliases.put("tpacancel", new CommandInfo("core.tpa", this::handleTeleportCancel));

        // Teleport toggle
        commandAliases.put("tptoggle", new CommandInfo("core.tptoggle", this::handleTeleportToggle));

        // Override player's teleport toggle
        commandAliases.put("tpo", new CommandInfo("core.tpo", this::handleTeleportOverride));

        // Teleport to offline player's last location
        commandAliases.put("tpoffline", new CommandInfo("core.tpoffline", this::handleTeleportOffline));

        // Teleport to highest/lowest point
        commandAliases.put("top", new CommandInfo("core.top", this::handleTeleportTop));
        commandAliases.put("bottom", new CommandInfo("core.bottom", this::handleTeleportBottom));

        // Auto-accept teleport request
        commandAliases.put("tpauto", new CommandInfo("core.tpauto", this::handleTeleportAuto));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Get command info for the used alias
        CommandInfo commandInfo = commandAliases.get(label.toLowerCase());
        if (commandInfo == null) {
            sender.sendMessage(lang.get("unknown-command"));
            return true;
        }

        // Check permission
        if (!player.hasPermission(commandInfo.permission)) {
            sender.sendMessage(lang.get("no-permission"));
            return true;
        }

        // Execute the command handler
        return commandInfo.handler.handle(player, args);
    }

    // Base teleport method
    private void teleportPlayer(Player player, Location destination, String successMessage) {
        // Store last location before teleporting
        lastLocation.put(player.getUniqueId(), player.getLocation());

        // Perform teleport
        player.teleport(destination);

        // Send success message if provided
        if (successMessage != null) {
            player.sendMessage(successMessage);
        }
    }

    // Handler methods for different teleport commands

    private boolean handleTeleport(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(lang.get("tp.usage"));
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(lang.get("tp.self"));
            return true;
        }

        teleportPlayer(player, target.getLocation(),
                lang.get("tp.success").replace("{player}", target.getName()));
        return true;
    }

    private boolean handleTeleportRequest(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(lang.get("tpa.usage"));
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(lang.get("tpa.self"));
            return true;
        }

        // Check if target has teleport requests toggled off
        if (teleportToggled.contains(target.getUniqueId()) && !player.hasPermission("core.tpo")) {
            player.sendMessage(lang.get("tpa.toggled").replace("{player}", target.getName()));
            return true;
        }

        // Send teleport request
        teleportRequests.put(player.getUniqueId(), target.getUniqueId());
        teleportRequestTimestamps.put(player.getUniqueId(), System.currentTimeMillis());

        player.sendMessage(lang.get("tpa.sent").replace("{player}", target.getName()));
        target.sendMessage(lang.get("tpa.received")
                .replace("{player}", player.getName())
                .replace("{acceptCmd}", "/tpaccept")
                .replace("{denyCmd}", "/tpdeny"));

        return true;
    }

    private boolean handleTeleportPosition(Player player, String[] args) {
        if (args.length != 3) {
            player.sendMessage(lang.get("tppos.usage"));
            return true;
        }

        try {
            double x = Double.parseDouble(args[0]);
            double y = Double.parseDouble(args[1]);
            double z = Double.parseDouble(args[2]);

            Location location = new Location(player.getWorld(), x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());
            teleportPlayer(player, location, lang.get("tppos.success")
                    .replace("{x}", String.format("%.2f", x))
                    .replace("{y}", String.format("%.2f", y))
                    .replace("{z}", String.format("%.2f", z)));

        } catch (NumberFormatException e) {
            player.sendMessage(lang.get("tppos.invalid-coords"));
        }

        return true;
    }

    private boolean handleTeleportHere(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(lang.get("tphere.usage"));
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(lang.get("tphere.self"));
            return true;
        }

        teleportPlayer(target, player.getLocation(),
                lang.get("tphere.target").replace("{player}", player.getName()));

        player.sendMessage(lang.get("tphere.success").replace("{player}", target.getName()));
        return true;
    }

    private boolean handleTeleportAll(Player player, String[] args) {
        int count = 0;
        Location location = player.getLocation();

        for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
            if (!onlinePlayer.getUniqueId().equals(player.getUniqueId())) {
                teleportPlayer(onlinePlayer, location,
                        lang.get("tpall.target").replace("{player}", player.getName()));
                count++;
            }
        }

        player.sendMessage(lang.get("tpall.success").replace("{count}", String.valueOf(count)));
        return true;
    }

    private boolean handleTeleportAccept(Player player, String[] args) {
        UUID requesterUUID = null;

        // If player specified who to accept
        if (args.length == 1) {
            Player requester = plugin.getServer().getPlayer(args[0]);
            if (requester == null) {
                player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
                return true;
            }

            // Check if this player has a pending request from the specified player
            if (teleportRequests.containsKey(requester.getUniqueId()) &&
                    teleportRequests.get(requester.getUniqueId()).equals(player.getUniqueId())) {
                requesterUUID = requester.getUniqueId();
            } else {
                player.sendMessage(lang.get("tpaccept.no-request").replace("{player}", requester.getName()));
                return true;
            }
        } else {
            // Find any pending requests for this player
            for (Map.Entry<UUID, UUID> entry : teleportRequests.entrySet()) {
                if (entry.getValue().equals(player.getUniqueId())) {
                    requesterUUID = entry.getKey();
                    break;
                }
            }
        }

        if (requesterUUID == null) {
            player.sendMessage(lang.get("tpaccept.none"));
            return true;
        }

        // Check if request is expired
        Long timestamp = teleportRequestTimestamps.get(requesterUUID);
        if (timestamp == null || System.currentTimeMillis() - timestamp > REQUEST_EXPIRATION) {
            player.sendMessage(lang.get("tpaccept.expired"));
            teleportRequests.remove(requesterUUID);
            teleportRequestTimestamps.remove(requesterUUID);
            return true;
        }

        // Get the requester player
        Player requester = plugin.getServer().getPlayer(requesterUUID);
        if (requester == null) {
            player.sendMessage(lang.get("tpaccept.offline"));
            teleportRequests.remove(requesterUUID);
            teleportRequestTimestamps.remove(requesterUUID);
            return true;
        }

        // Teleport the requester to the player
        teleportPlayer(requester, player.getLocation(),
                lang.get("tpaccept.teleported").replace("{player}", player.getName()));

        player.sendMessage(lang.get("tpaccept.success").replace("{player}", requester.getName()));

        // Clean up the request
        teleportRequests.remove(requesterUUID);
        teleportRequestTimestamps.remove(requesterUUID);

        return true;
    }

    private boolean handleTeleportDeny(Player player, String[] args) {
        UUID requesterUUID = null;

        // If player specified who to deny
        if (args.length == 1) {
            Player requester = plugin.getServer().getPlayer(args[0]);
            if (requester == null) {
                player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
                return true;
            }

            // Check if this player has a pending request from the specified player
            if (teleportRequests.containsKey(requester.getUniqueId()) &&
                    teleportRequests.get(requester.getUniqueId()).equals(player.getUniqueId())) {
                requesterUUID = requester.getUniqueId();
            } else {
                player.sendMessage(lang.get("tpdeny.no-request").replace("{player}", requester.getName()));
                return true;
            }
        } else {
            // Find any pending requests for this player
            for (Map.Entry<UUID, UUID> entry : teleportRequests.entrySet()) {
                if (entry.getValue().equals(player.getUniqueId())) {
                    requesterUUID = entry.getKey();
                    break;
                }
            }
        }

        if (requesterUUID == null) {
            player.sendMessage(lang.get("tpdeny.none"));
            return true;
        }

        // Get the requester player
        Player requester = plugin.getServer().getPlayer(requesterUUID);
        String requesterName = requester != null ? requester.getName() : "Player";

        // Inform both players
        player.sendMessage(lang.get("tpdeny.success").replace("{player}", requesterName));

        if (requester != null) {
            requester.sendMessage(lang.get("tpdeny.denied").replace("{player}", player.getName()));
        }

        // Clean up the request
        teleportRequests.remove(requesterUUID);
        teleportRequestTimestamps.remove(requesterUUID);

        return true;
    }

    private boolean handleTeleportCancel(Player player, String[] args) {
        if (!teleportRequests.containsKey(player.getUniqueId())) {
            player.sendMessage(lang.get("tpcancel.none"));
            return true;
        }

        UUID targetUUID = teleportRequests.get(player.getUniqueId());
        Player target = plugin.getServer().getPlayer(targetUUID);

        // Clean up the request
        teleportRequests.remove(player.getUniqueId());
        teleportRequestTimestamps.remove(player.getUniqueId());

        player.sendMessage(lang.get("tpcancel.success"));

        // Notify the target player if they're online
        if (target != null) {
            target.sendMessage(lang.get("tpcancel.target").replace("{player}", player.getName()));
        }

        return true;
    }

    private boolean handleTeleportToggle(Player player, String[] args) {
        if (teleportToggled.contains(player.getUniqueId())) {
            teleportToggled.remove(player.getUniqueId());
            player.sendMessage(lang.get("tptoggle.enabled"));
        } else {
            teleportToggled.add(player.getUniqueId());
            player.sendMessage(lang.get("tptoggle.disabled"));
        }

        return true;
    }

    private boolean handleTeleportOverride(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(lang.get("tpo.usage"));
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(lang.get("tpo.self"));
            return true;
        }

        teleportPlayer(player, target.getLocation(),
                lang.get("tpo.success").replace("{player}", target.getName()));

        if (teleportToggled.contains(target.getUniqueId())) {
            target.sendMessage(lang.get("tpo.target")
                    .replace("{player}", player.getName()));
        }

        return true;
    }

    private boolean handleTeleportOffline(Player player, String[] args) {
        // This would require storing player locations in a database
        // For simplicity, we'll just show the usage and mention this isn't implemented
        player.sendMessage(lang.get("tpoffline.not-implemented"));
        return true;
    }

    private boolean handleTeleportTop(Player player, String[] args) {
        Location current = player.getLocation();
        World world = current.getWorld();

        if (world == null) {
            player.sendMessage(lang.get("top.invalid-world"));
            return true;
        }

        int x = current.getBlockX();
        int z = current.getBlockZ();
        int y = world.getHighestBlockYAt(x, z);

        Location destination = new Location(world, x + 0.5, y + 1, z + 0.5, current.getYaw(), current.getPitch());
        teleportPlayer(player, destination, lang.get("top.success"));

        return true;
    }

    private boolean handleTeleportBottom(Player player, String[] args) {
        Location current = player.getLocation();
        World world = current.getWorld();

        if (world == null) {
            player.sendMessage(lang.get("bottom.invalid-world"));
            return true;
        }

        int x = current.getBlockX();
        int z = current.getBlockZ();
        int y = world.getMinHeight(); // Use minimum world height

        // Find the first safe location from bottom up
        while (y < current.getY()) {
            Block block = world.getBlockAt(x, y, z);
            Block blockAbove = world.getBlockAt(x, y + 1, z);
            Block blockAbove2 = world.getBlockAt(x, y + 2, z);

            if (!block.getType().isAir() &&
                    blockAbove.getType().isAir() &&
                    blockAbove2.getType().isAir()) {
                break;
            }
            y++;
        }

        Location destination = new Location(world, x + 0.5, y + 1, z + 0.5, current.getYaw(), current.getPitch());
        teleportPlayer(player, destination, lang.get("bottom.success"));

        return true;
    }

    private boolean handleTeleportAuto(Player player, String[] args) {
        // This would toggle auto-accepting teleport requests for a player
        // For simplicity, we'll just show a message saying it's not implemented
        player.sendMessage(lang.get("tpauto.not-implemented"));
        return true;
    }

    // Clear expired teleport requests
    private void clearExpiredRequests() {
        long currentTime = System.currentTimeMillis();
        Set<UUID> expiredRequests = new HashSet<>();

        for (Map.Entry<UUID, Long> entry : teleportRequestTimestamps.entrySet()) {
            if (currentTime - entry.getValue() > REQUEST_EXPIRATION) {
                expiredRequests.add(entry.getKey());
            }
        }

        for (UUID uuid : expiredRequests) {
            teleportRequests.remove(uuid);
            teleportRequestTimestamps.remove(uuid);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return new ArrayList<>();
        }

        CommandInfo commandInfo = commandAliases.get(alias.toLowerCase());
        if (commandInfo == null || !player.hasPermission(commandInfo.permission)) {
            return new ArrayList<>();
        }

        // Handle tab completion for player names
        if (args.length == 1) {
            String partialName = args[0].toLowerCase();

            // Commands that need player name completion
            if (alias.equalsIgnoreCase("tp") ||
                    alias.equalsIgnoreCase("teleport") ||
                    alias.equalsIgnoreCase("tpa") ||
                    alias.equalsIgnoreCase("tphere") ||
                    alias.equalsIgnoreCase("s") ||
                    alias.equalsIgnoreCase("tpo") ||
                    alias.equalsIgnoreCase("tpaccept") ||
                    alias.equalsIgnoreCase("tpdeny")) {

                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(partialName))
                        .collect(Collectors.toList());
            }

            // Special handling for tppos (coordinate suggestions)
            if (alias.equalsIgnoreCase("tppos")) {
                return Collections.singletonList(String.valueOf(Math.round(player.getLocation().getX())));
            }
        }
        // Handle additional args for tppos
        else if (alias.equalsIgnoreCase("tppos")) {
            if (args.length == 2) {
                return Collections.singletonList(String.valueOf(Math.round(player.getLocation().getY())));
            } else if (args.length == 3) {
                return Collections.singletonList(String.valueOf(Math.round(player.getLocation().getZ())));
            }
        }

        return new ArrayList<>();
    }
}
