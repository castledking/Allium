package codes.castled.allium.command.explosion;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.explosion.ExplosionSettings.Explosive;
import codes.castled.allium.explosion.RegenerationService;
import codes.castled.allium.message.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code /nuke [player...]}: TNT rains on the players named, or on everyone online. It hurts what it
 * lands on but breaks no blocks (nor item frames or paintings while {@code protect-hanging} is on).
 */
public final class NukeCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final RegenerationService regeneration;

    public NukeCommand(PluginStart plugin, RegenerationService regeneration) {
        this.plugin = plugin;
        this.regeneration = regeneration;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.NUKE;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        Set<Player> targets = new LinkedHashSet<>();
        if (context.args().length == 0) {
            targets.addAll(plugin.getServer().getOnlinePlayers());
        } else {
            for (String name : context.args()) {
                Player target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, sender, name);
                if (target == null) return;
                targets.add(target);
            }
        }
        List<String> names = new ArrayList<>();
        for (Player target : targets) {
            names.add(target.getName());
            UtilitySupport.onTarget(plugin, sender, target, () -> {
                drop(target);
                UtilitySupport.send(plugin, target, Messages.NUKE_INCOMING);
            });
        }
        if (!(targets.size() == 1 && targets.contains(sender)) && !names.isEmpty()) {
            UtilitySupport.send(plugin, sender, Messages.NUKE_SENT, "{players}", String.join(", ", names));
        }
        UtilitySupport.audit(plugin, sender, "nuke", "targets=" + String.join(",", names));
    }

    /** Essentials' pattern: a 5 by 5 grid of TNT, five blocks apart, 64 blocks above the highest block. */
    private void drop(Player target) {
        Location at = target.getLocation();
        World world = at.getWorld();
        if (world == null) return;
        int y = world.getHighestBlockYAt(at) + 64;
        int baseX = at.getBlockX();
        int baseZ = at.getBlockZ();
        for (int x = -10; x <= 10; x += 5) {
            for (int z = -10; z <= 10; z += 5) {
                int bx = baseX + x;
                int bz = baseZ + z;
                UtilitySupport.onRegion(plugin, world, bx >> 4, bz >> 4, () -> {
                    TNTPrimed tnt = world.spawn(new Location(world, bx + 0.5D, y, bz + 0.5D), TNTPrimed.class);
                    regeneration.tag(tnt, Explosive.NUKE);
                });
            }
        }
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        Set<String> already = new java.util.HashSet<>();
        for (int i = 0; i < args.length - 1; i++) already.add(args[i].toLowerCase(Locale.ROOT));
        List<String> names = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!codes.castled.allium.naming.PlayerNameVisibility.canSeeTarget(sender, player)) continue;
            String name = player.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix) && !already.contains(lower)) names.add(name);
        }
        return names;
    }
}
