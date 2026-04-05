package net.survivalfun.core.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.chat.AlliumChannelManager;
import net.survivalfun.core.managers.core.Text;
import net.survivalfun.core.managers.lang.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ChannelCommand implements CommandExecutor, TabCompleter {

    private final PluginStart plugin;
    private final AlliumChannelManager channelManager;
    private final Lang lang;

    public ChannelCommand(PluginStart plugin, AlliumChannelManager channelManager) {
        this.plugin = plugin;
        this.channelManager = channelManager;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use chat channels.");
            return true;
        }

        if (commandName.equals("channel")) {
            if (!player.hasPermission("allium.channel")) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "/channel");
                return true;
            }

            if (args.length == 0) {
                channelManager.sendChannelInfo(player);
                return true;
            }

            if (!args[0].equalsIgnoreCase("join")) {
                player.sendMessage(Text.parseColors("&cUsage: /channel join <channel> [read|write]"));
                return true;
            }

            if (!player.hasPermission("allium.channel.join")) {
                Text.sendErrorMessage(player, "no-permission", lang, "{cmd}", "/channel join");
                return true;
            }

            if (args.length < 2) {
                player.sendMessage(Text.parseColors("&cUsage: /channel join <channel> [read|write]"));
                return true;
            }

            String channel = channelManager.resolveChannel(args[1]);
            if (channel == null) {
                player.sendMessage(Text.parseColors("&cUnknown channel: &f" + args[1]));
                return true;
            }

            String mode = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "write";
            switch (mode) {
                case "read" -> {
                    if (!channelManager.addReadChannel(player, channel)) {
                        player.sendMessage(Text.parseColors("&cYou cannot join &f" + channel + " &cin read mode."));
                        return true;
                    }
                    player.sendMessage(Text.parseColors("&8[&bChannels&8] &aNow reading &f" + channel + "&a."));
                    return true;
                }
                case "write" -> {
                    if (!channelManager.setWriteChannel(player, channel)) {
                        player.sendMessage(Text.parseColors("&cYou cannot join &f" + channel + " &cin write mode."));
                        return true;
                    }
                    player.sendMessage(Text.parseColors("&8[&bChannels&8] &aNow writing in &f" + channel + "&a."));
                    return true;
                }
                default -> {
                    player.sendMessage(Text.parseColors("&cMode must be &fread &cor &fwrite&c."));
                    return true;
                }
            }
        }

        String targetChannel;
        if (commandName.equals("a") || commandName.equals("staffchat")) {
            targetChannel = channelManager.getStaffChannelName();
            String current = channelManager.getWriteChannel(player.getUniqueId());
            if (targetChannel.equals(current)) {
                targetChannel = channelManager.getDefaultChannelName();
            }
        } else {
            targetChannel = channelManager.getDefaultChannelName();
        }

        if (!channelManager.setWriteChannel(player, targetChannel)) {
            player.sendMessage(Text.parseColors("&cYou cannot switch to &f" + targetChannel + "&c."));
            return true;
        }

        player.sendMessage(Text.parseColors("&8[&bChannels&8] &aNow writing in &f" + targetChannel + "&a."));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        if (!command.getName().equalsIgnoreCase("channel")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return partial(args[0], List.of("join"));
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            List<String> available = new ArrayList<>();
            for (AlliumChannelManager.ChannelDefinition channel : channelManager.getChannels()) {
                if (channelManager.canRead(player, channel.name()) || channelManager.canWrite(player, channel.name())) {
                    available.add(channel.name());
                }
            }
            return partial(args[1], available);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("join")) {
            return partial(args[2], List.of("read", "write"));
        }

        return Collections.emptyList();
    }

    private List<String> partial(String input, List<String> options) {
        String needle = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(needle)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
