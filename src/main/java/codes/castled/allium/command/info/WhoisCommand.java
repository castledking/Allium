package codes.castled.allium.command.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.SpecCommandHandler;
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

    public WhoisCommand(PluginStart plugin, PlayerInfoService info) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.info = Objects.requireNonNull(info, "info");
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

        info.lookupProfile(sender, query, true).whenComplete((opt, error) -> info.runOnServer(() -> {
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
            boolean visibleOnline = liveOnline != null
                    && liveOnline.isOnline()
                    && PlayerNameVisibility.canSeeTarget(sender, liveOnline);

            String firstJoin = info.formatTimestamp(record.firstJoin());
            String names = lookup.nameHistory().stream()
                    .map(PlayerNameRepository.NameHistoryEntry::name)
                    .collect(Collectors.joining(", "));

            if (visibleOnline) {
                String tags = (info.isAfk(liveOnline) ? " &7[AFK]" : "")
                        + (PlayerNameVisibility.isVanished(liveOnline) ? " &8[Vanished]" : "");
                String onlineFor = PlayerInfoService.formatElapsed(info.onlineDurationMillis(liveOnline, record));
                plugin.getMessageManager().send(
                        sender,
                        Messages.INFO_WHOIS_HEADER,
                        "{player}", liveOnline.getName(),
                        "{status}", "online",
                        "{tags}", tags
                );
                plugin.getMessageManager().send(sender, Messages.INFO_WHOIS_UUID, "{uuid}", record.uuid().toString());
                plugin.getMessageManager().send(
                        sender,
                        Messages.INFO_WHOIS_DATES,
                        "{first_join}", firstJoin,
                        "{seen_label}", "Online for",
                        "{seen_value}", onlineFor
                );
                if (sender.hasPermission(PlayerInfoService.PERMISSION_WHOIS_LOCATION)) {
                    Location loc = liveOnline.getLocation();
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
        }));
    }
}
