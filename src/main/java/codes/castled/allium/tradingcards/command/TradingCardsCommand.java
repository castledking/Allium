package codes.castled.allium.tradingcards.command;

import codes.castled.allium.tradingcards.TradingCardsBranding;
import codes.castled.allium.tradingcards.TradingCardsModule;
import codes.castled.allium.tradingcards.card.CardDefinition;
import codes.castled.allium.tradingcards.card.Tier;
import codes.castled.allium.tradingcards.config.ValidationIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /tradingcards} admin command tree:
 * reload | give | list | inspect
 */
public final class TradingCardsCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TradingCardsModule module;

    public TradingCardsCommand(TradingCardsModule module) {
        this.module = module;
    }

    private static boolean can(CommandSender sender, String node) {
        return sender.hasPermission(TradingCardsBranding.PERMISSION_ROOT + "." + node)
            || sender.hasPermission(TradingCardsBranding.PERMISSION_ROOT + ".admin");
    }

    private static void msg(CommandSender sender, String miniMessage) {
        sender.sendMessage(MM.deserialize(
            "<gradient:#d4a04a:#8a5a2a>[Cards]</gradient> " + miniMessage));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!module.isEnabled()) {
            msg(sender, "<red>The trading card module is disabled "
                + "(see tradingcards/config.yml).</red>");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "give" -> give(sender, args);
            case "list" -> list(sender);
            case "inspect" -> inspect(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void reload(CommandSender sender) {
        if (!can(sender, "reload")) {
            msg(sender, "<red>You do not have permission for that.</red>");
            return;
        }
        // The module already logged every issue to the console in full; chat
        // gets a summary so a broken file cannot flood the player.
        List<ValidationIssue> issues = module.reload();
        long errors = issues.stream().filter(ValidationIssue::isError).count();
        long warnings = issues.size() - errors;
        msg(sender, "<green>Reloaded</green> <gray>—</gray> " + module.registry().size()
            + " card(s) across " + module.registry().mobCount() + " mob(s)");
        if (errors > 0 || warnings > 0) {
            msg(sender, "<gray>" + errors + " error(s), " + warnings
                + " warning(s); see console.</gray>");
        }
        if (errors > 0) {
            msg(sender, "<gray>Cards with errors were skipped; everything else applied.</gray>");
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (!can(sender, "give")) {
            msg(sender, "<red>You do not have permission for that.</red>");
            return;
        }
        if (args.length < 3) {
            msg(sender, "<gray>Usage: /tradingcards give <player> <card> [tier] [level] [quality]</gray>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(sender, "<red>Player '" + args[1] + "' is not online.</red>");
            return;
        }
        Optional<CardDefinition> definition =
            module.registry().byId(args[2].toLowerCase(Locale.ROOT));
        if (definition.isEmpty()) {
            msg(sender, "<red>No card configured with id '" + args[2] + "'.</red>");
            return;
        }
        Tier tier = args.length > 3
            ? CardDefinition.parseTier(args[3])
            : Tier.SIMPLE;
        if (tier == null) {
            msg(sender, "<red>Unknown tier '" + args[3] + "'.</red>");
            return;
        }
        if (definition.get().itemFor(tier) == null) {
            msg(sender, "<red>Card '" + definition.get().id() + "' has no item for "
                + tier + ".</red>");
            return;
        }
        int level = args.length > 4 ? parseInt(args[4], 0, 0, 1000) : 0;
        int quality = args.length > 5 ? parseInt(args[5], 100, 1, 100) : 100;

        Optional<ItemStack> card = module.factory().create(definition.get(), tier, level, quality,
            List.of("luck", "strength", "speed"), false, module.config());
        if (card.isEmpty()) {
            msg(sender, "<red>Could not build that card — the item does not resolve. "
                + "Is Nexo loaded?</red>");
            return;
        }
        var overflow = target.getInventory().addItem(card.get());
        overflow.values().forEach(rest ->
            target.getWorld().dropItemNaturally(target.getLocation(), rest));
        msg(sender, "<green>Gave</green> <yellow>" + tier + " " + definition.get().id()
            + "</yellow> <gray>to</gray> " + target.getName() + " <gray>— level " + level
            + ", quality " + quality + "%</gray>");
    }

    private void list(CommandSender sender) {
        if (!can(sender, "inspect")) {
            msg(sender, "<red>You do not have permission for that.</red>");
            return;
        }
        var cards = module.registry().byId();
        if (cards.isEmpty()) {
            msg(sender, "<gray>No cards configured.</gray>");
            return;
        }
        msg(sender, "<gray>" + cards.size() + " card(s) across "
            + module.registry().mobCount() + " mob(s):</gray>");
        for (var entry : cards.entrySet()) {
            CardDefinition card = entry.getValue();
            StringBuilder tiers = new StringBuilder();
            for (Tier tier : Tier.values()) {
                double weight = card.weightOf(tier);
                if (weight <= 0.0) continue;
                if (tiers.length() > 0) tiers.append(' ');
                tiers.append(tier.name().toLowerCase(Locale.ROOT)).append(' ')
                    .append(CardDefinition.trim(weight));
            }
            sender.sendMessage(MM.deserialize(
                "<gray>  <yellow>" + entry.getKey() + "</yellow> <dark_gray>("
                + card.mob() + ")</dark_gray> <gray>chance "
                + CardDefinition.trim(card.chance()) + "</gray>"));
            sender.sendMessage(MM.deserialize(
                "    <dark_gray>" + tiers + "</dark_gray>"));
        }
    }

    private void inspect(CommandSender sender, String[] args) {
        if (!can(sender, "inspect")) {
            msg(sender, "<red>You do not have permission for that.</red>");
            return;
        }
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Only a player can inspect a card in hand.</red>");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<codes.castled.allium.tradingcards.item.TradingCardData> data =
            codes.castled.allium.tradingcards.item.TradingCardData.read(hand);
        if (data.isEmpty()) {
            msg(sender, "<gray>That is not a trading card.</gray>");
            return;
        }
        var card = data.get();
        var bands = module.config().quality();
        var band = card.band(bands);
        msg(sender, "<gray>Mob:</gray> <yellow>" + card.mob()
            + "</yellow>  <gray>Id:</gray> " + card.cardId());
        msg(sender, "<gray>Tier:</gray> <yellow>" + card.tier().name()
            + "</yellow> " + card.tier().pipsWithPosition());
        msg(sender, "<gray>Level:</gray> <green>" + card.level()
            + "</green> <gray>/ " + module.config().levelling().maximumLevel() + "</gray>");
        msg(sender, "<gray>Quality:</gray> "
            + (band == null ? "<red>unknown (" + card.quality() + "%)"
                            : band.colour() + QualityBandDisplay.text(card, band))
            + " <gray>— worth " + card.headValue(bands) + " head(s)</gray>");
        msg(sender, "<gray>Signatures:</gray> <white>"
            + (card.signatures().isEmpty() ? "none" : String.join(", ", card.signatures()))
            + "</white> <dark_gray>(" + card.signatures().size() + ")</dark_gray>");
        if (card.rerolls() > 0) {
            msg(sender, "<gray>Rerolls:</gray> <white>" + card.rerolls() + "</white>");
        }
        if (card.bound()) {
            msg(sender, "<gray>Bound:</gray> <light_purple>crafted — cannot be traded</light_purple>");
        }
    }

    private static void sendHelp(CommandSender sender) {
        msg(sender, "<gray>Usage:</gray>");
        sender.sendMessage(MM.deserialize(
            "  <gray>/tradingcards reload</gray>"));
        sender.sendMessage(MM.deserialize(
            "  <gray>/tradingcards give <player> <card> [tier] [level] [quality]</gray>"));
        sender.sendMessage(MM.deserialize(
            "  <gray>/tradingcards list</gray>"));
        sender.sendMessage(MM.deserialize(
            "  <gray>/tradingcards inspect</gray> <dark_gray>(card in main hand)</dark_gray>"));
    }

    private static int parseInt(String raw, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ==================== tab completion ====================

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("reload", "give", "list", "inspect"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                default -> { }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            module.registry().byId().keySet().forEach(options::add);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            for (Tier tier : Tier.values()) {
                options.add(tier.name().toLowerCase(Locale.ROOT));
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream()
            .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
            .toList();
    }

    /** Renders a band's label with its percentage, in the band's own colour. */
    private static final class QualityBandDisplay {
        static String text(codes.castled.allium.tradingcards.item.TradingCardData card,
                           codes.castled.allium.tradingcards.card.QualityBand band) {
            return codes.castled.allium.tradingcards.card.QualityBand.displayId(band.id())
                + " (" + card.quality() + "%)";
        }
    }
}
