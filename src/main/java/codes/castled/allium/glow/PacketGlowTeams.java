package codes.castled.allium.glow;

import codes.castled.allium.PluginStart;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Glow colours as virtual teams sent straight to each viewer through PacketEvents, as legacy Allium
 * did: no team is ever registered on a Bukkit scoreboard. Loaded only when PacketEvents is running.
 */
final class PacketGlowTeams implements GlowService.Teams {

    private final PluginStart plugin;
    private final PlayerManager players;
    /** Targets whose team every online viewer has. */
    private final java.util.Set<java.util.UUID> created = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private PacketGlowTeams(PluginStart plugin) {
        this.plugin = plugin;
        this.players = PacketEvents.getAPI().getPlayerManager();
    }

    static GlowService.Teams create(PluginStart plugin) {
        return new PacketGlowTeams(plugin);
    }

    /** One team per player, the same name across colour changes; 16 characters at most. */
    static String teamName(Player target) {
        return "aglow_" + target.getUniqueId().toString().replace("-", "").substring(0, 10);
    }

    private static WrapperPlayServerTeams.ScoreBoardTeamInfo info(String color) {
        NamedTextColor named = NamedTextColor.NAMES.value(color);
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.empty(), Component.empty(), Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.ALWAYS, WrapperPlayServerTeams.CollisionRule.ALWAYS,
                named == null ? NamedTextColor.WHITE : named, WrapperPlayServerTeams.OptionData.NONE);
    }

    @Override
    public void show(Player target, String color, Iterable<? extends Player> viewers) {
        // The first time every viewer gets the team with its member; after that, a colour update.
        boolean fresh = created.add(target.getUniqueId());
        var packet = fresh
                ? new WrapperPlayServerTeams(teamName(target), WrapperPlayServerTeams.TeamMode.CREATE, info(color), target.getName())
                : new WrapperPlayServerTeams(teamName(target), WrapperPlayServerTeams.TeamMode.UPDATE, info(color), java.util.List.<String>of());
        for (Player viewer : viewers) send(viewer, packet);
    }

    @Override
    public void introduce(Player target, String color, Player viewer) {
        send(viewer, new WrapperPlayServerTeams(teamName(target), WrapperPlayServerTeams.TeamMode.CREATE, info(color), target.getName()));
    }

    @Override
    public void hide(Player target, Iterable<? extends Player> viewers) {
        if (!created.remove(target.getUniqueId())) return;
        var remove = new WrapperPlayServerTeams(teamName(target), WrapperPlayServerTeams.TeamMode.REMOVE,
                java.util.Optional.<WrapperPlayServerTeams.ScoreBoardTeamInfo>empty());
        for (Player viewer : viewers) send(viewer, remove);
    }

    /** Players PacketEvents is not attached to (Geyser sessions, fake players) are skipped. */
    private void send(Player viewer, WrapperPlayServerTeams packet) {
        try {
            if (players.getUser(viewer) == null) return;
            players.sendPacketSilently(viewer, packet);
        } catch (RuntimeException failed) {
            plugin.logger().debug("Glow: no team packet for " + viewer.getName() + ": " + failed.getMessage());
        }
    }
}
