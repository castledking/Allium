package net.survivalfun.core.commands.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TP implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final Lang lang;

    // Command aliases for different functionalities
    private final List<String> teleportCommandAliases;
    private final List<String> teleportRequestAliases;
    private final List<String> teleportAcceptAliases;
    private final List<String> teleportDenyAliases;
    private final List<String> teleportHereAliases;
    private final List<String> teleportPositionAliases;
    private final List<String> teleportToggleAliases;
    private final List<String> teleportTopBottomAliases;

    // Maps for teleport requests
    private final Map<UUID, UUID> teleportRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportRequestTimestamps = new ConcurrentHashMap<>();
    private final Set<UUID> teleportToggled = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Location> lastLocation = new ConcurrentHashMap<>();

    // Teleport request expiration time (2 minutes)
    private static final long REQUEST_EXPIRATION = 120000L;

    /**
     * Constructs a new TP handler with the necessary plugin instance and configurations.
     *
     * @param plugin The main plugin instance
     */
    public TP(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();

        // Initialize command aliases
        this.teleportCommandAliases = Arrays.asList("tp", "teleport");
        this.teleportRequestAliases = Arrays.asList("tpa", "tpahere");
        this.teleportAcceptAliases = Arrays.asList("tpaccept", "tpyes");
        this.teleportDenyAliases = Arrays.asList("tpdeny", "tpno");
        this.teleportHereAliases = Arrays.asList("tphere", "s");
        this.teleportPositionAliases = Collections.singletonList("tppos");
        this.teleportToggleAliases = Arrays.asList("tptoggle", "tpo");
        this.teleportTopBottomAliases = Arrays.asList("top", "bottom");

        // Schedule task to clear expired teleport requests
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::clearExpiredRequests, 1200L, 1200L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String usedCommand = label.toLowerCase();

        // Handle basic teleport command
        if (teleportCommandAliases.contains(usedCommand)) {
            return handleTeleport(sender, args);
        }

        // Handle teleport request command
        if (teleportRequestAliases.contains(usedCommand)) {
            return handleTeleportRequest(sender, args, usedCommand.equals("tpahere"));
        }

        // Handle teleport accept command
        if (teleportAcceptAliases.contains(usedCommand)) {
            return handleTeleportAccept(sender, args);
        }

        // Handle teleport deny command
        if (teleportDenyAliases.contains(usedCommand)) {
            return handleTeleportDeny(sender, args);
        }

        // Handle teleport here command
        if (teleportHereAliases.contains(usedCommand)) {
            return handleTeleportHere(sender, args);
        }

        // Handle teleport position command
        if (teleportPositionAliases.contains(usedCommand)) {
            return handleTeleportPosition(sender, args);
        }

        // Handle teleport toggle command
        if (teleportToggleAliases.contains(usedCommand)) {
            if (usedCommand.equals("tptoggle")) {
                return handleTeleportToggle(sender, args);
            } else { // tpo - override
                return handleTeleportOverride(sender, args);
            }
        }

        // Handle top/bottom commands
        if (teleportTopBottomAliases.contains(usedCommand)) {
            if (usedCommand.equals("top")) {
                return handleTeleportTop(sender, args);
            } else { // bottom
                return handleTeleportBottom(sender, args);
            }
        }

        return false;
    }

    /**
     * Handles the /tp command by teleporting to a player
     *
     * @param sender The sender of the command
     * @param args Command arguments: [target]
     * @return true if the command was processed successfully
     */
    private boolean handleTeleport(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.tp")) {
            player.sendMessage(lang.get("no-permission"));
            return true;
        }

        // Check arguments
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

        // Store last location before teleporting
        lastLocation.put(player.getUniqueId(), player.getLocation());

        // Teleport player
        player.teleport(target.getLocation());
        player.sendMessage(lang.get("tp.success"));

        return true;
    }

    /**
     * Handles the /tpa command by sending a teleport request
     *
     * @param sender The sender of the request
     * @param args Command arguments: [target]
     * @param isHereRequest Whether this is a /tpahere request
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportRequest(CommandSender sender, String[] args, boolean isHereRequest) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.tpa")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", isHereRequest ? "tpahere" : "tpa");
            return true;
        }

        // Check arguments
        if (args.length != 1) {
            player.sendMessage(lang.get("command-usage").replace("{cmd}",
                    isHereRequest ? "tpahere" : "tpa"));
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            Text.sendErrorMessage(player, "cannot-self", lang);
            return true;
        }

        // Check if target has teleport requests toggled off
        if (teleportToggled.contains(target.getUniqueId()) && !player.hasPermission("core.tptoggle.bypass")) {
            player.sendMessage(lang.get("tp.blocked").replace("{name}", target.getName()));
            return true;
        }

        // Store the request type (normal or here) in the UUID map
        // For a normal /tpa request, player wants to teleport TO target
        // For a /tpahere request, player wants target to teleport TO player
        if (isHereRequest) {
            // For tpahere, we store target -> player (target will teleport to player)
            teleportRequests.put(target.getUniqueId(), player.getUniqueId());
            teleportRequestTimestamps.put(target.getUniqueId(), System.currentTimeMillis());
        } else {
            // For tpa, we store player -> target (player will teleport to target)
            teleportRequests.put(player.getUniqueId(), target.getUniqueId());
            teleportRequestTimestamps.put(player.getUniqueId(), System.currentTimeMillis());
        }

        // Send appropriate messages
        if (isHereRequest) {
            player.sendMessage(lang.get("tp.sent").replace("{name}", target.getName()));
            String formatMsg = lang.get("tp.format").replace("{action}", "you to teleport to them.");
            TextComponent acceptCmd = Component.text("/tpaccept")
                    .color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/tpaccept"))
                    .hoverEvent(HoverEvent.showText(Component.text("&aAccept request")));
            TextComponent denyCmd = Component.text("/tpdeny")
                    .color(NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/tpdeny"))
                    .hoverEvent(HoverEvent.showText(Component.text("&cDeny request")));
            TextComponent baseMsg = LegacyComponentSerializer.legacyAmpersand().deserialize(formatMsg
                    .replace("{name}", player.getName()));
            Component finalMsg = replaceComponentPlaceholders(baseMsg, "{acceptCmd}", acceptCmd,
                    "{denyCmd}", denyCmd);
            target.sendMessage(finalMsg);
        } else {
            player.sendMessage(lang.get("tp.sent").replace("{name}", target.getName()));
            String formatMsg = lang.get("tp.format").replace("{action}", "to teleport to you.");
            TextComponent acceptCmd = Component.text("/tpaccept")
                    .color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/tpaccept"))
                    .hoverEvent(HoverEvent.showText(Component.text("&aAccept request")));
            TextComponent denyCmd = Component.text("/tpdeny")
                    .color(NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/tpdeny"))
                    .hoverEvent(HoverEvent.showText(Component.text("&cDeny request")));
            TextComponent baseMsg = LegacyComponentSerializer.legacyAmpersand().deserialize(formatMsg
                    .replace("{name}", player.getName()));
            Component finalMsg = replaceComponentPlaceholders(baseMsg, "{acceptCmd}", acceptCmd,
                    "{denyCmd}", denyCmd);
            target.sendMessage(finalMsg);
        }

        return true;
    }

    private Component replaceComponentPlaceholders(Component original, String placeholder1, Component replacement1,
                                                   String placeholder2, Component replacement2) {
        if (!(original instanceof TextComponent textComponent)) {
            return original; // Can't process non-text components
        }

        String content = textComponent.content();

        // Create new component parts
        List<Component> parts = new ArrayList<>();

        // Process text to replace placeholders
        if (content.contains(placeholder1) || content.contains(placeholder2)) {
            // Split by first placeholder
            String[] parts1 = content.split(Pattern.quote(placeholder1), 2);

            if (parts1.length > 1) {
                parts.add(Component.text(parts1[0]).style(textComponent.style()));
                parts.add(replacement1);

                // Check for second placeholder in remaining text
                if (parts1[1].contains(placeholder2)) {
                    String[] parts2 = parts1[1].split(Pattern.quote(placeholder2), 2);
                    parts.add(Component.text(parts2[0]).style(textComponent.style()));
                    parts.add(replacement2);
                    if (parts2.length > 1) {
                        parts.add(Component.text(parts2[1]).style(textComponent.style()));
                    }
                } else {
                    parts.add(Component.text(parts1[1]).style(textComponent.style()));
                }
            } else if (content.contains(placeholder2)) {
                // Only contains second placeholder
                String[] parts2 = content.split(Pattern.quote(placeholder2), 2);
                parts.add(Component.text(parts2[0]).style(textComponent.style()));
                parts.add(replacement2);
                if (parts2.length > 1) {
                    parts.add(Component.text(parts2[1]).style(textComponent.style()));
                }
            }
        } else {
            // No placeholders found, return original
            return original;
        }

        // Process children components too
        for (Component child : textComponent.children()) {
            parts.add(replaceComponentPlaceholders(child, placeholder1, replacement1, placeholder2, replacement2));
        }

        // Build final component
        return Component.empty().children(parts).style(textComponent.style());
    }


    /**
     * Handles the /tpaccept command by accepting a teleport request
     *
     * @param sender The sender accepting the request
     * @param args Command arguments: [player] (optional)
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportAccept(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.tpa")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tpaccept");
            return true;
        }

        UUID requesterUUID = null;
        UUID playerUUID = player.getUniqueId();

        // If player specified who to accept
        if (args.length == 1) {
            Player requester = plugin.getServer().getPlayer(args[0]);
            if (requester == null) {
                player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
                return true;
            }

            // Check if this player has a pending request from the specified player
            if (teleportRequests.containsKey(requester.getUniqueId()) &&
                    teleportRequests.get(requester.getUniqueId()).equals(playerUUID)) {
                requesterUUID = requester.getUniqueId();
            } else if (teleportRequests.containsKey(playerUUID) &&
                    teleportRequests.get(playerUUID).equals(requester.getUniqueId())) {
                requesterUUID = requester.getUniqueId();
            } else {
                player.sendMessage(lang.get("tp.no-request"));
                return true;
            }
        } else {
            // Find any pending requests for this player
            for (Map.Entry<UUID, UUID> entry : teleportRequests.entrySet()) {
                if (entry.getValue().equals(playerUUID) || entry.getKey().equals(playerUUID)) {
                    requesterUUID = entry.getKey();
                    break;
                }
            }
        }

        if (requesterUUID == null) {
            player.sendMessage(lang.get("tp.no-request"));
            return true;
        }

        // Check if request is expired
        Long timestamp = teleportRequestTimestamps.get(requesterUUID);
        if (timestamp == null || System.currentTimeMillis() - timestamp > REQUEST_EXPIRATION) {
            player.sendMessage(lang.get("tp.expired"));
            teleportRequests.remove(requesterUUID);
            teleportRequestTimestamps.remove(requesterUUID);
            return true;
        }

        // Determine who teleports to whom
        UUID destinationUUID = teleportRequests.get(requesterUUID);
        Player teleportingPlayer = plugin.getServer().getPlayer(requesterUUID);
        Player destinationPlayer = plugin.getServer().getPlayer(destinationUUID);

        if (teleportingPlayer == null || destinationPlayer == null) {
            // Determine which player is null and use their name or UUID
            if (teleportingPlayer == null) {
                // The requester is offline
                Text.sendErrorMessage(player, "player-not-found", lang, "{name}",
                        plugin.getServer().getOfflinePlayer(requesterUUID).getName());
            } else {
                // The destination player is offline
                Text.sendErrorMessage(player, "player-not-found", lang, "{name}",
                        plugin.getServer().getOfflinePlayer(destinationUUID).getName());
            }
            teleportRequests.remove(requesterUUID);
            teleportRequestTimestamps.remove(requesterUUID);
            return true;
        }


        // Store last location before teleporting
        lastLocation.put(teleportingPlayer.getUniqueId(), teleportingPlayer.getLocation());

        // Teleport the player
        teleportingPlayer.teleport(destinationPlayer.getLocation());
        teleportingPlayer.sendMessage(lang.get("tp.success"));
        destinationPlayer.sendMessage(lang.get("tp.success"));

        // Clean up the request
        teleportRequests.remove(requesterUUID);
        teleportRequestTimestamps.remove(requesterUUID);

        return true;
    }

    /**
     * Handles the /tpdeny command by denying a teleport request
     *
     * @param sender The sender denying the request
     * @param args Command arguments: [player] (optional)
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportDeny(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.tpa")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tpdeny");
            return true;
        }

        UUID requesterUUID = null;
        UUID playerUUID = player.getUniqueId();

        // If player specified who to deny
        if (args.length == 1) {
            Player requester = plugin.getServer().getPlayer(args[0]);
            if (requester == null) {
                player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
                return true;
            }

            // Check if this player has a pending request from the specified player
            if (teleportRequests.containsKey(requester.getUniqueId()) &&
                    teleportRequests.get(requester.getUniqueId()).equals(playerUUID)) {
                requesterUUID = requester.getUniqueId();
            } else if (teleportRequests.containsKey(playerUUID) &&
                    teleportRequests.get(playerUUID).equals(requester.getUniqueId())) {
                requesterUUID = requester.getUniqueId();
            } else {
                player.sendMessage(lang.get("tp.no-request"));
                return true;
            }
        } else {
            // Find any pending requests for this player
            for (Map.Entry<UUID, UUID> entry : teleportRequests.entrySet()) {
                if (entry.getValue().equals(playerUUID) || entry.getKey().equals(playerUUID)) {
                    requesterUUID = entry.getKey();
                    break;
                }
            }
        }

        if (requesterUUID == null) {
            player.sendMessage(lang.get("tp.no-request"));
            return true;
        }

        // Get the requester player
        Player requester = plugin.getServer().getPlayer(requesterUUID);
        String requesterName = requester != null ? requester.getName() : "Player";

        // Inform both players
        player.sendMessage(lang.get("tp.deny").replace("{name}", requesterName));

        if (requester != null) {
            requester.sendMessage(lang.get("tp.failure").replace("{name}", player.getName()));
        }

        // Clean up the request
        teleportRequests.remove(requesterUUID);
        teleportRequestTimestamps.remove(requesterUUID);

        return true;
    }

    /**
     * Handles the /tphere command by teleporting a player to you
     *
     * @param sender The sender of the command
     * @param args Command arguments: [player]
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportHere(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.tphere")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tphere");
            return true;
        }

        // Check arguments
        if (args.length != 1) {
            player.sendMessage(lang.get("command-usage").replace("{cmd}", "tphere")
                    .replace("{args}", "[player]"));
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(lang.get("player-not-found").replace("{name}", args[0]));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            Text.sendErrorMessage(player, "cannot-self", lang, "{action}", "teleport to");
            return true;
        }

        // Store last location before teleporting
        lastLocation.put(target.getUniqueId(), target.getLocation());

        // Teleport target to player
        target.teleport(player.getLocation());
        target.sendMessage(lang.get("tp.success"));
        player.sendMessage(lang.get("tp.success"));

        return true;
    }

    /**
     * Handles the /tppos command by teleporting to coordinates
     *
     * @param sender The sender of the command
     * @param args Command arguments: [x, y, z]
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportPosition(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.tppos")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tppos");
            return true;
        }

        // Check arguments
        if (args.length != 3) {
            player.sendMessage(lang.get("command-usage").replace("{cmd}", "tppos")
                    .replace("{args}", "[x, y, z]"));
            return true;
        }

        try {
            double x = Double.parseDouble(args[0]);
            double y = Double.parseDouble(args[1]);
            double z = Double.parseDouble(args[2]);

            // Store last location before teleporting
            lastLocation.put(player.getUniqueId(), player.getLocation());

            // Create location and teleport
            Location location = new Location(player.getWorld(), x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());
            player.teleport(location);

            player.sendMessage(lang.get("tppos.success")
                    .replace("{x}", String.format("%.2f", x))
                    .replace("{y}", String.format("%.2f", y))
                    .replace("{z}", String.format("%.2f", z)));

        } catch (NumberFormatException e) {
            Text.sendErrorMessage(player, "invalid", lang, "{arg}", args[0]);
        }

        return true;
    }

    /**
     * Handles the /tptoggle command by toggling teleport requests
     *
     * @param sender The sender of the command
     * @param args Command arguments: none
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportToggle(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.tptoggle")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tptoggle");
            return true;
        }

        UUID playerUUID = player.getUniqueId();

        if (teleportToggled.contains(playerUUID)) {
            teleportToggled.remove(playerUUID);
            player.sendMessage(lang.get("tptoggle.enabled"));
        } else {
            teleportToggled.add(playerUUID);
            player.sendMessage(lang.get("tptoggle.disabled"));
        }

        return true;
    }

    /**
     * Handles the /tpo command by overriding teleport toggle
     *
     * @param sender The sender of the command
     * @param args Command arguments: [player]
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportOverride(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.tpo")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "tpo");
            return true;
        }

        // Check arguments
        if (args.length != 1) {
            player.sendMessage(lang.get("command-usage").replace("{cmd}", "tpo")
                    .replace("{args}", "[player]"));
            return true;
        }

        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            Text.sendErrorMessage(player, "player-not-found", lang, "{name}", args[0]);
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            Text.sendErrorMessage(player, "You cannot teleport to yourself!", lang);
            return true;
        }

        // Store last location before teleporting
        lastLocation.put(player.getUniqueId(), player.getLocation());

        // Teleport player to target
        player.teleport(target.getLocation());
        player.sendMessage(lang.get("tpo.success").replace("{name}", target.getName()));

        // Notify target if they have teleport toggled off
        if (teleportToggled.contains(target.getUniqueId())) {
            target.sendMessage(lang.get("tpo.target").replace("{name}", player.getName()));
        }

        return true;
    }

    /**
     * Handles the /top command by teleporting to the highest block
     *
     * @param sender The sender of the command
     * @param args Command arguments: none
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportTop(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.top")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "top");
            return true;
        }

        Location current = player.getLocation();
        World world = current.getWorld();

        if (world == null) {
            Text.sendErrorMessage(player, "contact-admin", lang);
            return true;
        }

        // Store last location before teleporting
        lastLocation.put(player.getUniqueId(), player.getLocation());

        int x = current.getBlockX();
        int z = current.getBlockZ();
        int y = world.getHighestBlockYAt(x, z);

        Location destination = new Location(world, x + 0.5, y + 1, z + 0.5, current.getYaw(), current.getPitch());
        player.teleport(destination);
        player.sendMessage(lang.get("top.success"));

        return true;
    }

    /**
     * Handles the /bottom command by teleporting to the lowest safe block
     *
     * @param sender The sender of the command
     * @param args Command arguments: none
     * @return true if the command was processed successfully
     */
    private boolean handleTeleportBottom(CommandSender sender, String[] args) {
        // Check if sender is a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("not-a-player"));
            return true;
        }

        // Check permission
        if (!player.hasPermission("core.bottom")) {
            Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "bottom");
            return true;
        }

        Location current = player.getLocation();
        World world = current.getWorld();

        if (world == null) {
            Text.sendErrorMessage(player, "contact-admin", lang);
            return true;
        }

        // Store last location before teleporting
        lastLocation.put(player.getUniqueId(), player.getLocation());

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
        player.teleport(destination);
        player.sendMessage(lang.get("bottom.success"));

        return true;
    }

    /**
     * Clear expired teleport requests
     */
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

    /**
     * Gets the last location a player was at before teleporting
     *
     * @param playerUUID The UUID of the player
     * @return The last location, or null if not available
     */
    public Location getLastLocation(UUID playerUUID) {
        return lastLocation.get(playerUUID);
    }

    /**
     * Checks if a player has teleport requests toggled off
     *
     * @param playerUUID The UUID of the player
     * @return true if teleport requests are toggled off
     */
    public boolean isTeleportToggled(UUID playerUUID) {
        return teleportToggled.contains(playerUUID);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        String commandName = command.getName().toLowerCase();

        // Handle tab completion for player names
        if (args.length == 1) {
            // Commands that need player name completion
            if (teleportCommandAliases.contains(commandName) ||
                    teleportRequestAliases.contains(commandName) ||
                    teleportHereAliases.contains(commandName) ||
                    "tpo".equals(commandName)) {

                return getOnlinePlayerNames(args[0]);
            }

            // Special handling for tppos (coordinate suggestions)
            if (teleportPositionAliases.contains(commandName)) {
                return Collections.singletonList(String.valueOf(Math.round(player.getLocation().getX())));
            }
        }
        // Handle additional args for tppos
        else if (teleportPositionAliases.contains(commandName)) {
            if (args.length == 2) {
                return Collections.singletonList(String.valueOf(Math.round(player.getLocation().getY())));
            } else if (args.length == 3) {
                return Collections.singletonList(String.valueOf(Math.round(player.getLocation().getZ
                        ())));
            }
        }

        return new ArrayList<>();
    }
    /**
     * Gets a list of online player names that match the beginning of the input string
     *
     * @param input The beginning of the player name to match
     * @return List of matching player names
     */
    private List<String> getOnlinePlayerNames(String input) {
        String lowercaseInput = input.toLowerCase();
        return plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(lowercaseInput))
                .collect(Collectors.toList());
    }
}
