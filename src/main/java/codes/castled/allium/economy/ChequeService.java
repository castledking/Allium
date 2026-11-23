package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.mail.MailInventoryLock;
import codes.castled.allium.mail.PlayerDataFiles;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Journalled, anti-dupe money cheques ({@code /cheque}).
 *
 * <ul>
 *   <li><b>Player-funded ({@code /cheque <amount>}):</b> holds funds in a {@code CHEQUE}
 *       reservation with a {@code PENDING} cheque row, delivers the note with
 *       {@code allium:cheque_receipts} in the same {@code saveData()}, then captures (with
 *       optional tax to the Tax account) and marks the cheque {@code ISSUED}.</li>
 *   <li><b>Admin-issued ({@code /cheque <amount|min-max> <player>}):</b> requires
 *       {@code allium.cheque.others} (or console), mints an {@code ISSUED} cheque without
 *       withdrawing from the staff member, and supports ranged amounts such as {@code 500-1000}.</li>
 *   <li><b>Redemption:</b> right-clicking a cheque note executes a single atomic economy
 *       {@code DEPOSIT} coupled with {@code ISSUED -> REDEEMED}, so duplicated notes or crashes
 *       can never pay twice.</li>
 * </ul>
 */
public final class ChequeService implements Listener {

    public static final String FAULT_HALT_AFTER_RESERVE = "cheque.halt-after-reserve";
    public static final String FAULT_HALT_AFTER_SAVE = "cheque.halt-after-save";

    private final PluginStart plugin;
    private final EconomyRuntime runtime;
    private final ChequeRepository repository;
    private final Executor io;
    private final Path playerData;
    private final NamespacedKey chequeIdKey;
    private final NamespacedKey chequeAmountKey;
    private final NamespacedKey chequeSignerKey;
    private final NamespacedKey receiptKey;
    private final MailInventoryLock locks = new MailInventoryLock();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ChequeService(PluginStart plugin, EconomyRuntime runtime, ChequeRepository repository, Executor io) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.io = Objects.requireNonNull(io, "io");
        this.playerData = PlayerDataFiles.directory(plugin.getServer());
        this.chequeIdKey = new NamespacedKey(plugin, "cheque_id");
        this.chequeAmountKey = new NamespacedKey(plugin, "cheque_amount_minor");
        this.chequeSignerKey = new NamespacedKey(plugin, "cheque_signer");
        this.receiptKey = new NamespacedKey(plugin, "cheque_receipts");
        if (!receiptKey.toString().equals(PlayerDataReceiptProof.CHEQUE_RECEIPTS)) {
            throw new IllegalStateException("cheque receipt key " + receiptKey + " does not match proof key");
        }
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(locks, plugin);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            onPlayerNow(online, () -> recover(online), () -> {});
        }
    }

    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(locks);
    }

    public boolean busy(Player player) {
        return player != null && busy.contains(player.getUniqueId());
    }

    public void dispatch(CommandSender sender, String[] args) {
        if (closed || !runtime.ready()) {
            send(sender, Messages.ECO_UNAVAILABLE);
            return;
        }
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("/cheque <amount> [player]");
        }
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                throw new IllegalArgumentException("/cheque <amount> <player>");
            }
            Money gross = Money.parseCommand(args[0], false).positive();
            startPlayerCheque(player, gross);
            return;
        }
        // Admin cheque: /cheque <amount|min-max> <player> (also accepts legacy /cheque <player> <amount|min-max>).
        if (sender instanceof Player && !sender.hasPermission("allium.cheque.others")) {
            send(sender, Messages.NO_PERMISSION);
            return;
        }
        Money.Range range;
        String targetArg;
        try {
            range = Money.parseCommandRange(args[0]);
            targetArg = args[1];
        } catch (IllegalArgumentException | ArithmeticException firstFailed) {
            range = Money.parseCommandRange(args[1]);
            targetArg = args[0];
        }
        Player target = UtilitySupport.resolveVisibleOnlinePlayer(plugin, sender, targetArg);
        if (target == null) return;
        Money amount = range.sample(ThreadLocalRandom.current());
        startAdminCheque(sender, target, amount);
    }

    // -- Player-funded cheque ----------------------------------------------------------------------

    private void startPlayerCheque(Player player, Money gross) {
        if (busy(player) || UtilitySupport.isInventoryBusy(plugin, player) || !emptyCursor(player)) {
            send(player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            send(player, Messages.ECO_CHEQUE_FULL, "{player}", player.getName());
            return;
        }
        TaxSettings taxSettings = runtime.taxSettings();
        boolean taxable = taxSettings.appliesToCheques() && !player.hasPermission("allium.tax.exempt");
        Money tax = taxable ? taxSettings.calculateTax(gross) : new Money(0);
        Money faceValue = gross.minus(tax);
        if (faceValue.minor() <= 0) {
            send(player, Messages.ECO_INVALID);
            return;
        }
        UUID account = player.getUniqueId();
        UUID chequeId = UUID.randomUUID();
        String signerName = player.getName();
        EconomyDisplay display = runtime.display();
        try {
            player.saveData();
        } catch (RuntimeException error) {
            send(player, Messages.ECO_UNAVAILABLE);
            return;
        }
        String baseline = receipt(player);
        Path file = file(account);
        long now = System.currentTimeMillis();
        ChequeRecord pending = new ChequeRecord(chequeId, faceValue, tax, account, signerName,
                false, "PENDING", null, null, null, now, null);
        if (!busy.add(account)) {
            send(player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }
        locks.lock(player);
        Runnable forget = () -> finishBusy(player, account);
        if (!submit(player, () -> {
            try {
                PaidWorkflowSupport.prove(file, PlayerDataReceiptProof.CHEQUE_RECEIPTS, baseline);
                EconomyResult.Mutation reserved = runtime.reservations().reserve(
                        EconomyReservations.Kind.CHEQUE, chequeId, account, gross,
                        EconomyReservations.fingerprint("CHEQUE", chequeId.toString(), account.toString(),
                                Long.toString(gross.minor()), Long.toString(faceValue.minor()), Long.toString(tax.minor())),
                        c -> repository.insertPending(c, pending));
                if (reserved instanceof EconomyResult.Rejected refused) {
                    onPlayer(player, () -> {
                        finishBusy(player, account);
                        if (refused.reason().equals("INSUFFICIENT_FUNDS") || refused.reason().equals("MISSING_ACCOUNT")) {
                            send(player, Messages.ECO_CHEQUE_FUNDS, "{amount}", display.format(gross));
                        } else {
                            EconomyRuntime.sendRefusal(plugin, player, refused.reason());
                        }
                    }, forget);
                    return;
                }
                PaidWorkflowSupport.committed(reserved);
            } catch (PaidWorkflowSupport.UnknownCommit unknown) {
                onPlayer(player, () -> {
                    finishBusy(player, account);
                    send(player, Messages.ECO_UNKNOWN, "{operation}", unknown.operationId().toString());
                }, forget);
                return;
            } catch (SQLException error) {
                plugin.logger().warning("Cheque for " + signerName + " was not started: " + error.getMessage());
                onPlayer(player, () -> {
                    finishBusy(player, account);
                    send(player, Messages.ECO_STORAGE);
                }, forget);
                return;
            }
            if (FaultInjection.trip(FAULT_HALT_AFTER_RESERVE)) {
                plugin.logger().warning("Fault " + FAULT_HALT_AFTER_RESERVE + ": halting with cheque " + chequeId + " held.");
                Runtime.getRuntime().halt(77);
            }
            onPlayer(player, () -> deliverPlayerCheque(player, chequeId, gross, faceValue, tax, taxSettings, signerName, baseline), forget);
        })) {
            finishBusy(player, account);
            send(player, Messages.ECO_BUSY);
        }
    }

    private void deliverPlayerCheque(Player player, UUID chequeId, Money gross, Money faceValue,
                                     Money tax, TaxSettings taxSettings, String signerName, @Nullable String baseline) {
        UUID account = player.getUniqueId();
        if (closed || plugin.getServer().getPlayer(account) != player || !player.isOnline()) {
            finishBusy(player, account);
            return;
        }
        if (player.getInventory().firstEmpty() < 0 || !emptyCursor(player)) {
            releasePlayerCheque(player, chequeId, baseline, () -> send(player, Messages.ECO_CHEQUE_FULL, "{player}", player.getName()));
            return;
        }
        EconomyDisplay display = runtime.display();
        ItemStack note = createChequeItem(chequeId, faceValue, signerName, display);
        if (!player.getInventory().addItem(note).isEmpty()) {
            releasePlayerCheque(player, chequeId, baseline, () -> send(player, Messages.ECO_CHEQUE_FULL, "{player}", player.getName()));
            return;
        }
        Set<UUID> receipts = RepairReceipts.parse(receipt(player));
        receipts.add(chequeId);
        setReceipt(player, receipts);
        try {
            player.saveData();
        } catch (RuntimeException error) {
            plugin.logger().warning("Could not save " + signerName + " after cheque " + chequeId + ": " + error.getMessage());
            finishBusy(player, account);
            send(player, Messages.ECO_CHEQUE_PENDING);
            return;
        }
        String expected = receipt(player);
        if (FaultInjection.trip(FAULT_HALT_AFTER_SAVE)) {
            plugin.logger().warning("Fault " + FAULT_HALT_AFTER_SAVE + ": halting with cheque " + chequeId + " saved before capture.");
            Runtime.getRuntime().halt(77);
        }
        Path file = file(account);
        UUID captureOp = EconomyReservations.operation(EconomyReservations.Kind.CHEQUE, chequeId, EconomyRequest.Kind.CAPTURE);
        if (!submit(player, () -> {
            boolean captured;
            try {
                PaidWorkflowSupport.prove(file, PlayerDataReceiptProof.CHEQUE_RECEIPTS, expected);
                EconomyResult.Mutation result = tax.minor() > 0
                        ? runtime.reservations().capture(EconomyReservations.Kind.CHEQUE, chequeId,
                                taxSettings.accountUuid(), tax, c -> repository.markIssued(c, chequeId, captureOp))
                        : runtime.reservations().capture(EconomyReservations.Kind.CHEQUE, chequeId,
                                c -> repository.markIssued(c, chequeId, captureOp));
                captured = PaidWorkflowSupport.committed(result);
            } catch (SQLException error) {
                plugin.logger().warning("Cheque " + chequeId + " for " + signerName
                        + " is saved; its capture waits for the next join: " + error.getMessage());
                captured = false;
            }
            boolean done = captured;
            onPlayer(player, () -> {
                finishBusy(player, account);
                if (!done) {
                    send(player, Messages.ECO_CHEQUE_PENDING);
                    return;
                }
                if (plugin.getServer().getPlayer(account) == player) {
                    Set<UUID> left = RepairReceipts.parse(receipt(player));
                    left.remove(chequeId);
                    setReceipt(player, left);
                }
                if (tax.minor() > 0) {
                    send(player, Messages.ECO_CHEQUE_CREATED_TAXED,
                            "{amount}", display.format(faceValue),
                            "{tax}", display.format(tax),
                            "{account}", taxSettings.accountName());
                } else {
                    send(player, Messages.ECO_CHEQUE_CREATED, "{amount}", display.format(faceValue));
                }
                UtilitySupport.audit(plugin, player, "cheque_issue", "cheque=" + chequeId
                        + " gross=" + gross.minor() + " face=" + faceValue.minor() + " tax=" + tax.minor());
            }, () -> finishBusy(player, account));
        })) {
            finishBusy(player, account);
            send(player, Messages.ECO_CHEQUE_PENDING);
        }
    }

    private void releasePlayerCheque(Player player, UUID chequeId, @Nullable String baseline, Runnable then) {
        UUID account = player.getUniqueId();
        Path file = file(account);
        if (!submit(player, () -> {
            try {
                PaidWorkflowSupport.prove(file, PlayerDataReceiptProof.CHEQUE_RECEIPTS, baseline);
                PaidWorkflowSupport.committed(runtime.reservations().release(
                        EconomyReservations.Kind.CHEQUE, chequeId, c -> repository.markVoided(c, chequeId)));
                onPlayer(player, () -> {
                    finishBusy(player, account);
                    then.run();
                }, () -> finishBusy(player, account));
            } catch (SQLException error) {
                plugin.logger().warning("Cheque hold " + chequeId + " stays held until " + player.getName()
                        + "'s next join: " + error.getMessage());
                onPlayer(player, () -> finishBusy(player, account), () -> finishBusy(player, account));
            }
        })) {
            finishBusy(player, account);
        }
    }

    // -- Admin cheque ------------------------------------------------------------------------------

    private void startAdminCheque(CommandSender sender, Player target, Money amount) {
        UUID issuerUuid = sender instanceof Player p ? p.getUniqueId() : null;
        String signerName = sender instanceof Player p ? p.getName() : "Console";
        String targetName = target.getName();
        onPlayerNow(target, () -> {
            if (closed || plugin.getServer().getPlayer(target.getUniqueId()) != target || !target.isOnline()) {
                send(sender, Messages.PLAYER_NOT_FOUND, "{player}", targetName);
                return;
            }
            if (busy(target) || UtilitySupport.isInventoryBusy(plugin, target)) {
                send(sender, Messages.UTILITY_INVENTORY_BUSY, "{player}", target.getName());
                return;
            }
            if (target.getInventory().firstEmpty() < 0) {
                send(sender, Messages.ECO_CHEQUE_FULL, "{player}", target.getName());
                return;
            }
            UUID chequeId = UUID.randomUUID();
            EconomyDisplay display = runtime.display();
            ChequeRecord record = new ChequeRecord(chequeId, amount, new Money(0), issuerUuid, signerName,
                    true, "ISSUED", null, null, null, System.currentTimeMillis(), null);
            UUID targetId = target.getUniqueId();
            if (!busy.add(targetId)) {
                send(sender, Messages.UTILITY_INVENTORY_BUSY, "{player}", target.getName());
                return;
            }
            if (!submit(sender, () -> {
                try {
                    if (!repository.insertAdminIssued(record)) {
                        throw new SQLException("admin cheque insert affected 0 rows");
                    }
                } catch (SQLException error) {
                    plugin.logger().warning("Admin cheque for " + targetName + " failed: " + error.getMessage());
                    busy.remove(targetId);
                    send(sender, Messages.ECO_STORAGE);
                    return;
                }
                onPlayer(target, () -> {
                    busy.remove(targetId);
                    if (closed || plugin.getServer().getPlayer(targetId) != target || !target.isOnline()) {
                        voidAsync(chequeId);
                        send(sender, Messages.PLAYER_NOT_FOUND, "{player}", targetName);
                        return;
                    }
                    ItemStack note = createChequeItem(chequeId, amount, signerName, display);
                    if (!target.getInventory().addItem(note).isEmpty()) {
                        voidAsync(chequeId);
                        send(sender, Messages.ECO_CHEQUE_FULL, "{player}", target.getName());
                        return;
                    }
                    try {
                        target.saveData();
                    } catch (RuntimeException ignored) {
                    }
                    send(sender, Messages.ECO_CHEQUE_GIVEN, "{amount}", display.format(amount), "{player}", target.getName());
                    if (sender != target) {
                        send(target, Messages.ECO_CHEQUE_RECEIVED, "{amount}", display.format(amount), "{signer}", signerName);
                    }
                    UtilitySupport.audit(plugin, sender, "cheque_admin_give",
                            "cheque=" + chequeId + " target=" + target.getName() + " amount=" + amount.minor());
                }, () -> {
                    busy.remove(targetId);
                    voidAsync(chequeId);
                    send(sender, Messages.PLAYER_NOT_FOUND, "{player}", targetName);
                });
            })) {
                busy.remove(targetId);
                send(sender, Messages.ECO_BUSY);
            }
        }, () -> send(sender, Messages.PLAYER_NOT_FOUND, "{player}", targetName));
    }

    private void voidAsync(UUID chequeId) {
        try {
            io.execute(() -> {
                try {
                    repository.markVoided(chequeId);
                } catch (SQLException error) {
                    plugin.logger().warning("Could not void undelivered admin cheque " + chequeId + ": " + error.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    // -- Redemption --------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.PAPER) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        String rawId = meta.getPersistentDataContainer().get(chequeIdKey, PersistentDataType.STRING);
        if (rawId == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (closed || !runtime.ready()) {
            send(player, Messages.ECO_UNAVAILABLE);
            return;
        }
        if (busy(player) || UtilitySupport.isInventoryBusy(plugin, player)) {
            send(player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }
        UUID chequeId;
        try {
            chequeId = UUID.fromString(rawId);
        } catch (IllegalArgumentException bad) {
            send(player, Messages.ECO_CHEQUE_INVALID);
            return;
        }
        EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        UUID playerId = player.getUniqueId();
        EconomyDisplay display = runtime.display();
        if (!busy.add(playerId)) {
            send(player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }
        locks.lock(player);
        Runnable forget = () -> finishBusy(player, playerId);
        if (!submit(player, () -> {
            Optional<ChequeRecord> found;
            try {
                found = repository.find(chequeId);
            } catch (SQLException error) {
                onPlayer(player, () -> {
                    finishBusy(player, playerId);
                    send(player, Messages.ECO_STORAGE);
                }, forget);
                return;
            }
            if (found.isEmpty()) {
                onPlayer(player, () -> {
                    finishBusy(player, playerId);
                    send(player, Messages.ECO_CHEQUE_INVALID);
                }, forget);
                return;
            }
            ChequeRecord record = found.get();
            if ("PENDING".equals(record.state())) {
                onPlayer(player, () -> {
                    finishBusy(player, playerId);
                    send(player, Messages.ECO_CHEQUE_PENDING);
                }, forget);
                return;
            }
            if (!"ISSUED".equals(record.state())) {
                onPlayer(player, () -> {
                    consumeChequeItem(player, hand, chequeId);
                    finishBusy(player, playerId);
                    send(player, Messages.ECO_CHEQUE_SPENT);
                }, forget);
                return;
            }
            UUID opId = UUID.randomUUID();
            EconomyRequest request = runtime.engine().request(opId, "CHEQUE_REDEEM", chequeId.toString(),
                    EconomyRequest.Kind.DEPOSIT, playerId.toString(), playerId, null, record.amount());
            EconomyResult.Mutation result = runtime.synchronousWorkflow(request,
                    c -> repository.markRedeemed(c, chequeId, record.amount(), opId, playerId, System.currentTimeMillis()));
            onPlayer(player, () -> {
                if (result instanceof EconomyResult.Committed done) {
                    consumeChequeItem(player, hand, chequeId);
                    try {
                        player.saveData();
                    } catch (RuntimeException ignored) {
                    }
                    finishBusy(player, playerId);
                    runtime.logExternal(request, done, record.issuerName(), player.getName());
                    send(player, Messages.ECO_CHEQUE_REDEEMED,
                            "{amount}", display.format(record.amount()),
                            "{signer}", record.issuerName());
                    UtilitySupport.audit(plugin, player, "cheque_redeem",
                            "cheque=" + chequeId + " amount=" + record.amount().minor() + " op=" + done.operationId());
                    return;
                }
                if (result instanceof EconomyResult.Rejected refused) {
                    if ("WORKFLOW_REFUSED".equals(refused.reason())) {
                        consumeChequeItem(player, hand, chequeId);
                        finishBusy(player, playerId);
                        send(player, Messages.ECO_CHEQUE_SPENT);
                        return;
                    }
                    finishBusy(player, playerId);
                    EconomyRuntime.sendRefusal(plugin, player, refused.reason());
                    return;
                }
                finishBusy(player, playerId);
                if (result instanceof EconomyResult.Unknown unknown) {
                    send(player, Messages.ECO_UNKNOWN, "{operation}", unknown.operationId().toString());
                } else {
                    send(player, Messages.ECO_STORAGE);
                }
            }, forget);
        })) {
            finishBusy(player, playerId);
            send(player, Messages.ECO_BUSY);
        }
    }

    // -- Recovery ----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        recover(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        busy.remove(id);
        locks.clear(event.getPlayer());
    }

    private void recover(Player player) {
        if (closed || !runtime.ready()) return;
        UUID account = player.getUniqueId();
        busy.add(account);
        locks.lock(player);
        Path file = file(account);
        TaxSettings taxSettings = runtime.taxSettings();
        if (!submit(player, () -> {
            int captured = 0;
            int released = 0;
            Set<UUID> settled = new HashSet<>();
            try {
                EconomyReservations holds = runtime.reservations();
                List<EconomyReservations.Reservation> rows = holds.list(account).stream()
                        .filter(r -> r.kind() == EconomyReservations.Kind.CHEQUE).toList();
                Set<UUID> saved = rows.stream().anyMatch(r -> r.state().equals("HELD"))
                        ? RepairReceipts.parse(PlayerDataReceiptProof.read(file, PlayerDataReceiptProof.CHEQUE_RECEIPTS))
                        : Set.of();
                for (EconomyReservations.Reservation row : rows) {
                    if (!row.state().equals("HELD")) {
                        settled.add(row.id());
                        continue;
                    }
                    if (holds.operatorResolved(row.kind(), row.id())) continue;
                    Optional<ChequeRecord> cheque = repository.find(row.id());
                    if (cheque.isEmpty()) continue;
                    boolean charge = saved.contains(row.id());
                    UUID captureOp = EconomyReservations.operation(EconomyReservations.Kind.CHEQUE, row.id(), EconomyRequest.Kind.CAPTURE);
                    EconomyResult.Mutation result;
                    if (charge) {
                        result = cheque.get().tax().minor() > 0
                                ? holds.capture(row.kind(), row.id(), taxSettings.accountUuid(), cheque.get().tax(),
                                        c -> repository.markIssued(c, row.id(), captureOp))
                                : holds.capture(row.kind(), row.id(), c -> repository.markIssued(c, row.id(), captureOp));
                    } else {
                        result = holds.release(row.kind(), row.id(), c -> repository.markVoided(c, row.id()));
                    }
                    if (PaidWorkflowSupport.committed(result)) {
                        settled.add(row.id());
                        if (charge) captured++;
                        else released++;
                    }
                }
            } catch (SQLException | IOException | RuntimeException error) {
                plugin.logger().warning("Cheque recovery for " + player.getName() + " retries on next join: " + error.getMessage());
            }
            int issued = captured;
            int refunded = released;
            onPlayer(player, () -> {
                finishBusy(player, account);
                if (plugin.getServer().getPlayer(account) != player) return;
                Set<UUID> live = RepairReceipts.parse(receipt(player));
                if (live.removeAll(settled)) {
                    setReceipt(player, live);
                }
                if (issued + refunded > 0) {
                    send(player, Messages.ECO_CHEQUE_RECOVERED,
                            "{captured}", Integer.toString(issued), "{released}", Integer.toString(refunded));
                }
            }, () -> finishBusy(player, account));
        })) {
            finishBusy(player, account);
        }
    }

    // -- Item & helper utilities -------------------------------------------------------------------

    ItemStack createChequeItem(UUID chequeId, Money amount, String signerName, EconomyDisplay display) {
        ItemStack item = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(chequeIdKey, PersistentDataType.STRING, chequeId.toString());
            meta.getPersistentDataContainer().set(chequeAmountKey, PersistentDataType.LONG, amount.minor());
            meta.getPersistentDataContainer().set(chequeSignerKey, PersistentDataType.STRING, signerName);
            meta.setDisplayName("§a§lMoney Note §7(Right Click)");
            meta.setLore(List.of(
                    "§fValue: §a" + display.format(amount),
                    "§fSigner: §e" + signerName
            ));
            Enchantment glow = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
            if (glow != null) {
                meta.addEnchant(glow, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void consumeChequeItem(Player player, EquipmentSlot hand, UUID chequeId) {
        if (plugin.getServer().getPlayer(player.getUniqueId()) != player) return;
        String target = chequeId.toString();
        ItemStack inHand = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (matchesCheque(inHand, target)) {
            if (inHand.getAmount() > 1) {
                inHand.setAmount(inHand.getAmount() - 1);
            } else if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
            return;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack slot = contents[i];
            if (matchesCheque(slot, target)) {
                if (slot.getAmount() > 1) {
                    slot.setAmount(slot.getAmount() - 1);
                    player.getInventory().setItem(i, slot);
                } else {
                    player.getInventory().setItem(i, null);
                }
                return;
            }
        }
    }

    private boolean matchesCheque(@Nullable ItemStack stack, String chequeId) {
        if (stack == null || stack.getType() != Material.PAPER) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        return chequeId.equals(meta.getPersistentDataContainer().get(chequeIdKey, PersistentDataType.STRING));
    }

    private static boolean emptyCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        return cursor == null || cursor.getType().isAir();
    }

    @Nullable
    private String receipt(Player player) {
        return player.getPersistentDataContainer().get(receiptKey, PersistentDataType.STRING);
    }

    private void setReceipt(Player player, Set<UUID> ids) {
        String value = RepairReceipts.format(new LinkedHashSet<>(ids));
        if (value == null) {
            player.getPersistentDataContainer().remove(receiptKey);
        } else {
            player.getPersistentDataContainer().set(receiptKey, PersistentDataType.STRING, value);
        }
    }

    private Path file(UUID account) {
        return playerData.resolve(account + ".dat");
    }

    private void finishBusy(Player player, UUID account) {
        busy.remove(account);
        locks.unlock(player);
    }

    private boolean submit(CommandSender sender, Runnable task) {
        try {
            io.execute(task);
            return true;
        } catch (RejectedExecutionException full) {
            plugin.logger().warning("Cheque queue is full for " + sender.getName());
            return false;
        }
    }

    private void onPlayer(Player player, Runnable action, Runnable retired) {
        if (closed) {
            retired.run();
            return;
        }
        var started = new AtomicBoolean();
        Runnable unavailable = () -> {
            if (started.compareAndSet(false, true)) retired.run();
        };
        try {
            TaskHandle handle = BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
                if (started.compareAndSet(false, true)) {
                    if (!closed) action.run();
                    else retired.run();
                }
            }, unavailable, 0L);
            if (BukkitServerThread.isFolia() && handle == TaskHandle.NONE) unavailable.run();
        } catch (RuntimeException failure) {
            plugin.logger().warning("Cheque could not schedule player work for " + player.getName() + ": " + failure.getMessage());
            unavailable.run();
        }
    }

    private void onPlayerNow(Player player, Runnable action, Runnable retired) {
        if (closed) {
            retired.run();
            return;
        }
        if (!BukkitServerThread.isFolia() || BukkitServerThread.entity(plugin, player).isServerThread()) {
            action.run();
            return;
        }
        onPlayer(player, action, retired);
    }

    private void send(CommandSender sender, Messages message, String... args) {
        if (closed) return;
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), sender, () -> {
            if (!closed && (!(sender instanceof Player p) || p.isOnline())) {
                plugin.getMessageManager().send(sender, message, args);
            }
        });
    }
}
