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
 * {@code /allium import lang [apply]} (see {@link LegacyLangImporter}).
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
            default -> context.sendInvalidArgs(spec().selfUsage());
        }
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
