package codes.castled.allium.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import codes.castled.allium.PluginStart;
import codes.castled.allium.managers.core.Text;
import codes.castled.allium.managers.lang.Lang;
import net.kyori.adventure.text.Component;

/**
 * /voterestart [yes|no] - Vote on an active restart vote.
 * With no args, prompts the player to choose yes or no.
 * If no vote is active, shows the next scheduled restart time.
 */
public class VoterRestartCommand implements CommandExecutor {
    private final PluginStart plugin;
    private final Lang lang;

    public VoterRestartCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be run by a player.");
            return true;
        }

        AutoRestartCommand arCommand = plugin.getAutoRestartCommand();
        if (arCommand == null) {
            player.sendMessage(Text.parseColors("&cAuto-restart is not available."));
            return true;
        }

        if (args.length == 0) {
            // No args — prompt the player to choose
            if (arCommand.isVoteActive()) {
                String yesText = lang.get("autorestart.vote-yes-text");
                String noText = lang.get("autorestart.vote-no-text");
                String hoverYes = lang.get("autorestart.vote-hover-yes");
                String hoverNo = lang.get("autorestart.vote-hover-no");
                String prompt = lang.get("autorestart.vote-prompt");

                Component promptComp = Text.colorize(prompt);
                Component yesButton = Text.colorize(yesText)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/voterestart yes"))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Text.colorize(hoverYes)));
                Component noButton = Text.colorize(noText)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/voterestart no"))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Text.colorize(hoverNo)));

                player.sendMessage(promptComp);
                player.sendMessage(Component.empty()
                        .append(yesButton)
                        .append(Text.colorize(" &7| "))
                        .append(noButton));
            } else {
                // No active vote — show next scheduled restart time
                if (arCommand.isRestartScheduled()) {
                    long timeLeft = Math.max(0L, java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(
                            arCommand.getRestartTime() - System.currentTimeMillis()));
                    String msg = lang.get("autorestart.vote-none-active")
                            .replace("{time}", Text.formatTime(timeLeft));
                    player.sendMessage(Text.parseColors(msg));
                } else {
                    player.sendMessage(Text.parseColors(lang.get("autorestart.vote-none-active-no-schedule")));
                }
            }
            return true;
        }

        // Explicit yes/no
        if (args[0].equalsIgnoreCase("no")) {
            arCommand.handleVoteNo(player);
        } else {
            // "yes" or anything else = yes
            arCommand.handleVote(player);
        }

        return true;
    }
}
