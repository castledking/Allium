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
 * {@code /allium import lang [apply]} (see {@link LegacyLangImporter}), {@code /allium import vouchers
 * [apply]} and
 * {@code /allium adopt-window [<duration>|off]}.
 */
public final class AlliumCommand implements SpecCommandHandler {

    private final PluginStart plugin;
    private codes.castled.allium.importer.LegacyImportService legacyImport;

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
                if (args.length >= 2 && args[1].equalsIgnoreCase("legacy")) {
                    if (args.length == 2) legacyImport().dryRun(sender);
                    else if (args.length == 4 && args[2].equalsIgnoreCase("apply")) legacyImport().apply(sender, args[3]);
                    else context.sendInvalidArgs("/allium import legacy [apply <run>]");
                    return;
                }
                boolean apply = args.length == 3 && args[2].equalsIgnoreCase("apply");
                boolean vouchers = args.length >= 2 && args[1].equalsIgnoreCase("vouchers");
                if (args.length < 2 || !(vouchers || args[1].equalsIgnoreCase("lang")) || (args.length == 3 && !apply) || args.length > 3) {
                    context.sendInvalidArgs("/allium import <lang [apply]|vouchers [apply]|legacy [apply <run>]>");
                    return;
                }
                if (vouchers) importVouchers(sender, apply);
                else importLang(sender, apply);
            }
            case "adopt-window" -> adoptWindow(context);
            case "escalate" -> {
                // Pre-recode Allium's /core escalate <staffmember> <target> <reason>, for the console and scripts.
                if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                    plugin.getMessageManager().send(sender, Messages.CONSOLE_ONLY);
                    return;
                }
                String[] args = context.args();
                if (args.length < 4) {
                    context.sendInvalidArgs("/allium escalate <staffmember> <target> <reason>");
                    return;
                }
                if (plugin.getModuleManager().getModule("moderation") instanceof codes.castled.allium.module.ModerationModule moderation
                        && moderation.isEnabled() && moderation.reports() != null) {
                    moderation.reports().escalate(sender, args[1], args[2], String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
                } else {
                    plugin.getMessageManager().send(sender, Messages.MODULE_DISABLED);
                }
            }
            case "sethomes" -> {
                if (plugin.getModuleManager().getModule("travel") instanceof codes.castled.allium.module.TravelModule travel && travel.isEnabled()) {
                    travel.travel().sethomesAdmin(sender, java.util.Arrays.copyOfRange(context.args(), 1, context.args().length));
                } else {
                    plugin.getMessageManager().send(sender, Messages.MODULE_DISABLED);
                }
            }
            case "creativemanager" -> {
                if (plugin.getModuleManager().getModule("creative") instanceof codes.castled.allium.module.CreativeModule creative
                        && creative.isEnabled() && creative.manageCommand() != null) {
                    // /allium's card lists two creativemanager forms; any mistake shows both.
                    creative.manageCommand().run(sender, java.util.Arrays.copyOfRange(context.args(), 1, context.args().length),
                            "/allium creativemanager", ignored -> context.sendInvalidArgs("/allium creativemanager"));
                } else {
                    plugin.getMessageManager().send(sender, Messages.MODULE_DISABLED);
                }
            }
            case "hide" -> {
                if(plugin.getModuleManager().getModule("hide") instanceof codes.castled.allium.module.HideModule hide && hide.isEnabled())
                    hide.hide().admin(sender,java.util.Arrays.copyOfRange(context.args(),1,context.args().length),context::sendInvalidArgs);
                else plugin.getMessageManager().send(sender,Messages.MODULE_DISABLED);
            }
            default -> context.sendInvalidArgs(spec().selfUsage());
        }
    }

    /** One worker for legacy imports, created on first use; the scheduler stops it at shutdown. */
    private synchronized codes.castled.allium.importer.LegacyImportService legacyImport() {
        if (legacyImport == null) {
            legacyImport = new codes.castled.allium.importer.LegacyImportService(plugin, plugin.getScheduler().boundedPool("Allium-Import", 1, 2));
        }
        return legacyImport;
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

    /**
     * {@code /allium import vouchers [apply]}: makes a powertool of each voucher in pre-recode Allium's
     * {@code vouchers.yml} (see {@link codes.castled.allium.importer.VoucherImport}). A dry run reports;
     * {@code apply} writes the new {@code tools/voucher_<id>.yml} files (never replacing one) and
     * reloads the tools.
     */
    private void importVouchers(CommandSender sender, boolean apply) {
        java.io.File source = new java.io.File(plugin.getDataFolder(), codes.castled.allium.importer.VoucherImport.SOURCE);
        String sourceName = "plugins/" + plugin.getDataFolder().getName() + "/" + codes.castled.allium.importer.VoucherImport.SOURCE;
        if (!source.isFile()) {
            plugin.getMessageManager().send(sender, Messages.IMPORT_VOUCHERS_NO_SOURCE, "{file}", sourceName);
            return;
        }
        if (!(plugin.getModuleManager().getModule("tools") instanceof codes.castled.allium.module.ToolsModule tools)
                || !tools.isEnabled() || tools.powerTools() == null) {
            plugin.getMessageManager().send(sender, Messages.MODULE_DISABLED);
            return;
        }
        try {
            if (source.length() > 1_048_576L) throw new java.io.IOException(sourceName + " exceeds 1 MiB");
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            yaml.load(source);
            java.io.File toolsDir = new java.io.File(plugin.getDataFolder(), codes.castled.allium.tool.ToolDefinitionLoader.DIRECTORY);
            java.util.Set<String> labels = new java.util.HashSet<>();
            plugin.getDescription().getCommands().forEach((name, settings) -> {
                labels.add(name.toLowerCase(Locale.ROOT));
                if (settings.get("aliases") instanceof List<?> aliases) {
                    aliases.forEach(alias -> labels.add(String.valueOf(alias).toLowerCase(Locale.ROOT)));
                }
            });
            plugin.getCommandManager().getRenderedCommands().values().forEach(rendered -> labels.addAll(rendered.spec().bareLabels()));
            var settings = tools.powerTools().settings();
            codes.castled.allium.importer.VoucherImport.Plan plan = codes.castled.allium.importer.VoucherImport.plan(yaml,
                    new codes.castled.allium.importer.VoucherImport.Context(labels, settings.consoleAllowlist(), settings.blockSelectors(),
                            name -> existingTool(new java.io.File(toolsDir, name + ".yml")),
                            document -> tools.validatePowerTool(String.valueOf(document.get("id")).replace("allium:", ""), document)));
            plugin.logger().info((apply ? "Voucher import" : "Voucher import (dry run)") + " from " + sourceName + ":");
            for (codes.castled.allium.importer.VoucherImport.Item item : plan.items()) {
                plugin.logger().info("  " + item.status() + " " + item.legacyId() + " -> "
                        + (item.toolName() == null ? "(no tool name)" : "tools/" + item.toolName() + ".yml"));
                item.notes().forEach(note -> plugin.logger().info("    note: " + note));
            }
            long write = plan.count(codes.castled.allium.importer.VoucherImport.Status.WRITE);
            plugin.getMessageManager().send(sender, Messages.IMPORT_VOUCHERS_REPORT, "{mode}", apply ? "Import" : "Dry run",
                    "{write}", Long.toString(write),
                    "{same}", Long.toString(plan.count(codes.castled.allium.importer.VoucherImport.Status.SAME)),
                    "{kept}", Long.toString(plan.count(codes.castled.allium.importer.VoucherImport.Status.KEPT)),
                    "{blocked}", Long.toString(plan.count(codes.castled.allium.importer.VoucherImport.Status.BLOCKED)));
            if (!apply) {
                return;
            }
            if (write == 0) {
                plugin.getMessageManager().send(sender, Messages.IMPORT_VOUCHERS_NOTHING);
                return;
            }
            java.nio.file.Files.createDirectories(toolsDir.toPath());
            int written = 0;
            for (codes.castled.allium.importer.VoucherImport.Item item : plan.items()) {
                if (item.status() != codes.castled.allium.importer.VoucherImport.Status.WRITE || item.document() == null) continue;
                java.nio.file.Path target = toolsDir.toPath().resolve(item.toolName() + ".yml");
                java.nio.file.Path temp = java.nio.file.Files.createTempFile(toolsDir.toPath(), ".voucher-", ".tmp");
                try {
                    java.nio.file.Files.writeString(temp, codes.castled.allium.importer.VoucherImport.yaml(item.document()));
                    // Never replaces a file: one that appeared since the plan stays.
                    java.nio.file.Files.move(temp, target);
                    written++;
                    plugin.logger().info("  wrote tools/" + item.toolName() + ".yml (" + item.legacyId() + ")");
                } catch (java.nio.file.FileAlreadyExistsException appeared) {
                    plugin.logger().warning("  tools/" + item.toolName() + ".yml appeared since the plan; it is kept.");
                } finally {
                    java.nio.file.Files.deleteIfExists(temp);
                }
            }
            tools.reloadPowerTools();
            plugin.logger().info("Voucher import wrote " + written + " powertool(s). Give them with /pt give <player> <tool>; vouchers"
                    + " players already hold become these tools when used while /allium adopt-window is open.");
            plugin.getMessageManager().send(sender, Messages.IMPORT_VOUCHERS_APPLIED, "{write}", Integer.toString(written));
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException | RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            plugin.logger().warning("Voucher import failed: " + reason);
            plugin.getMessageManager().send(sender, Messages.IMPORT_VOUCHERS_FAILED, "{reason}", reason);
        }
    }

    /** A tool file's YAML after a round trip, to compare with a planned one; null when there is none. */
    @org.jetbrains.annotations.Nullable
    private static String existingTool(java.io.File file) {
        if (!file.isFile()) return null;
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.load(file);
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException unreadable) {
            return "";
        }
        return yaml.saveToString();
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
