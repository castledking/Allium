package codes.castled.allium.command;

import codes.castled.allium.PluginStart;
import codes.castled.allium.api.SettingsPresentation;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.scheduler.BukkitServerThread;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

public record SettingsCommand(PluginStart plugin, boolean changes) implements SpecCommandHandler {
    private enum Toggle {
        TPA("Teleport requests", "tptoggle", EffectivePreferences::acceptTeleportRequests),
        MSG("Private messages", "msgtoggle", EffectivePreferences::acceptMessages),
        PAY("Incoming payments", "paytoggle", EffectivePreferences::acceptPayments);

        final String title;
        final String command;
        final Predicate<EffectivePreferences> enabled;

        Toggle(String title, String command, Predicate<EffectivePreferences> enabled) {
            this.title = title;
            this.command = command;
            this.enabled = enabled;
        }

        String key() { return name().toLowerCase(Locale.ROOT); }

        boolean allowed(Player player, PluginStart plugin) {
            return player.hasPermission("allium.toggle." + key()) && player.hasPermission("allium." + command)
                    && !plugin.getCommandManager().getFamilyPolicy().isDisabled(command)
                    && plugin.getServer().getPluginCommand("allium:" + command) != null;
        }
    }

    @Override public CommandSpec spec() { return changes ? CoreCommandSpecs.TOGGLE : CoreCommandSpecs.TOGGLES; }

    @Override public void execute(BukkitCommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            plugin.getMessageManager().send(context.sender(), Messages.PLAYER_ONLY);
            return;
        }
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), player, () -> executeForPlayer(context, player));
    }

    private void executeForPlayer(BukkitCommandContext context, Player player) {
        String[] args = context.args();
        if (!changes && args.length == 0) {
            String label = context.label();
            if (label.substring(label.lastIndexOf(':') + 1).equalsIgnoreCase("settings")) {
                var presentation = plugin.getServer().getServicesManager().load(SettingsPresentation.class);
                if (presentation != null) {
                    try {
                        if (presentation.open(player)) return;
                    } catch (RuntimeException failure) {
                        plugin.logger().warning("Could not open the settings menu: " + failure.getMessage());
                    }
                }
            }
            show(player);
            return;
        }
        if (changes && args.length >= 1 && args.length <= 2
                && (args.length == 1 || args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            for (Toggle toggle : Toggle.values()) {
                if (!toggle.key().equalsIgnoreCase(args[0])) continue;
                if (!toggle.allowed(player, plugin)) plugin.getMessageManager().send(player, Messages.NO_PERMISSION);
                else player.performCommand("allium:" + toggle.command
                        + (args.length == 1 ? "" : " " + args[1].toLowerCase(Locale.ROOT)));
                return;
            }
        }
        context.sendInvalidArgs(spec().selfUsage());
    }

    private void show(Player player) {
        var preferences = plugin.getModuleManager().getModule("preferences");
        EffectivePreferences values = preferences instanceof PreferencesModule module && module.isEnabled()
                ? module.effective(player.getUniqueId()).orElse(null) : null;
        if (values == null) {
            plugin.getMessageManager().send(player, Messages.PREFERENCES_UNAVAILABLE, "{player}", player.getName());
            return;
        }
        player.sendMessage("§dPlayer settings");
        for (Toggle toggle : Toggle.values()) {
            if (!toggle.allowed(player, plugin)) continue;
            boolean on = toggle.enabled.test(values);
            var row = new TextComponent(toggle.title + " ");
            var button = new TextComponent(on ? "§a[On]" : "§c[Off]");
            button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/allium:toggle " + toggle.key() + (on ? " off" : " on")));
            row.addExtra(button);
            player.spigot().sendMessage(row);
        }
    }

    @Override public Optional<List<String>> customCompletions(CommandSender sender, String label, String[] args) {
        if (!changes || !(sender instanceof Player player)) return Optional.of(List.of());
        if (args.length == 1) return Optional.of(Arrays.stream(Toggle.values())
                .filter(toggle -> toggle.allowed(player, plugin)).map(Toggle::key)
                .filter(key -> key.startsWith(args[0].toLowerCase(Locale.ROOT))).toList());
        if (args.length == 2 && Arrays.stream(Toggle.values()).anyMatch(toggle ->
                toggle.key().equalsIgnoreCase(args[0]) && toggle.allowed(player, plugin))) {
            return Optional.of(List.of("on", "off").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList());
        }
        return Optional.of(List.of());
    }
}
