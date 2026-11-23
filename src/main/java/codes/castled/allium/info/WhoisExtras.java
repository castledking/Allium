package codes.castled.allium.info;

import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.alts.AltService;
import codes.castled.allium.compat.GodModeCompatProvider;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.AltsModule;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.text.SpigotComponents;
import codes.castled.allium.text.TextFormatter;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The staff-only parts of {@code /whois} ({@code allium.whois.extra}): gamemode, flight, god,
 * vitals, op/mute/ban/freeze/jail state, IP ({@code allium.whois.ip}), alt accounts, and the
 * clickable actions configured under {@code info.whois.actions} (legacy Allium's CoreProtect,
 * LiteBans and ChatSentry links by default), plus the alt and IP lines of {@code /seen}.
 */
public final class WhoisExtras {

    public static final String PERMISSION_EXTRA = "allium.whois.extra";
    public static final String PERMISSION_WHOIS_IP = "allium.whois.ip";
    public static final String PERMISSION_SEEN_ALTS = "allium.seen.alts";
    public static final String PERMISSION_SEEN_IP = "allium.seen.ip";

    private final PluginStart plugin;

    public WhoisExtras(PluginStart plugin) {
        this.plugin = plugin;
    }

    private String yesNo(boolean value) {
        return plugin.getMessageManager().resolve(value ? Messages.INFO_YES : Messages.INFO_NO);
    }

    @Nullable
    private AltService alts() {
        return plugin.getModuleManager().getModule("alts") instanceof AltsModule module && module.isEnabled() ? module.alts() : null;
    }

    @Nullable
    private ModerationModule moderation() {
        return plugin.getModuleManager().getModule("moderation") instanceof ModerationModule module && module.isEnabled() ? module : null;
    }

    public record LiveDetails(String[] state, String[] vitals, @Nullable String ip) {}

    /** Reads live details on the target's owning thread. */
    public LiveDetails capture(Player online, boolean includeIp) {
        String[] state = {
                    "{gamemode}", codes.castled.allium.command.essentials.UtilitySupport.titleCase(online.getGameMode().name()),
                    "{fly}", yesNo(online.getAllowFlight()), "{flying}", yesNo(online.isFlying()),
                    "{god}", yesNo(GodModeCompatProvider.current().isGodMode(online)),
                    "{vanished}", yesNo(PlayerNameVisibility.isVanished(online)),
                    "{afk}", yesNo(afk(online))};
        String[] vitals = {
                    "{health}", oneDecimal(online.getHealth()), "{max_health}", oneDecimal(codes.castled.allium.compat.HealthCompatProvider.current().getMaxHealth(online)),
                    "{food}", Integer.toString(online.getFoodLevel()), "{level}", Integer.toString(online.getLevel()),
                    "{ping}", Integer.toString(ping(online)), "{speed}", oneDecimal(online.getWalkSpeed() * 5)};
        var address = includeIp ? online.getAddress() : null;
        String ip = address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
        return new LiveDetails(state, vitals, ip);
    }

    /** Sends the extra lines on the requester's thread. */
    public void sendWhois(CommandSender sender, UUID uuid, String name, @Nullable LiveDetails online) {
        if (!sender.hasPermission(PERMISSION_EXTRA)) {
            return;
        }
        var messages = plugin.getMessageManager();
        if (online != null) {
            messages.send(sender, Messages.INFO_WHOIS_STATE, online.state());
            messages.send(sender, Messages.INFO_WHOIS_VITALS, online.vitals());
        }
        OfflinePlayer offline = plugin.getServer().getOfflinePlayer(uuid);
        ModerationModule moderation = moderation();
        messages.send(sender, Messages.INFO_WHOIS_FLAGS,
                "{op}", yesNo(offline.isOp()),
                "{muted}", yesNo(moderation != null && moderation.moderation().isMuted(uuid)),
                "{banned}", yesNo(moderation != null && moderation.discipline() != null && moderation.discipline().isBanned(uuid)),
                "{whitelisted}", yesNo(offline.isWhitelisted()),
                "{frozen}", yesNo(moderation != null && moderation.discipline() != null && moderation.discipline().isFrozen(uuid)),
                "{jailed}", yesNo(moderation != null && moderation.discipline() != null && moderation.discipline().jailed(uuid)));
        AltService alts = alts();
        if (alts != null) {
            boolean showIp = sender.hasPermission(PERMISSION_WHOIS_IP);
            sendAltLines(sender, alts, uuid, showIp, true, () -> sendActions(sender, uuid, name));
        } else {
            if (online != null && sender.hasPermission(PERMISSION_WHOIS_IP) && online.ip() != null) {
                messages.send(sender, Messages.INFO_WHOIS_IP, "{ip}", online.ip());
            }
            sendActions(sender, uuid, name);
        }
    }

    /** {@code /seen}'s alt and IP lines, for holders of {@code allium.seen.alts} / {@code allium.seen.ip}. */
    public void sendSeen(CommandSender sender, UUID uuid) {
        AltService alts = alts();
        boolean showAlts = sender.hasPermission(PERMISSION_SEEN_ALTS);
        boolean showIp = sender.hasPermission(PERMISSION_SEEN_IP);
        if (alts == null || !showAlts && !showIp) {
            return;
        }
        sendAltLines(sender, alts, uuid, showIp, showAlts, () -> { });
    }

    private void sendAltLines(CommandSender sender, AltService alts, UUID uuid, boolean showIp, boolean showAlts, Runnable then) {
        CompletableFuture<AltService.Summary> summary = alts.summary(uuid);
        summary.whenComplete((result, error) -> BukkitServerThread.runFor(plugin, plugin.getScheduler(), sender, () -> {
            if (sender instanceof Player p && !p.isOnline()) {
                return;
            }
            if (error != null) {
                plugin.getMessageManager().send(sender, Messages.ALTS_UNAVAILABLE);
            } else {
                if (showIp) {
                    plugin.getMessageManager().send(sender, Messages.INFO_WHOIS_IP, "{ip}",
                            result.lastIp().orElse(plugin.getMessageManager().resolve(Messages.INFO_NONE)));
                }
                if (showAlts) {
                    plugin.getMessageManager().send(sender, Messages.INFO_WHOIS_ALTS, "{accounts}", result.linkedNames().isEmpty()
                            ? plugin.getMessageManager().resolve(Messages.INFO_NONE) : String.join(", ", result.linkedNames()));
                }
            }
            then.run();
        }, () -> {}, 0L));
    }

    /**
     * The clickable actions under {@code info.whois.actions}: each has {@code text}, optional
     * {@code hover}, {@code click} (run-command, suggest-command, copy-to-clipboard, open-url),
     * {@code value} and an optional {@code permission}. {@code {player}} and {@code {uuid}} are
     * replaced. Only players see them; the console cannot click.
     */
    public void sendActions(CommandSender sender, UUID uuid, String name) {
        if (!(sender instanceof Player player)) {
            return;
        }
        ConfigurationSection actions = plugin.getConfig().getConfigurationSection("info.whois.actions");
        if (actions == null || actions.getKeys(false).isEmpty()) {
            return;
        }
        boolean header = false;
        for (String key : actions.getKeys(false)) {
            ConfigurationSection action = actions.getConfigurationSection(key);
            if (action == null) {
                continue;
            }
            String permission = action.getString("permission", "");
            if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
                continue;
            }
            String text = fill(action.getString("text", key), uuid, name);
            String value = fill(action.getString("value", ""), uuid, name);
            ClickEvent.Action click = switch (action.getString("click", "run-command").toLowerCase(Locale.ROOT)) {
                case "suggest-command" -> ClickEvent.Action.SUGGEST_COMMAND;
                case "copy-to-clipboard" -> ClickEvent.Action.COPY_TO_CLIPBOARD;
                case "open-url" -> ClickEvent.Action.OPEN_URL;
                default -> ClickEvent.Action.RUN_COMMAND;
            };
            if (!header) {
                plugin.getMessageManager().send(player, Messages.INFO_WHOIS_ACTIONS);
                header = true;
            }
            BaseComponent[] parts = SpigotComponents.of(TextFormatter.parse(text));
            String hover = action.getString("hover", "");
            for (BaseComponent part : parts) {
                if (!value.isEmpty()) {
                    part.setClickEvent(new ClickEvent(click, value));
                }
                if (hover != null && !hover.isEmpty()) {
                    part.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            SpigotComponents.of(TextFormatter.parse(fill(hover, uuid, name)))));
                }
            }
            player.spigot().sendMessage(parts);
        }
    }

    private static String fill(@Nullable String template, UUID uuid, String name) {
        return template == null ? "" : template.replace("{player}", name).replace("{uuid}", uuid.toString());
    }

    private boolean afk(Player player) {
        return plugin.getModuleManager().getModule("info") instanceof codes.castled.allium.module.PlayerInfoModule info
                && info.isEnabled() && info.info().isAfk(player);
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static int ping(Player player) {
        try {
            return player.getPing();
        } catch (LinkageError ignored) {
            return -1;
        }
    }
}
