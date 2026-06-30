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
 * /voterestart [yes|no]
 *
 * Staff with allium.autorestart.vote:  /voterestart           → starts a vote
 * Regular players:                    /voterestart           → shows vote prompt or scheduled time
 *                                      /voterestart yes|no    → casts vote
 */
public class VoterRestartCommand implements CommandExecutor {
    private final PluginStart plugin;
    private final Lang lang;
    private AutoRestartCommand arCommand;

    public VoterRestartCommand(PluginStart plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLangManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        arCommand = plugin.getAutoRestartCommand();
        if (arCommand == null) {
            sender.sendMessage(Text.parseColors("&cAuto-restart is not available."));
            return true;
        }

        boolean canStartVote = sender.hasPermission("allium.autorestart.vote")
                || sender.hasPermission("allium.admin")
                || sender.isOp();

        if (args.length == 0) {
            if (canStartVote) {
                // Staff: start a vote
                arCommand.startVoteRestart(sender);
            } else if (sender instanceof Player player) {
                // Regular player: show vote prompt or scheduled time
                if (arCommand.isVoteActive()) {
                    showVotePrompt(player);
                } else {
                    showScheduledTime(player);
                }
            } else {
                sender.sendMessage(Text.parseColors("&cPlease specify yes or no."));
            }
            return true;
        }

        // /voterestart start — staff-only alias for starting a vote
        String arg = args[0].toLowerCase();
        if (arg.equals("start") && canStartVote) {
            arCommand.startVoteRestart(sender);
            return true;
        }

        // Regular players must be in-game to vote
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be run by a player.");
            return true;
        }

        if (arg.equals("no")) {
            arCommand.handleVoteNo(player);
        } else {
            // "yes" or anything else = yes
            arCommand.handleVote(player);
        }

        return true;
    }

    private void showVotePrompt(Player player) {
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
    }

    private void showScheduledTime(Player player) {
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
}
