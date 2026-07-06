package codes.castled.allium.commands;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.NameTagVisibility;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.CollisionRule;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.OptionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import codes.castled.allium.PluginStart;
import codes.castled.allium.util.SchedulerAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /glow command — sets per-player colored glow outlines using PacketEvents
 * virtual team packets sent directly to viewers.
 *
 * Architecture:
 * - player.setGlowing(true) enables the glow outline (Bukkit API).
 * - A virtual team packet with the desired color is sent to each viewer.
 * - No scoreboard pollution — teams are never registered on any Bukkit scoreboard.
 * - Per-color permissions (allium.glow.red, allium.glow.blue, etc.).
 * - Rainbow mode cycles through 7 colors every 4 ticks.
 * - Folia-safe via SchedulerAdapter.
 */
public class Glow implements CommandExecutor, TabCompleter {

    // ---- Color constants ------------------------------------------------

    static final int RAINBOW_INTERVAL_TICKS = 4;

    static final NamedTextColor[] RAINBOW_COLORS = {
            NamedTextColor.RED,
            NamedTextColor.GOLD,
            NamedTextColor.YELLOW,
            NamedTextColor.GREEN,
            NamedTextColor.AQUA,
            NamedTextColor.BLUE,
            NamedTextColor.LIGHT_PURPLE,
    };

    /** All 16 vanilla glow colors: lowercase name -> NamedTextColor */
    static final Map<String, NamedTextColor> COLORS = new LinkedHashMap<>();

    static {
        COLORS.put("black",        NamedTextColor.BLACK);
        COLORS.put("dark_blue",    NamedTextColor.DARK_BLUE);
        COLORS.put("dark_green",   NamedTextColor.DARK_GREEN);
        COLORS.put("dark_aqua",    NamedTextColor.DARK_AQUA);
        COLORS.put("dark_red",     NamedTextColor.DARK_RED);
        COLORS.put("dark_purple",  NamedTextColor.DARK_PURPLE);
        COLORS.put("gold",         NamedTextColor.GOLD);
        COLORS.put("gray",         NamedTextColor.GRAY);
        COLORS.put("dark_gray",    NamedTextColor.DARK_GRAY);
        COLORS.put("blue",         NamedTextColor.BLUE);
        COLORS.put("green",        NamedTextColor.GREEN);
        COLORS.put("aqua",         NamedTextColor.AQUA);
        COLORS.put("red",          NamedTextColor.RED);
        COLORS.put("light_purple", NamedTextColor.LIGHT_PURPLE);
        COLORS.put("yellow",       NamedTextColor.YELLOW);
        COLORS.put("white",        NamedTextColor.WHITE);
    }

    // ---- Instance state -------------------------------------------------

    private final PluginStart plugin;
    private final PlayerManager playerManager;

    /** target UUID -> current glow color (null = rainbow) */
    private final Map<UUID, NamedTextColor> glowColor = new ConcurrentHashMap<>();

    /** target UUID -> rainbow task handle */
    private final Map<UUID, SchedulerAdapter.TaskHandle> rainbowTasks = new ConcurrentHashMap<>();

    /** target UUID -> current rainbow index */
    private final Map<UUID, Integer> rainbowIndex = new ConcurrentHashMap<>();

    // ---- Constructor ----------------------------------------------------

    public Glow(PluginStart plugin) {
        this.plugin = plugin;
        this.playerManager = PacketEvents.getAPI().getPlayerManager();
    }

    // ---- Command --------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("allium.glow")) {
            sender.sendMessage("§cYou don't have permission to use /" + label);
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§7Usage: /" + label + " <color|rainbow|off> [player]");
            return true;
        }

        String first = args[0].toLowerCase();

        // /glow off [player]
        if (first.equals("off") || first.equals("remove") || first.equals("none")) {
            Player target = resolveTarget(sender, label, args, 1, "allium.glow.others");
            if (target == null) return true;
            removeGlow(target, sender);
            return true;
        }

        // /glow rainbow [player]
        if (first.equals("rainbow")) {
            if (!sender.hasPermission("allium.glow.rainbow")) {
                sender.sendMessage("§cYou don't have permission for rainbow glow.");
                return true;
            }
            Player target = resolveTarget(sender, label, args, 1, "allium.glow.others");
            if (target == null) return true;
            setRainbowGlow(target, sender);
            return true;
        }

        // /glow <color> [player]
        NamedTextColor color = resolveColor(first);
        if (color == null) {
            sender.sendMessage("§cInvalid color: §7" + first + "§c. Use a valid Minecraft color name.");
            return true;
        }

        // Per-color permission check — allium.glow.<color> or allium.glow.*
        String colorPerm = "allium.glow." + first.replace(" ", "_").toLowerCase();
        if (!sender.hasPermission(colorPerm) && !sender.hasPermission("allium.glow.*")) {
            sender.sendMessage("§cYou don't have permission for the color §e" + first + "§c.");
            return true;
        }

        Player target = resolveTarget(sender, label, args, 1, "allium.glow.others");
        if (target == null) return true;
        setGlow(target, color, sender);
        return true;
    }

    // ---- Target resolution ----------------------------------------------

    private @Nullable Player resolveTarget(CommandSender sender, String label, String[] args,
                                           int targetIndex, String otherPerm) {
        if (args.length <= targetIndex) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("§7Usage: /" + label + " <color|rainbow|off> <player>");
                return null;
            }
            return p;
        }

        if (!sender.hasPermission(otherPerm)) {
            sender.sendMessage("§cYou don't have permission to change others' glow.");
            return null;
        }

        Player target = Bukkit.getPlayer(args[targetIndex]);
        if (target == null) {
            sender.sendMessage("§cPlayer §7" + args[targetIndex] + " §cnot found.");
            return null;
        }
        return target;
    }

    // ---- Color resolution -----------------------------------------------

    private static @Nullable NamedTextColor resolveColor(String input) {
        return COLORS.get(input.replace(" ", "_").toLowerCase());
    }

    // ---- Glow set -------------------------------------------------------

    void setGlow(Player target, NamedTextColor color, CommandSender sender) {
        UUID uuid = target.getUniqueId();

        // Cancel any existing rainbow task
        SchedulerAdapter.TaskHandle oldRainbow = rainbowTasks.remove(uuid);
        if (oldRainbow != null) oldRainbow.cancel();
        rainbowIndex.remove(uuid);

        glowColor.put(uuid, color);

        // Enable glowing on the entity (Bukkit API — sets metadata flag 0x40)
        target.setGlowing(true);

        // Send virtual team packets to all viewers
        sendTeamPacketsToAllViewers(target, color);

        // Notify
        String colorName = formatColorName(color);
        if (sender == target) {
            sender.sendMessage("§aGlow color set to §e" + colorName + "§a.");
        } else {
            sender.sendMessage("§aSet §e" + target.getName() + "§a's glow to §e" + colorName + "§a.");
            target.sendMessage("§aGlow color set to §e" + colorName + "§a.");
        }
    }

    // ---- Rainbow glow ---------------------------------------------------

    void setRainbowGlow(Player target, CommandSender sender) {
        UUID uuid = target.getUniqueId();

        // Cancel existing rainbow
        SchedulerAdapter.TaskHandle old = rainbowTasks.remove(uuid);
        if (old != null) old.cancel();

        glowColor.remove(uuid);
        rainbowIndex.put(uuid, 0);

        // Enable glowing
        target.setGlowing(true);

        // Send initial team packet
        sendTeamPacketsToAllViewers(target, RAINBOW_COLORS[0]);

        // Start rainbow cycle
        SchedulerAdapter.TaskHandle handle = SchedulerAdapter.runTimer(() -> {
            if (!target.isOnline()) {
                removeGlow(target, null);
                return;
            }
            int idx = rainbowIndex.getOrDefault(uuid, 0);
            NamedTextColor next = RAINBOW_COLORS[idx % RAINBOW_COLORS.length];
            rainbowIndex.put(uuid, idx + 1);

            // Send update packet to all viewers
            sendTeamUpdateToAllViewers(target, next);
        }, RAINBOW_INTERVAL_TICKS, RAINBOW_INTERVAL_TICKS);

        rainbowTasks.put(uuid, handle);

        if (sender == target) {
            sender.sendMessage("§aRainbow glow enabled!");
        } else {
            sender.sendMessage("§aSet §e" + target.getName() + "§a's glow to §drainbow§a.");
            target.sendMessage("§aRainbow glow enabled!");
        }
    }

    // ---- Glow remove ----------------------------------------------------

    void removeGlow(Player target, @Nullable CommandSender sender) {
        UUID uuid = target.getUniqueId();

        // Cancel rainbow
        SchedulerAdapter.TaskHandle rainbow = rainbowTasks.remove(uuid);
        if (rainbow != null) rainbow.cancel();
        rainbowIndex.remove(uuid);
        glowColor.remove(uuid);

        // Disable glowing
        target.setGlowing(false);

        // Send remove-team packets to all viewers
        sendRemoveTeamToAllViewers(target);

        if (sender != null) {
            if (sender == target) {
                sender.sendMessage("§aGlow effect removed.");
            } else {
                sender.sendMessage("§aRemoved glow from §e" + target.getName() + "§a.");
                target.sendMessage("§aGlow effect removed.");
            }
        }
    }

    // ---- Packet helpers -------------------------------------------------

    /** Team name — unique per target, consistent across color changes. */
    private static String teamName(Player target) {
        return "aglow_" + target.getUniqueId().toString().substring(0, 12);
    }

    /**
     * Sends a CREATE team packet to every online player (including the target).
     * CREATE writes both team info (color) and the player list in one packet.
     * No separate ADD_ENTITIES packet needed.
     */
    private void sendTeamPacketsToAllViewers(Player target, NamedTextColor color) {
        String tName = teamName(target);
        String targetName = target.getName();

        ScoreBoardTeamInfo info = new ScoreBoardTeamInfo(
                Component.empty(), Component.empty(), Component.empty(),
                NameTagVisibility.NEVER, CollisionRule.NEVER, color, OptionData.NONE);

        // CREATE packet — includes both team info (color) and player entries
        WrapperPlayServerTeams createPacket = new WrapperPlayServerTeams(
                tName, TeamMode.CREATE, info, targetName);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            playerManager.sendPacketSilently(viewer, createPacket);
        }
    }

    /**
     * Sends an UPDATE team packet to all viewers (used for rainbow color changes).
     */
    private void sendTeamUpdateToAllViewers(Player target, NamedTextColor color) {
        String tName = teamName(target);

        ScoreBoardTeamInfo info = new ScoreBoardTeamInfo(
                Component.empty(), Component.empty(), Component.empty(),
                NameTagVisibility.NEVER, CollisionRule.NEVER, color,
                OptionData.NONE);

        WrapperPlayServerTeams updatePacket = new WrapperPlayServerTeams(
                tName, TeamMode.UPDATE, info, Collections.emptyList());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            playerManager.sendPacketSilently(viewer, updatePacket);
        }
    }

    /**
     * Sends a REMOVE team packet to all viewers.
     */
    private void sendRemoveTeamToAllViewers(Player target) {
        String tName = teamName(target);

        WrapperPlayServerTeams removePacket = new WrapperPlayServerTeams(
                tName, TeamMode.REMOVE,
                new ScoreBoardTeamInfo(Component.empty(), Component.empty(), Component.empty(),
                        NameTagVisibility.ALWAYS, CollisionRule.NEVER, NamedTextColor.WHITE,
                        OptionData.NONE),
                Collections.emptyList());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            playerManager.sendPacketSilently(viewer, removePacket);
        }
    }

    // ---- Public API -----------------------------------------------------

    public @Nullable NamedTextColor getGlowColor(Player player) {
        return glowColor.get(player.getUniqueId());
    }

    public boolean isGlowing(Player player) {
        UUID uuid = player.getUniqueId();
        return glowColor.containsKey(uuid) || rainbowTasks.containsKey(uuid);
    }

    public void removeGlow(Player player) {
        removeGlow(player, null);
    }

    /**
     * Re-sends team packets to a newly joined viewer for all currently glowing players.
     * Sends only to the new viewer, not to all players.
     */
    public void sendGlowToViewer(Player viewer) {
        for (Map.Entry<UUID, NamedTextColor> entry : glowColor.entrySet()) {
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target == null || !target.isOnline()) continue;
            sendTeamPacketsToViewer(target, entry.getValue(), viewer);
        }
    }

    /**
     * Sends team packets for a specific target+color to a single viewer.
     */
    private void sendTeamPacketsToViewer(Player target, NamedTextColor color, Player viewer) {
        String tName = teamName(target);
        String targetName = target.getName();

        ScoreBoardTeamInfo info = new ScoreBoardTeamInfo(
                Component.empty(), Component.empty(), Component.empty(),
                NameTagVisibility.NEVER, CollisionRule.NEVER, color, OptionData.NONE);

        // CREATE packet — includes both team info (color) and player entries
        WrapperPlayServerTeams createPacket = new WrapperPlayServerTeams(
                tName, TeamMode.CREATE, info, targetName);

        playerManager.sendPacketSilently(viewer, createPacket);
    }

    // ---- Tab completion -------------------------------------------------

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (!sender.hasPermission("allium.glow")) return suggestions;

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            if ("off".startsWith(partial)) suggestions.add("off");
            if ("rainbow".startsWith(partial) && sender.hasPermission("allium.glow.rainbow"))
                suggestions.add("rainbow");
            for (String name : COLORS.keySet()) {
                if (name.startsWith(partial) && (sender.hasPermission("allium.glow." + name) || sender.hasPermission("allium.glow.*"))) {
                    suggestions.add(name);
                }
            }
        } else if (args.length == 2 && sender.hasPermission("allium.glow.others")) {
            String partial = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) {
                    suggestions.add(p.getName());
                }
            }
        }
        return suggestions;
    }

    // ---- Utility --------------------------------------------------------

    private static String formatColorName(NamedTextColor color) {
        // NamedTextColor.toString() returns the key like "red", "dark_blue" etc.
        String name = color.toString().toLowerCase().replace('_', ' ');
        String[] words = name.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Character.toUpperCase(words[i].charAt(0)))
              .append(words[i].substring(1));
        }
        return sb.toString();
    }
}
