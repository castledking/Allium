package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.lang.LegacyLangImport;
import codes.castled.allium.lang.LegacyLangImporter;
import codes.castled.allium.message.Messages;
import codes.castled.allium.text.TextSender;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

/**
 * {@code /allium version}, {@code /allium reload} (see {@link PluginStart#reloadConfiguration}) and
 * {@code /allium import lang [apply]} (see {@link LegacyLangImporter}) and
 * {@code /allium adopt-window [<duration>|off]}.
 */
public final class AlliumCommand implements SpecCommandHandler {

    private final PluginStart plugin;

    public AlliumCommand(PluginStart plugin) {
        this.plugin = plugin;
    }

    @Override
    public CommandSpec spec() {
        return CoreCommandSpecs.ALLIUM;
    }

    @Override
    public void execute(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        String action = context.args().length == 0 ? "version" : context.args()[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "version" -> context.sendOutcome(sender, "version", "{version}", plugin.getDescription().getVersion());
            case "reload" -> {
                if (!sender.hasPermission("allium.reload")) {
                    plugin.getMessageManager().sendByKey(sender, spec().messageKeys().noPermissionKey(), "{cmd}", "allium reload");
                    return;
                }
                boolean ok = plugin.reloadConfiguration(line -> TextSender.send(sender, "&7" + line));
                context.sendOutcome(sender, ok ? "reloaded" : "reload-failed");
            }
            case "import" -> {
                if (!sender.hasPermission("allium.import")) {
                    plugin.getMessageManager().sendByKey(sender, spec().messageKeys().noPermissionKey(), "{cmd}", "allium import");
                    return;
                }
                String[] args = context.args();
                boolean apply = args.length == 3 && args[2].equalsIgnoreCase("apply");
                if (args.length < 2 || !args[1].equalsIgnoreCase("lang") || (args.length == 3 && !apply) || args.length > 3) {
                    context.sendInvalidArgs("/allium import lang [apply]");
                    return;
                }
                importLang(sender, apply);
            }
            case "adopt-window" -> adoptWindow(context);
            default -> context.sendInvalidArgs(spec().selfUsage());
        }
    }

    /** The longest window: it is meant to be open only briefly, during a migration. */
    static final long MAX_ADOPT_WINDOW_MILLIS = 60L * 60L * 1000L;

    /**
     * {@code /allium adopt-window [<duration>|off]}: opens (or closes) the time-limited window in
     * which pre-recode privileged tools (the Phantom Obliterator) are adopted on first use. Off by
     * default, and every adoption is logged. The console may always use it. Anyone else needs
     * {@code allium.adoptwindow}, which defaults to no one (Bukkit's {@code default: false} also
     * leaves it off for the console's permission check, hence the explicit console case).
     */
    private void adoptWindow(BukkitCommandContext context) {
        CommandSender sender = context.sender();
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !sender.hasPermission("allium.adoptwindow")) {
            plugin.getMessageManager().sendByKey(sender, spec().messageKeys().noPermissionKey(), "{cmd}", "allium adopt-window");
            return;
        }
        String[] args = context.args();
        codes.castled.allium.item.ItemService items = plugin.getItemService();
        if (args.length == 1) {
            long left = items.privilegedAdoptionWindowRemainingMillis();
            plugin.getMessageManager().send(sender, Messages.ADOPT_WINDOW_STATUS, "{state}",
                    left > 0 ? "open for " + formatDuration(left) : "closed");
            return;
        }
        if (args.length != 2) {
            context.sendInvalidArgs("/allium adopt-window [<duration>|off]");
            return;
        }
        if (args[1].equalsIgnoreCase("off")) {
            items.disablePrivilegedAdoptionWindow();
            plugin.logger().warning("Adoption window closed by " + sender.getName() + ".");
            plugin.getMessageManager().send(sender, Messages.ADOPT_WINDOW_CLOSED);
            return;
        }
        long millis = parseDuration(args[1]);
        if (millis <= 0 || millis > MAX_ADOPT_WINDOW_MILLIS) {
            context.sendInvalidArgs("/allium adopt-window <1s-1h, e.g. 10m>|off");
            return;
        }
        items.enablePrivilegedAdoptionWindow(millis);
        plugin.logger().warning("Adoption window opened by " + sender.getName() + " for " + formatDuration(millis)
                + ": pre-recode privileged tools are adopted on first use, and each adoption is logged.");
        plugin.getMessageManager().send(sender, Messages.ADOPT_WINDOW_OPENED, "{duration}", formatDuration(millis));
    }

    /** {@code 90s}, {@code 10m}, {@code 1h}; -1 when it is not one of those. */
    static long parseDuration(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,7})([smh])").matcher(text.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return -1;
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> n * 1000L;
            case "m" -> n * 60_000L;
            default -> n * 3_600_000L;
        };
    }

    static String formatDuration(long millis) {
        long seconds = (millis + 999) / 1000;
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    private void importLang(CommandSender sender, boolean apply) {
        java.io.File source = new java.io.File(plugin.getDataFolder(), LegacyLangImporter.SOURCE);
        String sourceName = "plugins/" + plugin.getDataFolder().getName() + "/" + LegacyLangImporter.SOURCE;
        if (!source.isFile()) {
            plugin.getMessageManager().send(sender, Messages.IMPORT_LANG_NO_SOURCE, "{file}", sourceName);
            return;
        }
        try {
            String locale = plugin.getLanguageManager().getActiveLocale();
            LegacyLangImporter.Plan plan = LegacyLangImporter.plan(plugin.getDataFolder(), locale, plugin::getResource);
            String targetName = "lang/" + locale + ".yml";
            plugin.logger().info((apply ? "Lang import" : "Lang import (dry run)") + " from " + sourceName + " into " + targetName + ":");
            for (LegacyLangImporter.Item item : plan.items()) {
                LegacyLangImport.Entry e = item.entry();
                plugin.logger().info("  " + item.status() + " " + e.legacyKey() + " -> " + e.key()
                        + (e.sound() == null ? "" : " (sound " + e.sound()
                        + (e.volume() == null ? "" : " " + e.volume()) + (e.pitch() == null ? "" : " " + e.pitch()) + ")"));
            }
            plan.report().notes().forEach(note -> plugin.logger().info("  note: " + note));
            List<String> unmapped = plan.report().unmapped();
            if (!unmapped.isEmpty()) {
                plugin.logger().info("  not portable yet (" + unmapped.size() + "): "
                        + String.join(", ", unmapped.subList(0, Math.min(25, unmapped.size()))) + (unmapped.size() > 25 ? ", ..." : ""));
            }
            long write = plan.count(LegacyLangImporter.Status.WRITE);
            plugin.getMessageManager().send(sender, Messages.IMPORT_LANG_REPORT, "{mode}", apply ? "Import" : "Dry run",
                    "{write}", Long.toString(write), "{same}", Long.toString(plan.count(LegacyLangImporter.Status.SAME)),
                    "{kept}", Long.toString(plan.count(LegacyLangImporter.Status.KEPT)), "{unmapped}", Integer.toString(unmapped.size()));
            if (!apply) {
                return;
            }
            if (write == 0) {
                plugin.getMessageManager().send(sender, Messages.IMPORT_LANG_NOTHING, "{file}", targetName);
                return;
            }
            java.io.File backup = LegacyLangImporter.apply(plan);
            plugin.getLanguageManager().reload();
            plugin.logger().info("Lang import wrote " + write + " message(s) into " + targetName + "; backup " + backup.getName() + ".");
            plugin.getMessageManager().send(sender, Messages.IMPORT_LANG_APPLIED, "{write}", Long.toString(write),
                    "{file}", targetName, "{backup}", backup.getName());
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException | RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            plugin.logger().warning("Lang import failed: " + reason);
            plugin.getMessageManager().send(sender, Messages.IMPORT_LANG_FAILED, "{reason}", reason);
        }
    }
}
