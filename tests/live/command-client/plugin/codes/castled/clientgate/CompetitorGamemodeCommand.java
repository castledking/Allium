package codes.castled.clientgate;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Competitor 'gamemode' family (aliases gm) owned by the ClientGate validation plugin. */
public final class CompetitorGamemodeCommand extends Command implements PluginIdentifiableCommand {
    static final List<String> SUGGESTIONS = List.of("competitor-creative", "competitor-survival");
    private final Plugin owner;

    CompetitorGamemodeCommand(Plugin owner) {
        super("gamemode", "competitor gamemode", "/gamemode <mode>", List.of("gm"));
        this.owner = owner;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        sender.sendMessage("CLIENTGATE-COMPETITOR-EXECUTED label=" + label + " args=" + String.join(",", args));
        owner.getLogger().info("CLIENTGATE_COMPETITOR_EXEC label=" + label + " sender=" + sender.getName());
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : SUGGESTIONS) if (s.startsWith(p)) out.add(s);
        }
        return out;
    }

    @Override
    public Plugin getPlugin() {
        return owner;
    }
}
