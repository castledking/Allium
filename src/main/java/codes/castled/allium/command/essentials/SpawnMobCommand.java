package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code /spawnmob <mob>[:data][,<mount>[:data]] [amount] [player]}: Essentials-style mob spawning
 * with per-mob permissions, mount stacking, {@code :data} modifiers, and a configurable spawn limit.
 */
public final class SpawnMobCommand implements SpecCommandHandler {

    public static final String PERM_BASE = "allium.spawnmob";
    public static final String PERM_ALL = "allium.spawnmob.*";
    public static final String PERM_STACK = "allium.spawnmob.stack";
    public static final String PERM_OTHERS = "allium.spawnmob.others";

    private final PluginStart plugin;

    public SpawnMobCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.SPAWNMOB;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();

        if (args.length == 0) {
            String available = MobSpawner.allMobs().stream()
                    .filter(m -> canSpawnMob(sender, m))
                    .map(MobSpawner.MobEntry::canonicalName)
                    .collect(Collectors.joining(", "));
            if (available.isEmpty()) available = "none";
            plugin.getMessageManager().send(sender, Messages.SPAWNMOB_LIST, "{mobs}", available);
            return;
        }
        if (args.length > 3) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }
        if (!(sender instanceof Player) && args.length < 3) {
            context.sendInvalidArgs(spec().consoleUsage());
            return;
        }

        List<MobSpawner.ParsedPart> parsed = MobSpawner.parseSpec(args[0]);
        if (parsed.isEmpty()) {
            context.sendInvalidArgs(sender instanceof Player ? spec().selfUsage() : spec().consoleUsage());
            return;
        }
        if (parsed.size() > 1 && !sender.hasPermission(PERM_STACK)) {
            plugin.getMessageManager().send(sender, Messages.SPAWNMOB_NO_STACK);
            return;
        }

        List<MobSpawner.MobEntry> entries = new ArrayList<>(parsed.size());
        List<String> datas = new ArrayList<>(parsed.size());
        for (MobSpawner.ParsedPart part : parsed) {
            var entryOpt = MobSpawner.lookup(part.mobToken());
            if (entryOpt.isEmpty()) {
                plugin.getMessageManager().send(sender, Messages.SPAWNMOB_INVALID_MOB, "{mob}", part.mobToken());
                return;
            }
            MobSpawner.MobEntry entry = entryOpt.get();
            if (!canSpawnMob(sender, entry)) {
                plugin.getMessageManager().send(sender, Messages.SPAWNMOB_NO_PERM_MOB, "{mob}", entry.canonicalName());
                return;
            }
            entries.add(entry);
            datas.add(part.rawData());
            if ("".equals(part.rawData())) {
                List<String> hints = MobSpawner.dataHints(entry.type());
                if (!hints.isEmpty()) {
                    plugin.getMessageManager().send(sender, Messages.SPAWNMOB_DATA_LIST,
                            "{mob}", entry.canonicalName(),
                            "{data}", String.join(", ", hints));
                }
            }
        }

        int amount = 1;
        if (args.length >= 2) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException invalid) {
                plugin.getMessageManager().send(sender, Messages.SPAWNMOB_INVALID_AMOUNT);
                return;
            }
            if (amount < 1) {
                plugin.getMessageManager().send(sender, Messages.SPAWNMOB_INVALID_AMOUNT);
                return;
            }
        }

        Player target;
        Location spawnLocation;
        if (args.length == 3) {
            if (sender instanceof Player && !sender.hasPermission(PERM_OTHERS)) {
                plugin.getMessageManager().send(sender, Messages.NO_PERMISSION, "{cmd}", "spawnmob for others");
                return;
            }
            target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, sender, args[2]);
            if (target == null) return;
            spawnLocation = target.getLocation().clone();
        } else {
            Player player = (Player) sender;
            target = player;
            spawnLocation = targetBlockLocation(player);
        }

        int serverLimit = configuredLimit();
        if (entries.size() > serverLimit) {
            entries = new ArrayList<>(entries.subList(0, serverLimit));
            datas = new ArrayList<>(datas.subList(0, serverLimit));
        }
        int effectiveLimit = Math.max(1, serverLimit / entries.size());
        if (amount > effectiveLimit) {
            amount = effectiveLimit;
            plugin.getMessageManager().send(sender, Messages.SPAWNMOB_LIMIT, "{limit}", Integer.toString(serverLimit));
        }

        int spawnedStacks = 0;
        for (int i = 0; i < amount; i++) {
            Entity root = MobSpawner.spawnStack(spawnLocation, entries, datas, target);
            if (root == null) {
                plugin.getMessageManager().send(sender, Messages.SPAWNMOB_UNABLE);
                return;
            }
            spawnedStacks++;
        }

        int totalEntities = spawnedStacks * entries.size();
        String label = entries.size() == 1
                ? entries.get(0).canonicalName()
                : entries.stream().map(MobSpawner.MobEntry::canonicalName).collect(Collectors.joining(","));
        plugin.getMessageManager().send(sender, Messages.SPAWNMOB_SPAWNED,
                "{count}", Integer.toString(totalEntities),
                "{mob}", label);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_BASE)) return List.of();
        if (args.length == 1) {
            String raw = args[0];
            int lastComma = raw.lastIndexOf(',');
            String prefixPart = lastComma >= 0 ? raw.substring(0, lastComma + 1) : "";
            String current = (lastComma >= 0 ? raw.substring(lastComma + 1) : raw).toLowerCase(Locale.ROOT);
            if (lastComma >= 0 && !sender.hasPermission(PERM_STACK)) return List.of();

            int colon = current.indexOf(':');
            if (colon >= 0) {
                String mobName = current.substring(0, colon);
                String dataPrefix = current.substring(colon + 1);
                var entry = MobSpawner.lookup(mobName);
                if (entry.isEmpty() || !canSpawnMob(sender, entry.get())) return List.of();
                return MobSpawner.dataHints(entry.get().type()).stream()
                        .filter(h -> !h.startsWith("<") && h.startsWith(dataPrefix))
                        .map(h -> prefixPart + mobName + ":" + h)
                        .limit(50)
                        .toList();
            }
            return MobSpawner.allMobs().stream()
                    .filter(m -> canSpawnMob(sender, m))
                    .map(MobSpawner.MobEntry::canonicalName)
                    .filter(name -> name.startsWith(current))
                    .map(name -> prefixPart + name)
                    .limit(50)
                    .toList();
        }
        if (args.length == 2) {
            String prefix = args[1];
            return List.of("1", "2", "5", "10").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 3 && (!(sender instanceof Player) || sender.hasPermission(PERM_OTHERS))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream()
                    .filter(p -> PlayerNameVisibility.canSee(sender, p))
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .limit(50)
                    .toList();
        }
        return List.of();
    }

    public boolean canSpawnMob(CommandSender sender, MobSpawner.MobEntry entry) {
        if (sender.isOp() || sender.hasPermission(PERM_ALL) || sender.hasPermission("allium.admin")) {
            return true;
        }
        return sender.hasPermission("allium.spawnmob." + entry.canonicalName())
                || sender.hasPermission("allium.spawnmob." + entry.type().name().toLowerCase(Locale.ROOT));
    }

    public int configuredLimit() {
        int raw = plugin.getConfig() != null ? plugin.getConfig().getInt("spawnmob.limit", 10) : 10;
        return Math.max(1, Math.min(256, raw));
    }

    private static Location targetBlockLocation(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(128, FluidCollisionMode.NEVER);
        if (hit != null && hit.getHitBlock() != null) {
            BlockFace face = hit.getHitBlockFace() != null ? hit.getHitBlockFace() : BlockFace.UP;
            Block dest = hit.getHitBlock().getRelative(face);
            Location loc = dest.getLocation().add(0.5, 0.0, 0.5);
            loc.setYaw(player.getLocation().getYaw());
            loc.setPitch(0f);
            return loc;
        }
        return player.getLocation().clone();
    }
}
