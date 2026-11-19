package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.MailModule;
import codes.castled.allium.text.SpigotComponents;
import codes.castled.allium.text.TextFormatter;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server tax command ({@code /tax}): rate/balance status, atomic {@code impose} with SQLite
 * history and mail receipts, paginated clickable {@code history}, and atomic confirmed
 * {@code refund} from the Tax account.
 */
public final class TaxService {

    private static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final PluginStart plugin;
    private final EconomyRuntime runtime;
    private final TaxHistoryRepository history;

    public TaxService(PluginStart plugin, EconomyRuntime runtime, TaxHistoryRepository history) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.history = Objects.requireNonNull(history, "history");
    }

    public void dispatch(CommandSender sender, String[] args) {
        if (!runtime.ready()) {
            runtime.tellSender(sender, Messages.ECO_UNAVAILABLE);
            return;
        }
        if (args.length == 0) {
            showInfo(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] subArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "impose" -> {
                if (!sender.hasPermission("allium.tax.impose")) {
                    runtime.tellSender(sender, Messages.NO_PERMISSION);
                    return;
                }
                impose(sender, subArgs);
            }
            case "history" -> {
                if (!sender.hasPermission("allium.tax.history")) {
                    runtime.tellSender(sender, Messages.NO_PERMISSION);
                    return;
                }
                showHistory(sender, subArgs);
            }
            case "refund" -> {
                if (!sender.hasPermission("allium.tax.refund")) {
                    runtime.tellSender(sender, Messages.NO_PERMISSION);
                    return;
                }
                refund(sender, subArgs);
            }
            default -> showInfo(sender);
        }
    }

    // -- /tax --------------------------------------------------------------------------------------

    private void showInfo(CommandSender sender) {
        TaxSettings tax = runtime.taxSettings();
        EconomyDisplay display = runtime.display();
        List<String> applies = new ArrayList<>();
        if (tax.applyToPay()) applies.add("pay");
        if (tax.applyToCheques()) applies.add("cheques");
        String appliesText = applies.isEmpty() ? "none" : String.join(", ", applies);
        String status = tax.active() ? "enabled" : "disabled";
        runtime.submitWork(sender, () -> {
            EconomyResult.Balance bal = runtime.engine().balance(tax.accountUuid());
            String formatted = bal instanceof EconomyResult.Found found
                    ? display.format(found.available())
                    : display.format(new Money(0));
            runtime.tellSender(sender, Messages.ECO_TAX_INFO,
                    "{rate}", tax.formattedPercent(),
                    "{status}", status,
                    "{applies}", appliesText,
                    "{account}", tax.accountName(),
                    "{balance}", formatted);
        });
    }

    // -- /tax impose <player|*|*> <percent> --------------------------------------------------------

    private void impose(CommandSender sender, String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("/tax impose <player|*|*> <percent>");
        }
        String targetArg = args[0];
        int bps = TaxSettings.parsePercentBps(args[1], false);
        String percentText = TaxSettings.formatBps(bps);
        String imposer = sender instanceof Player p ? p.getName() : "Console";
        String actorId = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        TaxSettings tax = runtime.taxSettings();
        EconomyDisplay display = runtime.display();

        if ("*".equals(targetArg)) {
            List<Player> targets = new ArrayList<>();
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.getUniqueId().equals(tax.accountUuid())) continue;
                if (online.hasPermission("allium.tax.exempt")) continue;
                targets.add(online);
            }
            record Target(UUID uuid, String name) {}
            List<Target> frozen = targets.stream().map(p -> new Target(p.getUniqueId(), p.getName())).toList();
            runtime.submitWork(sender, () -> {
                int count = 0;
                long totalMinor = 0;
                for (Target target : frozen) {
                    Money taxed = imposeOne(target.uuid(), target.name(), bps, imposer, actorId, tax);
                    if (taxed != null && taxed.minor() > 0) {
                        count++;
                        totalMinor = Math.addExact(totalMinor, taxed.minor());
                        notifyImposed(target.uuid(), taxed, percentText, imposer, display);
                    }
                }
                runtime.tellSender(sender, Messages.ECO_TAX_IMPOSED_MULTI,
                        "{percent}", percentText,
                        "{count}", Integer.toString(count),
                        "{total}", display.format(new Money(totalMinor)));
                UtilitySupport.audit(plugin, sender, "tax_impose_online",
                        "percent=" + percentText + " count=" + count + " total=" + totalMinor);
            });
            return;
        }

        if ("**".equals(targetArg)) {
            if (!sender.hasPermission("allium.tax.impose.offline")) {
                runtime.tellSender(sender, Messages.NO_PERMISSION);
                return;
            }
            runtime.submitWork(sender, () -> {
                List<TaxHistoryRepository.AccountBalance> accounts;
                try {
                    accounts = history.listPositiveAccounts(tax.accountUuid());
                } catch (SQLException error) {
                    runtime.tellSender(sender, Messages.ECO_STORAGE);
                    return;
                }
                int count = 0;
                long totalMinor = 0;
                for (var entry : accounts) {
                    String name = runtime.resolveDisplayName(entry.accountUuid());
                    Money taxed = imposeOne(entry.accountUuid(), name, bps, imposer, actorId, tax);
                    if (taxed != null && taxed.minor() > 0) {
                        count++;
                        totalMinor = Math.addExact(totalMinor, taxed.minor());
                        notifyImposed(entry.accountUuid(), taxed, percentText, imposer, display);
                    }
                }
                runtime.tellSender(sender, Messages.ECO_TAX_IMPOSED_MULTI,
                        "{percent}", percentText,
                        "{count}", Integer.toString(count),
                        "{total}", display.format(new Money(totalMinor)));
                UtilitySupport.audit(plugin, sender, "tax_impose_all",
                        "percent=" + percentText + " count=" + count + " total=" + totalMinor);
            });
            return;
        }

        runtime.resolveAccount(sender, targetArg, targetId -> {
            if (targetId.equals(tax.accountUuid())) {
                runtime.tellSender(sender, Messages.ECO_INVALID);
                return;
            }
            Player online = plugin.getServer().getPlayer(targetId);
            String targetName = online != null ? online.getName() : targetArg;
            runtime.submitWork(sender, () -> {
                Money taxed = imposeOne(targetId, targetName, bps, imposer, actorId, tax);
                if (taxed == null || taxed.minor() <= 0) {
                    runtime.tellSender(sender, Messages.ECO_TAX_NO_BALANCE, "{player}", targetName);
                    return;
                }
                runtime.tellSender(sender, Messages.ECO_TAX_IMPOSED,
                        "{player}", targetName,
                        "{percent}", percentText,
                        "{amount}", display.format(taxed));
                notifyImposed(targetId, taxed, percentText, imposer, display);
                UtilitySupport.audit(plugin, sender, "tax_impose",
                        "target=" + targetName + " percent=" + percentText + " amount=" + taxed.minor());
            });
        });
    }

    private Money imposeOne(UUID targetId, String targetName, int bps, String imposer, String actorId, TaxSettings tax) {
        EconomyResult.Balance bal = runtime.engine().balance(targetId);
        if (!(bal instanceof EconomyResult.Found found) || found.available().minor() <= 0) {
            return null;
        }
        Money before = found.available();
        Money taxed = TaxSettings.calculateFromBps(before, bps, RoundingMode.HALF_UP);
        if (taxed.minor() <= 0) {
            return null;
        }
        UUID opId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        EconomyRequest req = runtime.engine().request(opId, "TAX_IMPOSE", opId.toString(),
                EconomyRequest.Kind.TRANSFER, actorId, targetId, tax.accountUuid(), taxed);
        EconomyResult.Mutation result = runtime.engine().execute(req,
                c -> history.insertImpose(c, opId, targetId, targetName, taxed, bps, before, imposer, now));
        if (result instanceof EconomyResult.Committed done) {
            runtime.logExternal(req, done, imposer, targetName);
            return taxed;
        }
        return null;
    }

    private void notifyImposed(UUID targetId, Money amount, String percentText, String imposer, EconomyDisplay display) {
        String formatted = display.format(amount);
        plugin.getScheduler().runOnServer(() -> {
            Player online = plugin.getServer().getPlayer(targetId);
            if (online != null && online.isOnline()) {
                plugin.getMessageManager().send(online, Messages.ECO_TAX_IMPOSED_NOTIFY,
                        "{percent}", percentText, "{amount}", formatted);
            }
            if (plugin.getModuleManager().getModule("mail") instanceof MailModule mail && mail.isEnabled() && mail.mail() != null) {
                String body = org.bukkit.ChatColor.stripColor(codes.castled.allium.message.MessageManager.colorize(
                        plugin.getMessageManager().resolve(Messages.ECO_TAX_IMPOSED_MAIL,
                                "{percent}", percentText, "{amount}", formatted, "{imposer}", imposer)));
                mail.mail().sendSystemText(targetId, runtime.taxSettings().accountName(), body);
            }
        });
    }

    // -- /tax history [player] [page] --------------------------------------------------------------

    private void showHistory(CommandSender sender, String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("/tax history [player] [page]");
        }
        String playerArg = null;
        int page = 1;
        if (args.length == 1) {
            if (args[0].matches("[0-9]{1,6}")) {
                page = Integer.parseInt(args[0]);
            } else {
                playerArg = args[0];
            }
        } else if (args.length == 2) {
            playerArg = args[0];
            if (!args[1].matches("[0-9]{1,6}")) {
                throw new IllegalArgumentException("page must be a positive integer");
            }
            page = Integer.parseInt(args[1]);
        }
        if (page < 1) throw new IllegalArgumentException("page must be >= 1");
        final int requestedPage = page;
        if (playerArg != null) {
            final String label = playerArg;
            runtime.resolveAccount(sender, playerArg, id -> queryHistory(sender, id, label, requestedPage));
        } else {
            queryHistory(sender, null, "all players", requestedPage);
        }
    }

    private void queryHistory(CommandSender sender, UUID filterId, String filterLabel, int page) {
        EconomyDisplay display = runtime.display();
        boolean canRefund = sender instanceof Player && sender.hasPermission("allium.tax.refund");
        runtime.submitWork(sender, () -> {
            int total;
            List<TaxHistoryRecord> rows;
            try {
                total = history.count(filterId);
                if (total == 0) {
                    runtime.tellSender(sender, Messages.ECO_TAX_HISTORY_EMPTY);
                    return;
                }
                int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
                int clamped = Math.min(page, pages);
                rows = history.list(filterId, PAGE_SIZE, (clamped - 1) * PAGE_SIZE);
                final int displayPage = clamped;
                final int totalPages = pages;
                plugin.getScheduler().runOnServer(() -> {
                    if (sender instanceof Player p && !p.isOnline()) return;
                    plugin.getMessageManager().send(sender, Messages.ECO_TAX_HISTORY_HEADER,
                            "{target}", filterLabel,
                            "{page}", Integer.toString(displayPage),
                            "{pages}", Integer.toString(totalPages));
                    for (TaxHistoryRecord row : rows) {
                        String status = row.refunded()
                                ? "&a[Refunded " + display.format(row.refundAmount()) + "]"
                                : "&e[Pending]";
                        String line = plugin.getMessageManager().resolve(Messages.ECO_TAX_HISTORY_ENTRY,
                                "{id}", Long.toString(row.id()),
                                "{date}", DATE_FORMAT.format(Instant.ofEpochMilli(row.createdAt())),
                                "{player}", row.playerName(),
                                "{amount}", display.format(row.amount()),
                                "{percent}", TaxSettings.formatBps(row.percentageBps()),
                                "{imposer}", row.imposer(),
                                "{status}", status);
                        if (canRefund && !row.refunded() && sender instanceof Player player) {
                            BaseComponent[] parts = SpigotComponents.of(TextFormatter.parse(line));
                            ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tax refund " + row.id() + " 100");
                            HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    SpigotComponents.of(TextFormatter.parse("&aClick to refund tax #" + row.id() + " (100%)")));
                            for (BaseComponent part : parts) {
                                part.setClickEvent(click);
                                part.setHoverEvent(hover);
                            }
                            player.spigot().sendMessage(parts);
                        } else {
                            sender.sendMessage(codes.castled.allium.message.MessageManager.colorize(line));
                        }
                    }
                });
            } catch (SQLException error) {
                runtime.tellSender(sender, Messages.ECO_STORAGE);
            }
        });
    }

    // -- /tax refund <id|player> [percent] [confirm] -----------------------------------------------

    private void refund(CommandSender sender, String[] args) {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException("/tax refund <id|player> [percent] [confirm]");
        }
        String targetArg = args[0];
        int refundBps = 10_000;
        boolean confirmed = false;
        if (args.length >= 2) {
            if ("confirm".equalsIgnoreCase(args[1]) && args.length == 2) {
                confirmed = true;
            } else {
                refundBps = TaxSettings.parsePercentBps(args[1], false);
            }
        }
        if (args.length == 3) {
            if (!"confirm".equalsIgnoreCase(args[2])) {
                throw new IllegalArgumentException("third argument must be confirm");
            }
            confirmed = true;
        }
        final int finalBps = refundBps;
        final boolean isConfirmed = confirmed;
        if (targetArg.matches("[0-9]{1,18}")) {
            long id = Long.parseLong(targetArg);
            executeRefund(sender, id, null, targetArg, finalBps, isConfirmed);
        } else {
            runtime.resolveAccount(sender, targetArg,
                    playerId -> executeRefund(sender, null, playerId, targetArg, finalBps, isConfirmed));
        }
    }

    private void executeRefund(CommandSender sender, Long taxId, UUID playerId, String label,
                               int refundBps, boolean confirmed) {
        TaxSettings tax = runtime.taxSettings();
        EconomyDisplay display = runtime.display();
        String refunder = sender instanceof Player p ? p.getName() : "Console";
        String actorId = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        String percentText = TaxSettings.formatBps(refundBps);
        runtime.submitWork(sender, () -> {
            Optional<TaxHistoryRecord> found;
            try {
                found = taxId != null ? history.findById(taxId) : history.findLatestUnrefunded(playerId);
            } catch (SQLException error) {
                runtime.tellSender(sender, Messages.ECO_STORAGE);
                return;
            }
            if (found.isEmpty()) {
                runtime.tellSender(sender, Messages.ECO_TAX_REFUND_NOT_FOUND, "{target}", label);
                return;
            }
            TaxHistoryRecord record = found.get();
            if (record.refunded()) {
                runtime.tellSender(sender, Messages.ECO_TAX_ALREADY_REFUNDED, "{id}", Long.toString(record.id()));
                return;
            }
            Money refundAmount = TaxSettings.calculateFromBps(record.amount(), refundBps, RoundingMode.HALF_UP);
            if (refundAmount.minor() <= 0) {
                runtime.tellSender(sender, Messages.ECO_INVALID);
                return;
            }
            if (!confirmed) {
                String pctArg = percentText.endsWith("%") ? percentText.substring(0, percentText.length() - 1) : percentText;
                String confirmCmd = "/tax refund " + record.id() + " " + pctArg + " confirm";
                plugin.getScheduler().runOnServer(() -> {
                    if (sender instanceof Player player && player.isOnline()) {
                        String text = plugin.getMessageManager().resolve(Messages.ECO_TAX_REFUND_CONFIRM,
                                "{id}", Long.toString(record.id()),
                                "{amount}", display.format(refundAmount),
                                "{percent}", percentText,
                                "{player}", record.playerName(),
                                "{command}", "[CLICK TO CONFIRM]");
                        BaseComponent[] parts = SpigotComponents.of(TextFormatter.parse(text));
                        ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, confirmCmd);
                        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                SpigotComponents.of(TextFormatter.parse("&aClick to run: &f" + confirmCmd)));
                        for (BaseComponent part : parts) {
                            part.setClickEvent(click);
                            part.setHoverEvent(hover);
                        }
                        player.spigot().sendMessage(parts);
                    } else if (!(sender instanceof Player)) {
                        plugin.getMessageManager().send(sender, Messages.ECO_TAX_REFUND_CONFIRM,
                                "{id}", Long.toString(record.id()),
                                "{amount}", display.format(refundAmount),
                                "{percent}", percentText,
                                "{player}", record.playerName(),
                                "{command}", confirmCmd);
                    }
                });
                return;
            }
            UUID opId = UUID.randomUUID();
            long now = System.currentTimeMillis();
            EconomyRequest req = runtime.engine().request(opId, "TAX_REFUND", "refund:" + record.id(),
                    EconomyRequest.Kind.TRANSFER, actorId, tax.accountUuid(), record.playerUuid(), refundAmount);
            EconomyResult.Mutation result = runtime.engine().execute(req,
                    c -> history.markRefunded(c, record.id(), refundAmount, refundBps, refunder, opId, now));
            if (result instanceof EconomyResult.Committed done) {
                runtime.logExternal(req, done, refunder, record.playerName());
                runtime.tellSender(sender, Messages.ECO_TAX_REFUNDED,
                        "{id}", Long.toString(record.id()),
                        "{amount}", display.format(refundAmount),
                        "{player}", record.playerName());
                notifyRefunded(record.playerUuid(), refundAmount, refunder, display);
                UtilitySupport.audit(plugin, sender, "tax_refund",
                        "id=" + record.id() + " player=" + record.playerName() + " amount=" + refundAmount.minor());
            } else if (result instanceof EconomyResult.Rejected refused) {
                if ("INSUFFICIENT_FUNDS".equals(refused.reason())) {
                    runtime.tellSender(sender, Messages.ECO_TAX_FUNDS, "{account}", tax.accountName());
                } else if ("WORKFLOW_REFUSED".equals(refused.reason())) {
                    runtime.tellSender(sender, Messages.ECO_TAX_ALREADY_REFUNDED, "{id}", Long.toString(record.id()));
                } else {
                    runtime.tellSender(sender, Messages.ECO_OPERATION_REFUSED, "{reason}", refused.reason());
                }
            } else if (result instanceof EconomyResult.Unknown unknown) {
                runtime.tellSender(sender, Messages.ECO_UNKNOWN, "{operation}", unknown.operationId().toString());
            } else {
                runtime.tellSender(sender, Messages.ECO_STORAGE);
            }
        });
    }

    private void notifyRefunded(UUID targetId, Money amount, String refunder, EconomyDisplay display) {
        String formatted = display.format(amount);
        plugin.getScheduler().runOnServer(() -> {
            Player online = plugin.getServer().getPlayer(targetId);
            if (online != null && online.isOnline()) {
                plugin.getMessageManager().send(online, Messages.ECO_TAX_REFUNDED_NOTIFY, "{amount}", formatted);
            } else if (plugin.getModuleManager().getModule("mail") instanceof MailModule mail && mail.isEnabled() && mail.mail() != null) {
                String body = org.bukkit.ChatColor.stripColor(codes.castled.allium.message.MessageManager.colorize(
                        plugin.getMessageManager().resolve(Messages.ECO_TAX_REFUNDED_MAIL,
                                "{amount}", formatted, "{refunder}", refunder)));
                mail.mail().sendSystemText(targetId, runtime.taxSettings().accountName(), body);
            }
        });
    }
}
