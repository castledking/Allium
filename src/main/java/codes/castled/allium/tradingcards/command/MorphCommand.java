package codes.castled.allium.tradingcards.command;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.morph.MorphRules;
import codes.castled.allium.tradingcards.morph.MorphService;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /morph} — turn into the mob on your equipped FABLED card.
 *
 * <p>A toggle, not a timed effect: {@code duration: 0} in the config means the
 * morph lasts until the player runs the command again, quits, or is knocked
 * under the configured health floor.
 *
 * <p>Separate from {@code /tradingcards} because it is a player-facing verb that
 * belongs in the player's command bar, while {@code /tradingcards} is admin
 * tooling with a permission root.
 */
public final class MorphCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TradingCardsModule module;

    public MorphCommand(TradingCardsModule module) {
        this.module = module;
    }

    private static void msg(CommandSender sender, String miniMessage) {
        sender.sendMessage(MM.deserialize(
            "<gradient:#d4a04a:#8a5a2a>[Morph]</gradient> " + miniMessage));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Only players can morph.</red>");
            return true;
        }
        if (!sender.hasPermission(permissionNode())) {
            msg(player, "<red>You do not have permission for that.</red>");
            return true;
        }
        MorphService morphs = module.morphs();
        if (morphs == null || !module.isMorphAvailable()) {
            // Distinguished from "disabled": one is a server decision, the other
            // is a missing plugin, and the player can act on the second.
            msg(player, "<red>Morphing needs LibsDisguises, which is not "
                + "available on this server.</red>");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
            list(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            status(player, morphs);
            return true;
        }

        TradingCardData card = module.equippedCard(player.getUniqueId());
        MorphService.Result result = morphs.toggle(player, card);
        msg(player, "<gray>" + result.message() + "</gray>");
        return true;
    }

    private void list(Player player) {
        msg(player, "<gray>Morphs:</gray> <white>"
            + String.join(", ", MorphRules.names()) + "</white>");
    }

    private void status(Player player, MorphService morphs) {
        EntityType form = morphs.formOf(player);
        msg(player, "<gray>Form:</gray> <white>"
            + (form == null ? "none" : form.name()) + "</white>");
        msg(player, "<gray>Mobs pursuing you:</gray> <white>"
            + (morphs.isProvoked(player.getUniqueId()) ? "yes" : "no — you are unseen")
            + "</white>");
        MorphService.Config config = morphs.config();
        msg(player, "<gray>LibsDisguises:</gray> <white>"
            + (morphs.disguises().isFree() ? "free (self view only)" : "premium")
            + "</white>");
        msg(player, "<gray>Stealth until attacked:</gray> <white>"
            + (config.stealthUntilAttacked() ? "on" : "off") + "</white>");
        int remaining = morphs.secondsRemaining(player);
        if (remaining > 0) {
            msg(player, "<gray>Time left:</gray> <white>" + remaining + "s</white>");
        } else if (morphs.isTimed()) {
            msg(player, "<gray>This is a toggle, not a timed morph.</gray>");
        }
        msg(player, "<gray>Flight:</gray> <white>"
            + (config.allowFlight() ? "allowed for fliers" : "disabled") + "</white>");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        if (args.length != 1 || !(sender instanceof Player)) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("list", "status").stream().filter(s -> s.startsWith(prefix)).toList();
    }

    /** Node used by the command's own permission check. */
    public static String permissionNode() {
        return TradingCardsBranding.PERMISSION_ROOT + ".morph";
    }
}
