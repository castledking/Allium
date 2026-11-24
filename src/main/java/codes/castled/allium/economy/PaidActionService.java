package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.mail.PlayerDataFiles;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shared paid-action lifecycle engine built on {@link EconomyReservations} and player-data receipt
 * proofs.
 *
 * <p>Owns reservation, owning-thread handoff (Spigot/Paper and Folia), pre- and post-delivery
 * revalidation, typed delivery outcomes ({@link PaidActionOutcome}), capture/release settlement,
 * disconnect/retirement failure policy, and receipt-backed join recovery so callers only supply
 * domain validation, mutation, and user-facing messages.
 */
public final class PaidActionService {

    /** Performs a live paid action after funds are held and validity is rechecked. */
    @FunctionalInterface
    public interface Delivery {
        /**
         * Delivers the effect on the player's owning thread. May finish synchronously or later
         * (e.g. after warmup, teleport, or global-tick handoff) by invoking {@code complete} once
         * with a typed {@link PaidActionOutcome}.
         */
        void deliver(BooleanSupplier stillValid, Consumer<PaidActionOutcome> complete);
    }

    /** Performs a synchronous player-data mutation while funds are held, before saving player data. */
    @FunctionalInterface
    public interface ReceiptMutation {
        /**
         * Runs on the player's owning thread while the reservation is held. Return
         * {@link PaidActionOutcome.Delivered} after mutating live player state so the engine can
         * append the workflow receipt and call {@link Player#saveData()} together; return
         * {@link PaidActionOutcome.Refused} or {@link PaidActionOutcome.Interrupted} to abort and
         * release without touching player data.
         */
        PaidActionOutcome apply(BooleanSupplier stillValid);
    }

    /** Configuration for receipt-backed durable mutations proven against {@code <uuid>.dat}. */
    public record ReceiptSpec(
            String receiptKey,
            NamespacedKey pdcKey,
            @Nullable String faultAfterReserve,
            @Nullable String faultAfterSave
    ) {
        public ReceiptSpec {
            Objects.requireNonNull(receiptKey, "receiptKey");
            Objects.requireNonNull(pdcKey, "pdcKey");
            if (!pdcKey.toString().equals(receiptKey)) {
                throw new IllegalStateException("receipt key " + pdcKey + " does not match proof key " + receiptKey);
            }
        }
    }

    /** Request for a live (non-receipt-file) paid action such as paid signs, RTP, or command costs. */
    public record LiveRequest(
            EconomyReservations.Kind kind,
            UUID workflowId,
            Player player,
            Money price,
            String intentFingerprint,
            String logLabel,
            BooleanSupplier stillValid,
            Delivery delivery,
            Consumer<PaidActionResult> onComplete,
            boolean trackBusy
    ) {
        public LiveRequest {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(workflowId, "workflowId");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(intentFingerprint, "intentFingerprint");
            Objects.requireNonNull(logLabel, "logLabel");
            Objects.requireNonNull(stillValid, "stillValid");
            Objects.requireNonNull(delivery, "delivery");
            Objects.requireNonNull(onComplete, "onComplete");
        }
    }

    /** Request for a receipt-backed durable player-data mutation such as paid repairs. */
    public record ReceiptRequest(
            EconomyReservations.Kind kind,
            UUID workflowId,
            Player player,
            Money price,
            String intentFingerprint,
            String logLabel,
            ReceiptSpec receiptSpec,
            BooleanSupplier stillValid,
            ReceiptMutation mutation,
            Consumer<PaidActionResult> onComplete
    ) {
        public ReceiptRequest {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(workflowId, "workflowId");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(intentFingerprint, "intentFingerprint");
            Objects.requireNonNull(logLabel, "logLabel");
            Objects.requireNonNull(receiptSpec, "receiptSpec");
            Objects.requireNonNull(stillValid, "stillValid");
            Objects.requireNonNull(mutation, "mutation");
            Objects.requireNonNull(onComplete, "onComplete");
        }
    }

    /** Result of a per-player receipt-backed recovery pass. */
    public record RecoveryResult(int captured, int released, Set<UUID> settled) {}

    private final PluginStart plugin;
    @Nullable
    private final Executor io;
    @Nullable
    private final Path playerData;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public PaidActionService(PluginStart plugin, @Nullable Executor io) {
        this(plugin, io, safePlayerDataDir(plugin));
    }

    public PaidActionService(PluginStart plugin, @Nullable Executor io, @Nullable Path playerData) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.io = io;
        this.playerData = playerData;
    }

    @Nullable
    private static Path safePlayerDataDir(PluginStart plugin) {
        try {
            return plugin.getServer() == null ? null : PlayerDataFiles.directory(plugin.getServer());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean busy(@Nullable Player player) {
        return player != null && busy.contains(player.getUniqueId());
    }

    public void clearBusy(UUID account) {
        busy.remove(account);
    }

    // -- Live reservation-backed paid actions ---------------------------------------------------

    /**
     * Executes a live paid action: checks readiness & concurrency, reserves funds asynchronously,
     * switches back to the player's owning thread, revalidates, runs {@link Delivery}, settles
     * (capture iff {@link PaidActionOutcome.Delivered}, release otherwise), and reports the
     * terminal {@link PaidActionResult}.
     */
    public void execute(LiveRequest request) {
        Objects.requireNonNull(request, "request");
        UUID account = request.player().getUniqueId();
        if (closed || io == null || !PaidWorkflowSupport.ready(plugin)) {
            request.onComplete().accept(PaidActionResult.immediate(
                    PaidActionResult.Status.UNAVAILABLE, request.workflowId(), "economy unavailable"));
            return;
        }
        if (request.trackBusy() && !busy.add(account)) {
            request.onComplete().accept(PaidActionResult.immediate(
                    PaidActionResult.Status.BUSY, request.workflowId(), "player already has a paid action in flight"));
            return;
        }
        try {
            io.execute(() -> reserveLive(request));
        } catch (RejectedExecutionException full) {
            releaseBusy(request);
            request.onComplete().accept(PaidActionResult.immediate(
                    PaidActionResult.Status.BUSY, request.workflowId(), "worker queue full"));
        }
    }

    private void reserveLive(LiveRequest request) {
        if (closed) {
            releaseBusy(request);
            return;
        }
        EconomyResult.Mutation result;
        try {
            result = PaidWorkflowSupport.reservations(plugin).reserve(
                    request.kind(),
                    request.workflowId(),
                    request.player().getUniqueId(),
                    request.price(),
                    request.intentFingerprint(),
                    c -> true
            );
        } catch (SQLException | RuntimeException failure) {
            warn(request.logLabel(), request.workflowId(), "could not reserve the price", failure);
            onPlayer(request.player(), () -> {
                releaseBusy(request);
                request.onComplete().accept(PaidActionResult.immediate(
                        PaidActionResult.Status.STORAGE_ERROR, request.workflowId(), String.valueOf(failure.getMessage())));
            }, () -> releaseBusy(request));
            return;
        }

        if (result instanceof EconomyResult.Committed && closed) {
            settleLiveOnWorker(request, PaidActionOutcome.Interrupted.CLOSED);
            return;
        }

        boolean mayBeHeld = result instanceof EconomyResult.Committed || result instanceof EconomyResult.Unknown;
        onPlayer(request.player(), () -> {
            if (result instanceof EconomyResult.Committed) {
                deliverLive(request);
            } else if (result instanceof EconomyResult.Rejected refused) {
                releaseBusy(request);
                PaidActionResult.Status status = isFundsRefusal(refused.reason())
                        ? PaidActionResult.Status.INSUFFICIENT_FUNDS
                        : PaidActionResult.Status.RESERVATION_REFUSED;
                request.onComplete().accept(PaidActionResult.immediate(status, request.workflowId(), refused.reason()));
            } else if (result instanceof EconomyResult.Unknown unknown) {
                // Delivery never started, so releasing any possibly-committed hold is always safe.
                request.onComplete().accept(new PaidActionResult(
                        PaidActionResult.Status.UNKNOWN_RESERVE,
                        request.workflowId(),
                        null,
                        unknown.operationId(),
                        "uncertain reserve outcome",
                        PaidActionOutcome.Interrupted.CLOSED
                ));
                settleLive(request, PaidActionOutcome.interrupted("unknown reserve"), false);
            } else {
                releaseBusy(request);
                request.onComplete().accept(PaidActionResult.immediate(
                        PaidActionResult.Status.STORAGE_ERROR, request.workflowId(), result.getClass().getSimpleName()));
            }
        }, () -> {
            if (mayBeHeld) {
                settleLive(request, PaidActionOutcome.Interrupted.RETIRED, true);
            } else {
                releaseBusy(request);
            }
        });
    }

    private void deliverLive(LiveRequest request) {
        AtomicBoolean completed = new AtomicBoolean();
        BooleanSupplier valid = () -> !completed.get() && isCurrentSession(request.player()) && request.stillValid().getAsBoolean();
        Consumer<PaidActionOutcome> complete = outcome -> {
            PaidActionOutcome resolved = outcome == null ? PaidActionOutcome.Refused.EMPTY : outcome;
            if (completed.compareAndSet(false, true)) {
                settleLive(request, resolved, true);
            }
        };
        try {
            if (valid.getAsBoolean()) {
                request.delivery().deliver(valid, complete);
            } else {
                complete.accept(isCurrentSession(request.player())
                        ? PaidActionOutcome.Refused.EMPTY
                        : PaidActionOutcome.Interrupted.DISCONNECTED);
            }
        } catch (RuntimeException failure) {
            warn(request.logLabel(), request.workflowId(), "action failed", failure);
            complete.accept(PaidActionOutcome.interrupted(failure.getMessage()));
        }
    }

    private void settleLive(LiveRequest request, PaidActionOutcome outcome, boolean notifyCaller) {
        try {
            io.execute(() -> settleLiveOnWorker(request, outcome, notifyCaller));
        } catch (RejectedExecutionException full) {
            warn(request.logLabel(), request.workflowId(),
                    "could not queue settlement; restart recovery will release any outstanding hold", full);
            onPlayer(request.player(), () -> {
                releaseBusy(request);
                if (notifyCaller) {
                    PaidActionResult.Status status = outcome.shouldCapture()
                            ? PaidActionResult.Status.CAPTURE_DEFERRED
                            : PaidActionResult.Status.RELEASE_DEFERRED;
                    request.onComplete().accept(new PaidActionResult(
                            status, request.workflowId(), null, null, "settlement queue full", outcome));
                }
            }, () -> releaseBusy(request));
        }
    }

    private void settleLiveOnWorker(LiveRequest request, PaidActionOutcome outcome) {
        settleLiveOnWorker(request, outcome, false);
    }

    private void settleLiveOnWorker(LiveRequest request, PaidActionOutcome outcome, boolean notifyCaller) {
        boolean capture = outcome.shouldCapture();
        boolean settled = false;
        try {
            EconomyReservations holds = PaidWorkflowSupport.reservations(plugin);
            EconomyResult.Mutation result = capture
                    ? holds.capture(request.kind(), request.workflowId(), c -> true)
                    : holds.release(request.kind(), request.workflowId(), c -> true);
            settled = result instanceof EconomyResult.Committed
                    || (!capture && result instanceof EconomyResult.Rejected refused
                    && "MISSING_RESERVATION".equals(refused.reason()));
            if (!settled) {
                warn(request.logLabel(), request.workflowId(),
                        "settlement unconfirmed (" + result.getClass().getSimpleName()
                                + "); restart recovery will settle any outstanding hold", null);
            }
        } catch (SQLException | RuntimeException failure) {
            warn(request.logLabel(), request.workflowId(),
                    "settlement failed; restart recovery will settle any outstanding hold", failure);
        }

        boolean confirmed = settled;
        UUID captureOp = (confirmed && capture)
                ? EconomyReservations.operation(request.kind(), request.workflowId(), EconomyRequest.Kind.CAPTURE)
                : null;
        PaidActionResult.Status status = capture
                ? (confirmed ? PaidActionResult.Status.CAPTURED : PaidActionResult.Status.CAPTURE_DEFERRED)
                : (confirmed ? PaidActionResult.Status.RELEASED : PaidActionResult.Status.RELEASE_DEFERRED);

        onPlayer(request.player(), () -> {
            releaseBusy(request);
            if (notifyCaller) {
                request.onComplete().accept(new PaidActionResult(
                        status, request.workflowId(), captureOp, null, "", outcome));
            }
        }, () -> releaseBusy(request));
    }

    // -- Receipt-backed durable player-data paid actions ----------------------------------------

    /**
     * Executes a receipt-backed durable paid action: saves and proves the baseline player file,
     * reserves funds asynchronously, mutates player state on the owning thread alongside writing
     * the receipt into player PDC and calling {@link Player#saveData()}, proves the saved file,
     * and captures (or releases on refusal/invalidation).
     */
    public void executeReceiptBacked(ReceiptRequest request) {
        Objects.requireNonNull(request, "request");
        Player player = request.player();
        UUID account = player.getUniqueId();
        if (closed || io == null || !PaidWorkflowSupport.ready(plugin) || playerData == null) {
            request.onComplete().accept(PaidActionResult.immediate(
                    PaidActionResult.Status.UNAVAILABLE, request.workflowId(), "economy or playerdata unavailable"));
            return;
        }
        // Save the live player state first so the saved file provably matches baseline before any hold exists.
        try {
            player.saveData();
        } catch (RuntimeException error) {
            request.onComplete().accept(PaidActionResult.immediate(
                    PaidActionResult.Status.UNAVAILABLE, request.workflowId(), "could not save baseline player data"));
            return;
        }
        String baseline = readReceipt(player, request.receiptSpec().pdcKey());
        Path file = playerDataFile(account);
        if (!busy.add(account)) {
            request.onComplete().accept(PaidActionResult.immediate(
                    PaidActionResult.Status.BUSY, request.workflowId(), "player already has a paid action in flight"));
            return;
        }
        Runnable forget = () -> busy.remove(account);
        try {
            io.execute(() -> {
                try {
                    PaidWorkflowSupport.prove(file, request.receiptSpec().receiptKey(), baseline);
                    EconomyResult.Mutation reserved = PaidWorkflowSupport.reservations(plugin).reserve(
                            request.kind(),
                            request.workflowId(),
                            account,
                            request.price(),
                            request.intentFingerprint(),
                            c -> true
                    );
                    if (reserved instanceof EconomyResult.Rejected refused) {
                        onPlayer(player, () -> {
                            busy.remove(account);
                            PaidActionResult.Status status = isFundsRefusal(refused.reason())
                                    ? PaidActionResult.Status.INSUFFICIENT_FUNDS
                                    : PaidActionResult.Status.RESERVATION_REFUSED;
                            request.onComplete().accept(PaidActionResult.immediate(
                                    status, request.workflowId(), refused.reason()));
                        }, forget);
                        return;
                    }
                    PaidWorkflowSupport.committed(reserved);
                } catch (PaidWorkflowSupport.UnknownCommit unknown) {
                    onPlayer(player, () -> {
                        busy.remove(account);
                        request.onComplete().accept(new PaidActionResult(
                                PaidActionResult.Status.UNKNOWN_RESERVE,
                                request.workflowId(),
                                null,
                                unknown.operationId(),
                                "uncertain reserve outcome",
                                null
                        ));
                    }, forget);
                    return;
                } catch (SQLException | RuntimeException error) {
                    warn(request.logLabel(), request.workflowId(), "was not started", error);
                    onPlayer(player, () -> {
                        busy.remove(account);
                        request.onComplete().accept(PaidActionResult.immediate(
                                PaidActionResult.Status.STORAGE_ERROR, request.workflowId(), String.valueOf(error.getMessage())));
                    }, forget);
                    return;
                }

                if (request.receiptSpec().faultAfterReserve() != null
                        && FaultInjection.trip(request.receiptSpec().faultAfterReserve())) {
                    plugin.logger().warning("Fault " + request.receiptSpec().faultAfterReserve()
                            + ": halting with " + request.logLabel() + " " + request.workflowId() + " held (simulated crash).");
                    Runtime.getRuntime().halt(77);
                }

                onPlayer(player, () -> applyReceiptMutation(request, baseline), forget);
            });
        } catch (RejectedExecutionException full) {
            busy.remove(account);
            request.onComplete().accept(PaidActionResult.immediate(
                    PaidActionResult.Status.BUSY, request.workflowId(), "worker queue full"));
        }
    }

    private void applyReceiptMutation(ReceiptRequest request, @Nullable String baseline) {
        Player player = request.player();
        UUID account = player.getUniqueId();
        UUID workflow = request.workflowId();
        if (!isCurrentSession(player)) {
            // Player left: the saved file has no receipt, so their next join or startup recovery releases the hold.
            busy.remove(account);
            return;
        }
        BooleanSupplier valid = () -> isCurrentSession(player) && request.stillValid().getAsBoolean();
        PaidActionOutcome outcome;
        try {
            outcome = valid.getAsBoolean()
                    ? request.mutation().apply(valid)
                    : PaidActionOutcome.Refused.EMPTY;
            if (outcome == null) outcome = PaidActionOutcome.Refused.EMPTY;
        } catch (RuntimeException failure) {
            warn(request.logLabel(), workflow, "mutation failed", failure);
            outcome = PaidActionOutcome.interrupted(failure.getMessage());
        }

        if (!outcome.shouldCapture()) {
            releaseReceiptHold(request, baseline, outcome);
            return;
        }

        Set<UUID> receipts = RepairReceipts.parse(readReceipt(player, request.receiptSpec().pdcKey()));
        receipts.add(workflow);
        writeReceipt(player, request.receiptSpec().pdcKey(), receipts);
        try {
            player.saveData();
        } catch (RuntimeException error) {
            warn(request.logLabel(), workflow, "could not save player after mutation", error);
            busy.remove(account);
            request.onComplete().accept(new PaidActionResult(
                    PaidActionResult.Status.CAPTURE_DEFERRED, workflow, null, null, "save failed", outcome));
            return;
        }

        String expected = readReceipt(player, request.receiptSpec().pdcKey());
        if (request.receiptSpec().faultAfterSave() != null
                && FaultInjection.trip(request.receiptSpec().faultAfterSave())) {
            plugin.logger().warning("Fault " + request.receiptSpec().faultAfterSave()
                    + ": halting with " + request.logLabel() + " " + workflow + " saved but not captured.");
            Runtime.getRuntime().halt(77);
        }

        Path file = playerDataFile(account);
        PaidActionOutcome deliveredOutcome = outcome;
        try {
            io.execute(() -> {
                boolean captured;
                try {
                    PaidWorkflowSupport.prove(file, request.receiptSpec().receiptKey(), expected);
                    captured = PaidWorkflowSupport.committed(PaidWorkflowSupport.reservations(plugin)
                            .capture(request.kind(), workflow, c -> true));
                } catch (SQLException | RuntimeException error) {
                    warn(request.logLabel(), workflow, "is saved; capture waits for next join", error);
                    captured = false;
                }
                boolean done = captured;
                UUID captureOp = done ? EconomyReservations.operation(request.kind(), workflow, EconomyRequest.Kind.CAPTURE) : null;
                onPlayer(player, () -> {
                    busy.remove(account);
                    if (done && plugin.getServer().getPlayer(account) == player) {
                        Set<UUID> left = RepairReceipts.parse(readReceipt(player, request.receiptSpec().pdcKey()));
                        left.remove(workflow);
                        writeReceipt(player, request.receiptSpec().pdcKey(), left);
                    }
                    request.onComplete().accept(new PaidActionResult(
                            done ? PaidActionResult.Status.CAPTURED : PaidActionResult.Status.CAPTURE_DEFERRED,
                            workflow, captureOp, null, "", deliveredOutcome));
                }, () -> busy.remove(account));
            });
        } catch (RejectedExecutionException full) {
            busy.remove(account);
            request.onComplete().accept(new PaidActionResult(
                    PaidActionResult.Status.CAPTURE_DEFERRED, workflow, null, null, "capture queue full", deliveredOutcome));
        }
    }

    private void releaseReceiptHold(ReceiptRequest request, @Nullable String baseline, PaidActionOutcome outcome) {
        Player player = request.player();
        UUID account = player.getUniqueId();
        UUID workflow = request.workflowId();
        Path file = playerDataFile(account);
        try {
            io.execute(() -> {
                boolean released = false;
                try {
                    PaidWorkflowSupport.prove(file, request.receiptSpec().receiptKey(), baseline);
                    released = PaidWorkflowSupport.committed(PaidWorkflowSupport.reservations(plugin)
                            .release(request.kind(), workflow, c -> true));
                } catch (SQLException | RuntimeException error) {
                    warn(request.logLabel(), workflow, "hold stays held until next join", error);
                }
                boolean done = released;
                onPlayer(player, () -> {
                    busy.remove(account);
                    request.onComplete().accept(new PaidActionResult(
                            done ? PaidActionResult.Status.RELEASED : PaidActionResult.Status.RELEASE_DEFERRED,
                            workflow, null, null, "", outcome));
                }, () -> busy.remove(account));
            });
        } catch (RejectedExecutionException full) {
            busy.remove(account);
            request.onComplete().accept(new PaidActionResult(
                    PaidActionResult.Status.RELEASE_DEFERRED, workflow, null, null, "release queue full", outcome));
        }
    }

    // -- Receipt-backed join recovery -----------------------------------------------------------

    /**
     * Reconciles all {@code HELD} reservations of {@code kind} for {@code player} against their
     * saved playerdata file: captures when the receipt is present, releases when absent, and prunes
     * settled receipts from live PDC.
     */
    public void recoverReceipts(Player player, EconomyReservations.Kind kind, ReceiptSpec spec,
                                String logLabel, Consumer<RecoveryResult> onRecovered) {
        if (closed || io == null || !PaidWorkflowSupport.ready(plugin) || playerData == null) {
            return;
        }
        UUID account = player.getUniqueId();
        busy.add(account);
        Path file = playerDataFile(account);
        try {
            io.execute(() -> {
                int captured = 0;
                int released = 0;
                Set<UUID> settled = new HashSet<>();
                try {
                    EconomyReservations holds = PaidWorkflowSupport.reservations(plugin);
                    List<EconomyReservations.Reservation> rows = holds.list(account).stream()
                            .filter(r -> r.kind() == kind).toList();
                    Set<UUID> saved = rows.stream().anyMatch(r -> r.state().equals("HELD"))
                            ? RepairReceipts.parse(PlayerDataReceiptProof.read(file, spec.receiptKey()))
                            : Set.of();
                    for (EconomyReservations.Reservation row : rows) {
                        if (!row.state().equals("HELD")) {
                            settled.add(row.id());
                            continue;
                        }
                        if (holds.operatorResolved(row.kind(), row.id())) {
                            continue;
                        }
                        boolean charge = saved.contains(row.id());
                        EconomyResult.Mutation result = charge
                                ? holds.capture(row.kind(), row.id(), c -> true)
                                : holds.release(row.kind(), row.id(), c -> true);
                        if (PaidWorkflowSupport.committed(result)) {
                            settled.add(row.id());
                            if (charge) captured++;
                            else released++;
                        }
                    }
                } catch (SQLException | IOException | RuntimeException error) {
                    plugin.logger().warning(logLabel + " recovery for " + player.getName()
                            + " is retried on their next join: " + error.getMessage());
                }
                RecoveryResult summary = new RecoveryResult(captured, released, Set.copyOf(settled));
                onPlayer(player, () -> {
                    busy.remove(account);
                    if (plugin.getServer().getPlayer(account) != player) {
                        return;
                    }
                    Set<UUID> live = RepairReceipts.parse(readReceipt(player, spec.pdcKey()));
                    if (live.removeAll(summary.settled())) {
                        writeReceipt(player, spec.pdcKey(), live);
                    }
                    onRecovered.accept(summary);
                }, () -> busy.remove(account));
            });
        } catch (RejectedExecutionException full) {
            busy.remove(account);
            plugin.logger().warning(logLabel + " recovery queue is full for " + player.getName());
        }
    }

    // -- Shared helpers -------------------------------------------------------------------------

    public boolean isCurrentSession(Player player) {
        return !closed && player.isOnline()
                && plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }

    public static boolean isFundsRefusal(@Nullable String reason) {
        return "INSUFFICIENT_FUNDS".equals(reason) || "MISSING_ACCOUNT".equals(reason);
    }

    private void releaseBusy(LiveRequest request) {
        if (request.trackBusy()) {
            busy.remove(request.player().getUniqueId());
        }
    }

    @Nullable
    private static String readReceipt(Player player, NamespacedKey key) {
        return player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private static void writeReceipt(Player player, NamespacedKey key, Set<UUID> ids) {
        String value = RepairReceipts.format(new LinkedHashSet<>(ids));
        if (value == null) {
            player.getPersistentDataContainer().remove(key);
        } else {
            player.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        }
    }

    private Path playerDataFile(UUID account) {
        return playerData.resolve(account + ".dat");
    }

    private void onPlayer(Player player, Runnable action, Runnable retired) {
        AtomicBoolean started = new AtomicBoolean();
        Runnable unavailable = () -> {
            if (started.compareAndSet(false, true)) retired.run();
        };
        try {
            TaskHandle handle = BukkitServerThread.runFor(plugin, plugin.getScheduler(), player, () -> {
                if (started.compareAndSet(false, true)) {
                    if (closed) retired.run();
                    else action.run();
                }
            }, unavailable, 0L);
            if (BukkitServerThread.isFolia() && handle == TaskHandle.NONE) {
                unavailable.run();
            }
        } catch (RuntimeException failure) {
            plugin.logger().warning("Paid action could not schedule player work for "
                    + player.getName() + ": " + failure.getMessage());
            unavailable.run();
        }
    }

    private void warn(String label, UUID workflowId, String message, @Nullable Throwable cause) {
        if (plugin.logger() != null) {
            plugin.logger().warning(label + " " + workflowId + ": " + message
                    + (cause == null ? "" : ": " + cause.getMessage()));
        }
    }
}
