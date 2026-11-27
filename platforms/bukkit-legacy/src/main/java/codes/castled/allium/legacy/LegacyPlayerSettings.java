package codes.castled.allium.legacy;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

final class LegacyPlayerSettings {
    enum Setting {
        TPA("tpa", "tptoggle", "Teleport requests", true),
        MSG("msg", "msgtoggle", "Private messages", true),
        PAY("pay", "paytoggle", "Payments", true),
        TP_AUTO("tp-auto", "tpauto", "Auto-accepting teleport requests", false);

        private final String key;
        private final String command;
        private final String title;
        private final boolean defaultValue;

        Setting(String key, String command, String title, boolean defaultValue) {
            this.key = key;
            this.command = command;
            this.title = title;
            this.defaultValue = defaultValue;
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final LegacyCommandManager commands;
    private final LegacyEconomyModule economy;
    private YamlConfiguration data = new YamlConfiguration();
    private boolean writable;

    LegacyPlayerSettings(JavaPlugin plugin, LegacyCommandManager commands, LegacyEconomyModule economy) {
        this.plugin = plugin;
        this.commands = commands;
        this.economy = economy;
        this.file = new File(plugin.getDataFolder(), "player-settings-legacy.yml");
    }

    void reload() {
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            if (file.exists()) loaded.load(file);
            data = loaded;
            writable = true;
        } catch (IOException | InvalidConfigurationException error) {
            writable = false;
            plugin.getLogger().warning("Could not load " + file.getName() + ": " + error.getMessage());
        }
    }

    boolean enabled(UUID player, Setting setting) {
        return setting == Setting.PAY ? economy.acceptsPayments(player)
                : data.getBoolean(path(player, setting), setting.defaultValue);
    }

    boolean direct(CommandSender sender, Setting setting, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can change these settings.");
            return true;
        }
        if (!sender.hasPermission("allium." + setting.command)) {
            sender.sendMessage(ChatColor.RED + "You cannot change that setting.");
            return true;
        }
        if (args.length > 1 || (args.length == 1 && !isState(args[0]))) return false;
        return change((Player) sender, setting, args.length == 0 ? null : args[0]);
    }

    boolean execute(CommandSender sender, boolean change, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use these settings.");
            return true;
        }
        Player player = (Player) sender;
        if (!change) {
            if (args.length != 0) return false;
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Player settings");
            for (Setting setting : Setting.values()) {
                if (!allowed(player, setting)) continue;
                boolean on = enabled(player.getUniqueId(), setting);
                String text = setting.title + (on ? ChatColor.GREEN + " [On]" : ChatColor.RED + " [Off]");
                LegacyCompat.sendInteractive(player, text, "/allium:toggle " + setting.key + (on ? " off" : " on"),
                        true, ChatColor.GRAY + "Click to change this setting.");
            }
            return true;
        }
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !isState(args[1]))) return false;
        Setting setting = find(args[0]);
        if (setting == null) return false;
        if (!allowed(player, setting)) {
            player.sendMessage(ChatColor.RED + "You cannot change that setting.");
            return true;
        }
        return change(player, setting, args.length == 2 ? args[1] : null);
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) || !sender.hasPermission("allium.toggles")) return Collections.emptyList();
        Player player = (Player) sender;
        List<String> values = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Setting setting : Setting.values()) {
                if (allowed(player, setting) && setting.key.startsWith(prefix)) values.add(setting.key);
            }
        } else if (args.length == 2) {
            Setting setting = find(args[0]);
            if (setting != null && allowed(player, setting)) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                if ("on".startsWith(prefix)) values.add("on");
                if ("off".startsWith(prefix)) values.add("off");
            }
        }
        return values;
    }

    private boolean change(Player player, Setting setting, String state) {
        UUID id = player.getUniqueId();
        boolean on = state == null ? !enabled(id, setting) : "on".equalsIgnoreCase(state);
        boolean saved = setting == Setting.PAY ? economy.setAcceptsPayments(id, on) : save(id, setting, on);
        if (!saved) {
            player.sendMessage(ChatColor.RED + "Your setting could not be saved.");
        } else {
            player.sendMessage((on ? ChatColor.GREEN : ChatColor.GRAY) + setting.title + (on ? " enabled." : " disabled."));
        }
        return true;
    }

    private boolean save(UUID player, Setting setting, boolean on) {
        if (enabled(player, setting) == on) return true;
        return saveValue(path(player, setting), on == setting.defaultValue ? null : Boolean.valueOf(on));
    }

    Optional<Boolean> replyToLastRecipient(UUID player) {
        String path = "players." + player + ".reply-to-last-recipient";
        return data.isBoolean(path) ? Optional.of(data.getBoolean(path)) : Optional.empty();
    }

    boolean setReplyToLastRecipient(UUID player, boolean lastRecipient) {
        return saveValue("players." + player + ".reply-to-last-recipient", lastRecipient);
    }

    Set<String> unlimitedBlocks(UUID player) {
        return new TreeSet<>(data.getStringList("players." + player + ".unlimited-blocks"));
    }

    boolean setUnlimitedBlocks(UUID player, Set<String> blocks) {
        return saveValue("players." + player + ".unlimited-blocks", blocks.isEmpty() ? null : new ArrayList<>(blocks));
    }

    private boolean saveValue(String path, Object value) {
        if (!writable) return false;
        Object previous = data.get(path);
        data.set(path, value);
        try {
            replaceFile(data, file);
            return true;
        } catch (IOException error) {
            data.set(path, previous);
            plugin.getLogger().warning("Could not save " + file.getName() + ": " + error.getMessage());
            return false;
        }
    }

    static void replaceFile(YamlConfiguration values, File file) throws IOException {
        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), file.getName(), ".tmp");
        try {
            values.save(temporary.toFile());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean allowed(Player player, Setting setting) {
        return setting != Setting.TP_AUTO && player.hasPermission("allium.toggle." + setting.key)
                && player.hasPermission("allium." + setting.command) && !commands.isDisabled(setting.command)
                && !commands.isModuleDisabled(setting.command);
    }

    private static Setting find(String key) {
        for (Setting setting : Setting.values()) {
            if (key.equalsIgnoreCase(setting.key)) return setting;
        }
        return null;
    }

    private static String path(UUID player, Setting setting) {
        return "players." + player + "." + setting.key;
    }

    private static boolean isState(String state) {
        return "on".equalsIgnoreCase(state) || "off".equalsIgnoreCase(state);
    }
}
