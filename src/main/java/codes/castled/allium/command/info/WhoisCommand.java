package codes.castled.allium.command.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.info.PlayerInfoRecord;
import codes.castled.allium.info.PlayerInfoService;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.naming.PlayerNameVisibility;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code /whois <player>}: shows UUID, name history from the name index, online/offline state, and
 * staff-only location ({@code allium.whois.location}), strictly enforcing vanish "looks offline".
 */
public final class WhoisCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private final PlayerInfoService info;
    private final codes.castled.allium.info.WhoisExtras extras;

    public WhoisCommand(PluginStart plugin, PlayerInfoService info) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.info = Objects.requireNonNull(info, "info");
        this.extras = new codes.castled.allium.info.WhoisExtras(plugin);
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.WHOIS;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        String[] args = context.args();
        if (args.length != 1 || args[0].isBlank()) {
            context.sendInvalidArgs(spec().selfUsage());
            return;
        }
        CommandSender sender = context.sender();
        String query = args[0].trim();

        info.lookupProfile(sender, query, true).whenComplete((opt, error) -> info.runFor(sender, () -> {
            if (error != null) {
                plugin.logger().warning("Failed /whois lookup for " + query + ": " + error.getMessage());
                plugin.getMessageManager().send(sender, Messages.INFO_STORAGE_FAILED);
                return;
            }
            if (opt.isEmpty()) {
                plugin.getMessageManager().send(sender, Messages.INFO_SEEN_UNKNOWN, "{player}", query);
                return;
            }

            PlayerInfoService.ProfileLookupResult lookup = opt.get();
            PlayerInfoRecord record = lookup.record();
            Player liveOnline = plugin.getServer().getPlayer(record.uuid());
            Runnable offline = () -> report(sender, lookup, null);
            if (liveOnline == null) {
                offline.run();
                return;
            }
            boolean selfOrConsole = !(sender instanceof Player) || sender == liveOnline;
            boolean seeVanished = selfOrConsole || sender.hasPermission("allium.vanish.see");
            boolean showLocation = sender.hasPermission(PlayerInfoService.PERMISSION_WHOIS_LOCATION);
            boolean showExtra = sender.hasPermission(codes.castled.allium.info.WhoisExtras.PERMISSION_EXTRA);
            boolean showIp = sender.hasPermission(codes.castled.allium.info.WhoisExtras.PERMISSION_WHOIS_IP);
            UtilitySupport.onTarget(plugin, sender, liveOnline, () -> {
                boolean vanished = PlayerNameVisibility.isVanished(liveOnline);
                if (!liveOnline.isOnline() || vanished && !seeVanished) {
                    UtilitySupport.onOwner(plugin, sender, offline);
                    return;
                }
                String tags = (info.isAfk(liveOnline) ? " &7[AFK]" : "") + (vanished ? " &8[Vanished]" : "");
                LiveProfile live = new LiveProfile(liveOnline.getName(), tags, vanished,
                        PlayerInfoService.formatElapsed(info.onlineDurationMillis(liveOnline, record)),
                        showLocation ? liveOnline.getLocation() : null,
                        showExtra ? extras.capture(liveOnline, showIp) : null);
                UtilitySupport.onOwner(plugin, sender, () -> {
                    if (live.vanished() && !selfOrConsole && !sender.hasPermission("allium.vanish.see")) offline.run();
                    else report(sender, lookup, live);
                });
            }, offline);
        }));
    }

    private record LiveProfile(String name, String tags, boolean vanished, String onlineFor, Location location,
                               codes.castled.allium.info.WhoisExtras.LiveDetails details) {}

    private void report(CommandSender sender, PlayerInfoService.ProfileLookupResult lookup, LiveProfile live) {
        PlayerInfoRecord record = lookup.record();
        String firstJoin = info.formatTimestamp(record.firstJoin());
        String names = lookup.nameHistory().stream()
                .map(PlayerNameRepository.NameHistoryEntry::name)
                .collect(Collectors.joining(", "));

        if (live != null) {
            plugin.getMessageManager().send(
                    sender,
                    Messages.INFO_WHOIS_HEADER,
                    "{player}", live.name(),
                    "{status}", "online",
                    "{tags}", live.tags()
            );
            plugin.getMessageManager().send(sender, Messages.INFO_WHOIS_UUID, "{uuid}", record.uuid().toString());
            plugin.getMessageManager().send(
                    sender,
                    Messages.INFO_WHOIS_DATES,
                    "{first_join}", firstJoin,
                    "{seen_label}", "Online for",
                    "{seen_value}", live.onlineFor()
            );
            if (sender.hasPermission(PlayerInfoService.PERMISSION_WHOIS_LOCATION)) {
                Location loc = live.location();
                if (loc != null && loc.getWorld() != null) {
                    plugin.getMessageManager().send(
                            sender,
                            Messages.INFO_WHOIS_LOCATION,
                            "{world}", loc.getWorld().getName(),
                            "{x}", Integer.toString(loc.getBlockX()),
                            "{y}", Integer.toString(loc.getBlockY()),
                            "{z}", Integer.toString(loc.getBlockZ())
                    );
                }
            }
        } else {
            String lastSeenAgo = info.formatDurationSince(record.lastSeen());
            String lastSeenDate = info.formatTimestamp(record.lastSeen());
            plugin.getMessageManager().send(
                    sender,
                    Messages.INFO_WHOIS_HEADER,
                    "{player}", record.lastName(),
                    "{status}", "offline",
                    "{tags}", ""
            );
            plugin.getMessageManager().send(sender, Messages.INFO_WHOIS_UUID, "{uuid}", record.uuid().toString());
            plugin.getMessageManager().send(
                    sender,
                    Messages.INFO_WHOIS_DATES,
                    "{first_join}", firstJoin,
                    "{seen_label}", "Last seen",
                    "{seen_value}", lastSeenAgo + " ago (" + lastSeenDate + ")"
            );
        }
        plugin.getMessageManager().send(sender, Messages.INFO_WHOIS_NAMES, "{names}", names);
        // Staff extras never reveal a vanished player's live state: they get the offline view.
        extras.sendWhois(sender, record.uuid(), live == null ? record.lastName() : live.name(),
                live == null ? null : live.details());
    }
}
