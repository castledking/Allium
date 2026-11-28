package codes.castled.allium.brigadier;

import codes.castled.allium.portable.NativeEconomy;
import codes.castled.allium.portable.NativeSettings;
import codes.castled.allium.portable.NativeWorth;
import codes.castled.allium.portable.PlayerNameSearch;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Shared Brigadier economy commands for native Fabric (1.14–26.3) and NeoForge (1.20.2–26.3):
 * {@code /balance}, {@code /pay}, {@code /paytoggle}, {@code /payon}, {@code /payoff},
 * {@code /payconfirmtoggle}, {@code /payconfirmon}, {@code /payconfirmoff},
 * {@code /eco}, {@code /baltop}, {@code /worth}, {@code /setworth}, and {@code /sell}.
 */
public final class NativeEconomyCommands {
    private static final int BALTOP_PAGE_SIZE = 10;

    private static final class ResolvedAccount<P> {
        final UUID id;
        final String name;
        final P online;

        ResolvedAccount(UUID id, String name, P online) {
            this.id = id;
            this.name = name;
            this.online = online;
        }
    }

    private NativeEconomyCommands() {}

    public static <S, P> void register(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                       NativeEconomy.PendingConfirmations confirmations,
                                       SuggestionProvider<S> onlinePlayers,
                                       SuggestionProvider<S> onOff) {
        SuggestionProvider<S> knownPlayers = (ctx, builder) -> {
            String rem = builder.getRemaining().toLowerCase(Locale.ROOT);
            Set<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            for (P online : platform.onlinePlayers(ctx.getSource())) {
                String n = platform.name(online);
                if (n != null) names.add(n);
            }
            names.addAll(new NativeEconomy(platform.dataDirectory(ctx.getSource())).knownNames());
            for (String n : names) {
                if (n.toLowerCase(Locale.ROOT).startsWith(rem)) builder.suggest(n);
            }
            return builder.buildFuture();
        };

        SuggestionProvider<S> worthItems = (ctx, builder) -> {
            for (String item : new NativeWorth(platform.dataDirectory(ctx.getSource())).suggestions(builder.getRemaining())) {
                builder.suggest(item);
            }
            return builder.buildFuture();
        };

        registerBalance(dispatcher, platform, knownPlayers);
        registerPay(dispatcher, platform, confirmations, onlinePlayers);
        registerPaymentToggles(dispatcher, platform, confirmations, knownPlayers, onOff);
        registerEco(dispatcher, platform, knownPlayers);
        registerBaltop(dispatcher, platform);
        registerWorthAndSell(dispatcher, platform, worthItems);
    }

    // ==================== /balance ====================

    private static <S, P> void registerBalance(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                               SuggestionProvider<S> knownPlayers) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal("allium:balance")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P player = requirePlayer(platform, source);
                    if (player == null) return 0;
                    NativeEconomy eco = new NativeEconomy(platform.dataDirectory(source));
                    BigDecimal bal = eco.balance(platform.id(player));
                    platform.reply(source, "Balance: " + NativeEconomy.format(bal));
                    return 1;
                })
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            ResolvedAccount<P> target = resolveAccount(platform, source,
                                    StringArgumentType.getString(ctx, "player"));
                            if (target == null) return 0;
                            NativeEconomy eco = new NativeEconomy(platform.dataDirectory(source));
                            platform.reply(source, target.name + "'s balance: "
                                    + NativeEconomy.format(eco.balance(target.id)));
                            return 1;
                        }));
        registerAliases(dispatcher, root, "balance", "bal", "money");
    }

    // ==================== /pay ====================

    private static <S, P> void registerPay(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                           NativeEconomy.PendingConfirmations confirmations,
                                           SuggestionProvider<S> onlinePlayers) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal("allium:pay")
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers)
                        .then(RequiredArgumentBuilder.<S, String>argument("amount", StringArgumentType.word())
                                .executes(ctx -> executePay(platform, confirmations, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "amount")))));
        registerAliases(dispatcher, root, "pay");
    }

    private static <S, P> int executePay(NativePlatform<S, P> platform,
                                         NativeEconomy.PendingConfirmations confirmations,
                                         S source, String targetQuery, String rawAmount) {
        P payer = requirePlayer(platform, source);
        if (payer == null) return 0;
        P target = resolveOnline(platform, source, targetQuery);
        if (target == null) return 0;
        UUID payerId = platform.id(payer);
        UUID targetId = platform.id(target);
        if (payerId.equals(targetId)) {
            platform.reply(source, "You cannot pay yourself.");
            return 0;
        }
        BigDecimal amount = NativeEconomy.parseAmount(rawAmount);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            platform.reply(source, "Invalid amount: " + rawAmount);
            return 0;
        }
        NativeEconomy eco = new NativeEconomy(platform.dataDirectory(source));
        NativeSettings settings = new NativeSettings(platform.dataDirectory(source));
        if (!platform.isGamemaster(source)) {
            if (!eco.acceptsPayments(targetId) || settings.isIgnoring(targetId, payerId)) {
                platform.reply(source, platform.name(target) + " is not accepting payments.");
                return 0;
            }
        }
        if (eco.balance(payerId).compareTo(amount) < 0) {
            platform.reply(source, "Insufficient funds. Your balance is "
                    + NativeEconomy.format(eco.balance(payerId)) + ".");
            return 0;
        }
        if (eco.requiresPayConfirm(payerId) && !confirmations.confirmOrArm(payerId, targetId, amount)) {
            platform.reply(source, "Run /pay " + platform.name(target) + " " + amount.toPlainString()
                    + " again within 30 seconds to confirm sending " + NativeEconomy.format(amount) + ".");
            return 1;
        }
        if (!eco.transfer(payerId, platform.name(payer), targetId, platform.name(target), amount)) {
            platform.reply(source, "Could not complete payment.");
            return 0;
        }
        String formatted = NativeEconomy.format(amount);
        platform.reply(source, "Sent " + formatted + " to " + platform.name(target) + ".");
        platform.send(target, "Received " + formatted + " from " + platform.name(payer) + ".");
        return 1;
    }

    // ==================== /paytoggle & /payconfirmtoggle ====================

    private static <S, P> void registerPaymentToggles(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                      NativeEconomy.PendingConfirmations confirmations,
                                                      SuggestionProvider<S> knownPlayers,
                                                      SuggestionProvider<S> onOff) {
        registerPayToggleFamily(dispatcher, platform, confirmations, knownPlayers, onOff, false);
        registerPayToggleFamily(dispatcher, platform, confirmations, knownPlayers, onOff, true);
    }

    private static <S, P> void registerPayToggleFamily(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                       NativeEconomy.PendingConfirmations confirmations,
                                                       SuggestionProvider<S> knownPlayers,
                                                       SuggestionProvider<S> onOff, boolean confirmMode) {
        String base = confirmMode ? "payconfirmtoggle" : "paytoggle";
        String onCmd = confirmMode ? "payconfirmon" : "payon";
        String offCmd = confirmMode ? "payconfirmoff" : "payoff";

        LiteralArgumentBuilder<S> toggle = LiteralArgumentBuilder.<S>literal("allium:" + base)
                .executes(ctx -> setPayPref(platform, confirmations, ctx.getSource(), confirmMode, null, null))
                .then(RequiredArgumentBuilder.<S, String>argument("state", StringArgumentType.word())
                        .suggests(onOff)
                        .executes(ctx -> setPayPref(platform, confirmations, ctx.getSource(), confirmMode,
                                StringArgumentType.getString(ctx, "state"), null))
                        .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                                .requires(platform::isGamemaster)
                                .suggests(knownPlayers)
                                .executes(ctx -> setPayPref(platform, confirmations, ctx.getSource(), confirmMode,
                                        StringArgumentType.getString(ctx, "state"),
                                        StringArgumentType.getString(ctx, "player")))));
        registerAliases(dispatcher, toggle, base);

        LiteralArgumentBuilder<S> forceOn = LiteralArgumentBuilder.<S>literal("allium:" + onCmd)
                .executes(ctx -> setPayPref(platform, confirmations, ctx.getSource(), confirmMode, "on", null))
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .requires(platform::isGamemaster)
                        .suggests(knownPlayers)
                        .executes(ctx -> setPayPref(platform, confirmations, ctx.getSource(), confirmMode, "on",
                                StringArgumentType.getString(ctx, "player"))));
        registerAliases(dispatcher, forceOn, onCmd);

        LiteralArgumentBuilder<S> forceOff = LiteralArgumentBuilder.<S>literal("allium:" + offCmd)
                .executes(ctx -> setPayPref(platform, confirmations, ctx.getSource(), confirmMode, "off", null))
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .requires(platform::isGamemaster)
                        .suggests(knownPlayers)
                        .executes(ctx -> setPayPref(platform, confirmations, ctx.getSource(), confirmMode, "off",
                                StringArgumentType.getString(ctx, "player"))));
        registerAliases(dispatcher, forceOff, offCmd);
    }

    private static <S, P> int setPayPref(NativePlatform<S, P> platform,
                                         NativeEconomy.PendingConfirmations confirmations,
                                         S source, boolean confirmMode, String rawState, String targetName) {
        Boolean desired = null;
        if (rawState != null) {
            if ("on".equalsIgnoreCase(rawState)) desired = Boolean.TRUE;
            else if ("off".equalsIgnoreCase(rawState)) desired = Boolean.FALSE;
            else {
                platform.reply(source, "Use 'on' or 'off'.");
                return 0;
            }
        }
        ResolvedAccount<P> target;
        if (targetName != null) {
            target = resolveAccount(platform, source, targetName);
            if (target == null) return 0;
        } else {
            P self = requirePlayer(platform, source);
            if (self == null) return 0;
            target = new ResolvedAccount<P>(platform.id(self), platform.name(self), self);
        }
        NativeEconomy eco = new NativeEconomy(platform.dataDirectory(source));
        boolean current = confirmMode ? eco.requiresPayConfirm(target.id) : eco.acceptsPayments(target.id);
        boolean next = desired == null ? !current : desired.booleanValue();
        boolean saved = confirmMode
                ? eco.setRequiresPayConfirm(target.id, target.name, next)
                : eco.setAcceptsPayments(target.id, target.name, next);
        if (!saved) {
            platform.reply(source, "Could not save payment preference.");
            return 0;
        }
        if (confirmMode && !next) confirmations.clear(target.id);
        String label = confirmMode ? "Payment confirmation" : "Payments";
        if (targetName == null) {
            platform.reply(source, label + (next ? " enabled." : " disabled."));
        } else {
            platform.reply(source, label + (next ? " enabled" : " disabled") + " for " + target.name + ".");
        }
        return 1;
    }

    // ==================== /eco ====================

    private static <S, P> void registerEco(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                           SuggestionProvider<S> knownPlayers) {
        LiteralArgumentBuilder<S> eco = LiteralArgumentBuilder.<S>literal("allium:eco")
                .requires(platform::isGamemaster)
                .then(ecoAmountBranch(platform, knownPlayers, "give"))
                .then(ecoAmountBranch(platform, knownPlayers, "take"))
                .then(ecoAmountBranch(platform, knownPlayers, "set"))
                .then(LiteralArgumentBuilder.<S>literal("reset")
                        .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                                .suggests(knownPlayers)
                                .executes(ctx -> executeEco(platform, ctx.getSource(), "reset",
                                        StringArgumentType.getString(ctx, "player"), null))));
        registerAliases(dispatcher, eco, "eco", "economy");
    }

    private static <S, P> LiteralArgumentBuilder<S> ecoAmountBranch(NativePlatform<S, P> platform,
                                                                    SuggestionProvider<S> knownPlayers,
                                                                    String action) {
        return LiteralArgumentBuilder.<S>literal(action)
                .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .suggests(knownPlayers)
                        .then(RequiredArgumentBuilder.<S, String>argument("amount", StringArgumentType.word())
                                .executes(ctx -> executeEco(platform, ctx.getSource(), action,
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "amount")))));
    }

    private static <S, P> int executeEco(NativePlatform<S, P> platform, S source, String action,
                                         String targetQuery, String rawAmount) {
        ResolvedAccount<P> target = resolveAccount(platform, source, targetQuery);
        if (target == null) return 0;
        NativeEconomy eco = new NativeEconomy(platform.dataDirectory(source));
        if ("reset".equals(action)) {
            BigDecimal start = eco.resetBalance(target.id, target.name);
            if (start == null) {
                platform.reply(source, "Could not reset balance for " + target.name + ".");
                return 0;
            }
            platform.reply(source, "Reset " + target.name + "'s balance to " + NativeEconomy.format(start) + ".");
            return 1;
        }
        BigDecimal amount = NativeEconomy.parseAmount(rawAmount);
        if (amount == null || (!"set".equals(action) && amount.compareTo(BigDecimal.ZERO) <= 0)) {
            platform.reply(source, "Invalid amount: " + rawAmount);
            return 0;
        }
        if ("give".equals(action)) {
            BigDecimal next = eco.addBalance(target.id, target.name, amount);
            if (next == null) {
                platform.reply(source, "Could not give " + NativeEconomy.format(amount) + " to " + target.name + ".");
                return 0;
            }
            platform.reply(source, "Gave " + NativeEconomy.format(amount) + " to " + target.name
                    + " (new balance: " + NativeEconomy.format(next) + ").");
            if (target.online != null) {
                platform.send(target.online, "You received " + NativeEconomy.format(amount) + ".");
            }
            return 1;
        }
        if ("take".equals(action)) {
            BigDecimal next = eco.takeBalance(target.id, target.name, amount);
            if (next == null) {
                platform.reply(source, target.name + " does not have " + NativeEconomy.format(amount) + ".");
                return 0;
            }
            platform.reply(source, "Took " + NativeEconomy.format(amount) + " from " + target.name
                    + " (new balance: " + NativeEconomy.format(next) + ").");
            return 1;
        }
        if ("set".equals(action)) {
            if (!eco.setBalance(target.id, target.name, amount)) {
                platform.reply(source, "Could not set balance for " + target.name + ".");
                return 0;
            }
            platform.reply(source, "Set " + target.name + "'s balance to " + NativeEconomy.format(amount) + ".");
            return 1;
        }
        return 0;
    }

    // ==================== /baltop ====================

    private static <S, P> void registerBaltop(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal("allium:baltop")
                .executes(ctx -> showBaltop(platform, ctx.getSource(), 1))
                .then(RequiredArgumentBuilder.<S, Integer>argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> showBaltop(platform, ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "page"))));
        registerAliases(dispatcher, root, "baltop", "balancetop");
    }

    private static <S, P> int showBaltop(NativePlatform<S, P> platform, S source, int requestedPage) {
        NativeEconomy eco = new NativeEconomy(platform.dataDirectory(source));
        List<NativeEconomy.AccountEntry> entries = eco.topAccounts();
        if (entries.isEmpty()) {
            platform.reply(source, "No economy accounts recorded yet.");
            return 1;
        }
        BigDecimal total = NativeEconomy.ZERO;
        for (NativeEconomy.AccountEntry e : entries) {
            total = total.add(e.balance);
        }
        int pages = Math.max(1, (entries.size() + BALTOP_PAGE_SIZE - 1) / BALTOP_PAGE_SIZE);
        int page = Math.max(1, Math.min(requestedPage, pages));
        int start = (page - 1) * BALTOP_PAGE_SIZE;
        int end = Math.min(entries.size(), start + BALTOP_PAGE_SIZE);
        platform.reply(source, "Top Balances (Page " + page + "/" + pages + ") - Server Total: "
                + NativeEconomy.format(total));
        for (int i = start; i < end; i++) {
            NativeEconomy.AccountEntry e = entries.get(i);
            platform.reply(source, (i + 1) + ". " + e.name + " - " + NativeEconomy.format(e.balance));
        }
        return 1;
    }

    // ==================== /worth, /setworth, /sell ====================

    private static <S, P> void registerWorthAndSell(CommandDispatcher<S> dispatcher, NativePlatform<S, P> platform,
                                                    SuggestionProvider<S> worthItems) {
        LiteralArgumentBuilder<S> worth = LiteralArgumentBuilder.<S>literal("allium:worth")
                .executes(ctx -> {
                    S source = ctx.getSource();
                    P player = requirePlayer(platform, source);
                    if (player == null) return 0;
                    String itemId = platform.heldItemId(player);
                    int count = platform.heldItemCount(player);
                    if (itemId == null || count <= 0) {
                        platform.reply(source, "Hold an item in your main hand or specify an item ID.");
                        return 0;
                    }
                    return reportWorth(platform, source, itemId, count);
                })
                .then(RequiredArgumentBuilder.<S, String>argument("item", StringArgumentType.word())
                        .suggests(worthItems)
                        .executes(ctx -> reportWorth(platform, ctx.getSource(),
                                StringArgumentType.getString(ctx, "item"), 1)));
        registerAliases(dispatcher, worth, "worth", "price");

        LiteralArgumentBuilder<S> setWorth = LiteralArgumentBuilder.<S>literal("allium:setworth")
                .requires(platform::isGamemaster)
                .then(RequiredArgumentBuilder.<S, String>argument("price", StringArgumentType.word())
                        .executes(ctx -> {
                            S source = ctx.getSource();
                            P player = requirePlayer(platform, source);
                            if (player == null) return 0;
                            String itemId = platform.heldItemId(player);
                            if (itemId == null) {
                                platform.reply(source, "Hold an item in your main hand or specify an item ID.");
                                return 0;
                            }
                            return updateWorth(platform, source, itemId, StringArgumentType.getString(ctx, "price"));
                        })
                        .then(RequiredArgumentBuilder.<S, String>argument("item", StringArgumentType.word())
                                .suggests(worthItems)
                                .executes(ctx -> updateWorth(platform, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"),
                                        StringArgumentType.getString(ctx, "price")))));
        registerAliases(dispatcher, setWorth, "setworth");

        LiteralArgumentBuilder<S> sell = LiteralArgumentBuilder.<S>literal("allium:sell")
                .executes(ctx -> sellHand(platform, ctx.getSource()))
                .then(LiteralArgumentBuilder.<S>literal("hand").executes(ctx -> sellHand(platform, ctx.getSource())))
                .then(LiteralArgumentBuilder.<S>literal("all").executes(ctx -> sellAll(platform, ctx.getSource())));
        registerAliases(dispatcher, sell, "sell");
    }

    private static <S, P> int reportWorth(NativePlatform<S, P> platform, S source, String rawItem, int count) {
        String id = NativeWorth.normalizeItemId(rawItem);
        if (id.isEmpty()) {
            platform.reply(source, "Invalid item ID: " + rawItem);
            return 0;
        }
        NativeWorth worth = new NativeWorth(platform.dataDirectory(source));
        BigDecimal unit = worth.priceOf(id);
        if (unit == null) {
            platform.reply(source, id + " cannot be sold (no worth configured).");
            return 0;
        }
        if (count > 1) {
            BigDecimal stackTotal = unit.multiply(BigDecimal.valueOf(count)).setScale(2, RoundingMode.HALF_UP);
            platform.reply(source, id + " is worth " + NativeEconomy.format(unit) + " each ("
                    + count + "x = " + NativeEconomy.format(stackTotal) + ").");
        } else {
            platform.reply(source, id + " is worth " + NativeEconomy.format(unit) + " each.");
        }
        return 1;
    }

    private static <S, P> int updateWorth(NativePlatform<S, P> platform, S source, String rawItem, String rawPrice) {
        String id = NativeWorth.normalizeItemId(rawItem);
        if (id.isEmpty()) {
            platform.reply(source, "Invalid item ID: " + rawItem);
            return 0;
        }
        BigDecimal price = NativeEconomy.parseAmount(rawPrice);
        if (price == null) {
            platform.reply(source, "Invalid price: " + rawPrice);
            return 0;
        }
        NativeWorth worth = new NativeWorth(platform.dataDirectory(source));
        if (!worth.setPrice(id, price)) {
            platform.reply(source, "Could not save worth for " + id + ".");
            return 0;
        }
        if (price.compareTo(BigDecimal.ZERO) == 0) {
            platform.reply(source, "Removed sell price for " + id + ".");
        } else {
            platform.reply(source, "Set worth of " + id + " to " + NativeEconomy.format(price) + ".");
        }
        return 1;
    }

    private static <S, P> int sellHand(NativePlatform<S, P> platform, S source) {
        P player = requirePlayer(platform, source);
        if (player == null) return 0;
        String itemId = platform.heldItemId(player);
        int count = platform.heldItemCount(player);
        if (itemId == null || count <= 0) {
            platform.reply(source, "Hold an item in your main hand to sell it.");
            return 0;
        }
        NativeWorth worth = new NativeWorth(platform.dataDirectory(source));
        BigDecimal unit = worth.priceOf(itemId);
        if (unit == null) {
            platform.reply(source, itemId + " cannot be sold.");
            return 0;
        }
        int removed = platform.consumeHeldItem(player);
        if (removed <= 0) {
            platform.reply(source, "Nothing to sell.");
            return 0;
        }
        BigDecimal earned = unit.multiply(BigDecimal.valueOf(removed)).setScale(2, RoundingMode.HALF_UP);
        NativeEconomy eco = new NativeEconomy(platform.dataDirectory(source));
        eco.addBalance(platform.id(player), platform.name(player), earned);
        platform.reply(source, "Sold " + removed + "x " + itemId + " for " + NativeEconomy.format(earned) + ".");
        return 1;
    }

    private static <S, P> int sellAll(NativePlatform<S, P> platform, S source) {
        P player = requirePlayer(platform, source);
        if (player == null) return 0;
        NativeWorth worth = new NativeWorth(platform.dataDirectory(source));
        Map<String, Integer> sold = platform.consumeSellableItems(player, id -> worth.priceOf(id) != null);
        if (sold == null || sold.isEmpty()) {
            platform.reply(source, "You have no sellable items in your inventory.");
            return 0;
        }
        int totalItems = 0;
        BigDecimal totalEarned = NativeEconomy.ZERO;
        for (Map.Entry<String, Integer> entry : sold.entrySet()) {
            int qty = entry.getValue() == null ? 0 : entry.getValue().intValue();
            if (qty <= 0) continue;
            BigDecimal unit = worth.priceOf(entry.getKey());
            if (unit == null) continue;
            totalItems += qty;
            totalEarned = totalEarned.add(unit.multiply(BigDecimal.valueOf(qty)));
        }
        totalEarned = totalEarned.setScale(2, RoundingMode.HALF_UP);
        if (totalItems <= 0 || totalEarned.compareTo(BigDecimal.ZERO) <= 0) {
            platform.reply(source, "You have no sellable items in your inventory.");
            return 0;
        }
        NativeEconomy eco = new NativeEconomy(platform.dataDirectory(source));
        eco.addBalance(platform.id(player), platform.name(player), totalEarned);
        platform.reply(source, "Sold " + totalItems + " item(s) for " + NativeEconomy.format(totalEarned) + ".");
        return 1;
    }

    // ==================== Shared Helpers ====================

    private static <S, P> P requirePlayer(NativePlatform<S, P> platform, S source) {
        P player = platform.playerOrNull(source);
        if (player == null) {
            platform.reply(source, "Only players can use this command.");
        }
        return player;
    }

    private static <S, P> P resolveOnline(NativePlatform<S, P> platform, S source, String query) {
        P found = findOnline(platform, source, query);
        if (found == null) {
            platform.reply(source, "Player '" + query + "' is not online.");
        }
        return found;
    }

    private static <S, P> P findOnline(NativePlatform<S, P> platform, S source, String query) {
        if (query == null || query.isEmpty()) return null;
        Collection<P> online = platform.onlinePlayers(source);
        for (P p : online) {
            if (query.equalsIgnoreCase(platform.name(p))) return p;
        }
        List<P> ranked = PlayerNameSearch.rankedMatches(online, query,
                p -> true, platform::id, platform::name, platform::name);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    private static <S, P> ResolvedAccount<P> resolveAccount(NativePlatform<S, P> platform, S source, String query) {
        P online = findOnline(platform, source, query);
        if (online != null) {
            return new ResolvedAccount<P>(platform.id(online), platform.name(online), online);
        }
        NativeEconomy eco = new NativeEconomy(platform.dataDirectory(source));
        UUID known = eco.findKnownId(query);
        if (known != null) {
            return new ResolvedAccount<P>(known, eco.nameOf(known), null);
        }
        platform.reply(source, "Player '" + query + "' was not found.");
        return null;
    }

    private static <S> void registerAliases(CommandDispatcher<S> dispatcher, LiteralArgumentBuilder<S> root,
                                            String... labels) {
        LiteralCommandNode<S> node = dispatcher.register(root);
        for (String label : labels) {
            for (String alias : new String[]{label, "allium:" + label}) {
                if (dispatcher.getRoot().getChild(alias) == null) {
                    dispatcher.register(LiteralArgumentBuilder.<S>literal(alias)
                            .requires(node.getRequirement())
                            .executes(node.getCommand())
                            .redirect(node));
                }
            }
        }
    }
}
