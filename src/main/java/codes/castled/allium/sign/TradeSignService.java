package codes.castled.allium.sign;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.economy.EconomyDisplay;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyReservations;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomyRuntime;
import codes.castled.allium.economy.Money;
import codes.castled.allium.economy.PaidActionOutcome;
import codes.castled.allium.economy.PaidActionResult;
import codes.castled.allium.economy.PaidActionService;
import codes.castled.allium.economy.PaidWorkflowSupport;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.sign.TradeSignRepository.TradeSignRecord;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Player-owned stock {@code [Trade]} sign lifecycle backed by {@link TradeSignRepository} and
 * {@link PaidActionService}. Handles creation, customer trades (money-for-items, items-for-money,
 * and item-for-item barter), owner restocking and earnings collection, and lossless break reclaim.
 */
public final class TradeSignService implements AutoCloseable {

    private final PluginStart plugin;
    private final TradeSignRepository repository;
    private final Executor io;
    private final PaidActionService actions;
    private final Set<UUID> busySigns = ConcurrentHashMap.newKeySet();
    private final Set<UUID> busyPlayers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public TradeSignService(PluginStart plugin, TradeSignRepository repository) {
        this(plugin, repository, plugin.getScheduler().boundedPool("Allium-TradeSign", 1, 128));
    }

    TradeSignService(PluginStart plugin, TradeSignRepository repository, Executor io) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.io = Objects.requireNonNull(io, "io");
        this.actions = new PaidActionService(plugin, io);
    }

    public TradeSignRepository repository() {
        return repository;
    }

    @Override
    public void close() {
        closed = true;
        actions.close();
        busySigns.clear();
        busyPlayers.clear();
    }

    /**
     * Persists a newly created item-stocked {@code [Trade]} sign after the creator's initial stock
     * has already been removed from their inventory, then writes the sign face's PDC. Refunds the
     * removed items if database persistence fails or the sign disappears before activation.
     */
    void createItemStocked(Player creator, Block block, Side side, TradeSignSpec spec, SignIdentity created,
                           List<ItemStack> removedStock, NamespacedKey faceKey, String[] expectedLines) {
        UUID ownerId = creator.getUniqueId();
        String ownerName = creator.getName();
        TradeSignRecord record = newRecord(created.id(), ownerId, ownerName, block, side, spec);
        try {
            io.execute(() -> {
                try {
                    repository.create(record);
                } catch (SQLException | RuntimeException failure) {
                    plugin.logger().warning("Could not create trade sign " + created.id() + ": " + failure.getMessage());
                    onPlayer(creator, () -> {
                        PaidSignService.restoreItems(creator, removedStock);
                        plugin.getMessageManager().send(creator, Messages.SIGN_TRADE_STORAGE_ERROR);
                    }, () -> {});
                    return;
                }
                onSignRegion(block, () -> {
                    if (closed || !(block.getState() instanceof Sign live)
                            || !java.util.Arrays.equals(live.getSide(side).getLines(), expectedLines)) {
                        rollbackCreatedSign(created.id(), creator, removedStock);
                        return;
                    }
                    live.getPersistentDataContainer().set(faceKey, PersistentDataType.STRING, created.encode());
                    live.update(false, false);
                    onPlayer(creator, () -> plugin.getMessageManager().send(creator, Messages.SIGN_TRADE_CREATED,
                            "{stock}", formatAmount(spec.give(), spec.initialStock())), () -> {});
                }, () -> rollbackCreatedSign(created.id(), creator, removedStock));
            });
        } catch (RejectedExecutionException busy) {
            PaidSignService.restoreItems(creator, removedStock);
            plugin.getMessageManager().send(creator, Messages.ECO_BUSY);
        }
    }

    /**
     * Funds a newly created money-stocked {@code [Trade]} sign via {@link PaidActionService}:
     * reserves initial funding first, verifies the sign block still exists, inserts the repository
     * row, activates the sign PDC, and captures only once active.
     */
    void createMoneyStocked(Player creator, Block block, Side side, TradeSignSpec spec, SignIdentity created,
                            NamespacedKey faceKey, String[] expectedLines) {
        UUID workflow = UUID.randomUUID();
        Money fund = new Money(spec.initialStock());
        String formattedFund = display().format(fund);
        String fingerprint = EconomyReservations.fingerprint(
                "TRADE_SIGN_CREATE", created.id().toString(), Long.toString(spec.initialStock()));
        actions.execute(new PaidActionService.LiveRequest(
                EconomyReservations.Kind.SIGN,
                workflow,
                creator,
                fund,
                fingerprint,
                "Trade sign funding",
                () -> !closed && creator.isOnline(),
                (valid, complete) -> {
                    if (!valid.getAsBoolean()) {
                        complete.accept(PaidActionOutcome.refused("refused"));
                        return;
                    }
                    TradeSignRecord record = newRecord(created.id(), creator.getUniqueId(), creator.getName(), block, side, spec);
                    try {
                        repository.create(record);
                    } catch (SQLException | RuntimeException failure) {
                        plugin.logger().warning("Could not persist funded trade sign " + created.id() + ": " + failure.getMessage());
                        complete.accept(PaidActionOutcome.refused("refused"));
                        return;
                    }
                    onSignRegion(block, () -> {
                        if (closed || !(block.getState() instanceof Sign live)
                                || !java.util.Arrays.equals(live.getSide(side).getLines(), expectedLines)) {
                            deleteQuietly(created.id());
                            complete.accept(PaidActionOutcome.refused("refused"));
                            return;
                        }
                        live.getPersistentDataContainer().set(faceKey, PersistentDataType.STRING, created.encode());
                        live.update(false, false);
                        complete.accept(PaidActionOutcome.delivered());
                    }, () -> {
                        deleteQuietly(created.id());
                        complete.accept(PaidActionOutcome.refused("refused"));
                    });
                },
                result -> {
                    if (result.status() == PaidActionResult.Status.CAPTURED) {
                        plugin.getMessageManager().send(creator, Messages.SIGN_TRADE_CREATED,
                                "{stock}", formattedFund);
                    } else {
                        markSignUnfunded(block, side, expectedLines);
                        handlePaidFailure(creator, formattedFund, result);
                    }
                },
                true
        ));
    }

    /** Handles right-clicking an active {@code [Trade]} sign (either owner or customer). */
    void interact(Player player, Block block, Side side, SignIdentity identity,
                  BooleanSupplier faceValid, Function<String, ItemStack> itemResolver) {
        if (closed) return;
        UUID playerId = player.getUniqueId();
        UUID signId = identity.id();
        TradeSignSpec.Encoded encoded;
        try {
            encoded = TradeSignSpec.decodeArgument(identity.argument());
        } catch (IllegalArgumentException invalid) {
            return;
        }
        if (UtilitySupport.isInventoryBusy(plugin, player)) {
            plugin.getMessageManager().send(player, Messages.UTILITY_INVENTORY_BUSY, "{player}", player.getName());
            return;
        }
        if (!busyPlayers.add(playerId)) {
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
            return;
        }
        if (!busySigns.add(signId)) {
            busyPlayers.remove(playerId);
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
            return;
        }

        Runnable releaseBoth = () -> {
            busySigns.remove(signId);
            busyPlayers.remove(playerId);
        };

        try {
            io.execute(() -> {
                Optional<TradeSignRecord> loaded;
                try {
                    loaded = repository.load(signId);
                } catch (SQLException | RuntimeException failure) {
                    releaseBoth.run();
                    onPlayer(player, () -> plugin.getMessageManager().send(player, Messages.SIGN_TRADE_STORAGE_ERROR), () -> {});
                    return;
                }
                if (loaded.isEmpty()) {
                    releaseBoth.run();
                    onPlayer(player, () -> plugin.getMessageManager().send(player, Messages.SIGN_TRADE_MISSING), () -> {});
                    return;
                }
                TradeSignRecord record = loaded.get();
                onPlayer(player, () -> {
                    if (closed || !player.isOnline() || !faceValid.getAsBoolean()) {
                        releaseBoth.run();
                        return;
                    }
                    if (playerId.equals(encoded.ownerUuid())) {
                        handleOwner(player, block, side, record, faceValid, itemResolver, releaseBoth);
                    } else {
                        handleCustomer(player, block, side, record, faceValid, itemResolver, releaseBoth);
                    }
                }, releaseBoth);
            });
        } catch (RejectedExecutionException busy) {
            releaseBoth.run();
            plugin.getMessageManager().send(player, Messages.ECO_BUSY);
        }
    }

    /**
     * Enforces {@code [Trade]} sign break ownership and lossless reclaim. Returns {@code true} if
     * the break is allowed to proceed (and cleans up the repository row / returns remaining stock),
     * or {@code false} if the break must be cancelled.
     */
    boolean handleBreak(Player breaker, SignIdentity identity, Function<String, ItemStack> itemResolver) {
        TradeSignSpec.Encoded encoded;
        try {
            encoded = TradeSignSpec.decodeArgument(identity.argument());
        } catch (IllegalArgumentException invalid) {
            return true;
        }
        boolean isOwner = breaker.getUniqueId().equals(encoded.ownerUuid());
        if (isOwner) {
            if (!breaker.hasPermission("allium.signs.break.trade")) {
                return false;
            }
        } else if (!breaker.hasPermission("allium.signs.break.trade.others")) {
            plugin.getMessageManager().send(breaker, Messages.SIGN_TRADE_NOT_OWNER);
            return false;
        }

        UUID signId = identity.id();
        if (!busySigns.add(signId)) {
            plugin.getMessageManager().send(breaker, Messages.ECO_BUSY);
            return false;
        }
        try {
            Optional<TradeSignRecord> loaded = repository.load(signId);
            if (loaded.isEmpty()) {
                return true;
            }
            TradeSignRecord record = loaded.get();
            if (record.stockAmount() > 0 || record.earnedAmount() > 0) {
                if (!isOwner) {
                    plugin.getMessageManager().send(breaker, Messages.SIGN_TRADE_BREAK_NOT_EMPTY_OTHER,
                            "{owner}", record.ownerName());
                    return false;
                }
                // Check inventory capacity for any item balances before mutating state.
                ItemStack stockItem = record.giveKind() == TradeSignSpec.Kind.ITEM && record.stockAmount() > 0
                        ? itemResolver.apply(record.giveItem()) : null;
                ItemStack earnedItem = record.payKind() == TradeSignSpec.Kind.ITEM && record.earnedAmount() > 0
                        ? itemResolver.apply(record.payItem()) : null;

                boolean needsStockItem = record.giveKind() == TradeSignSpec.Kind.ITEM && record.stockAmount() > 0;
                boolean needsEarnedItem = record.payKind() == TradeSignSpec.Kind.ITEM && record.earnedAmount() > 0;
                if ((needsStockItem && stockItem == null) || (needsEarnedItem && earnedItem == null)
                        || !canFitBoth(breaker.getInventory().getStorageContents(),
                        stockItem, needsStockItem ? record.stockAmount() : 0L,
                        earnedItem, needsEarnedItem ? record.earnedAmount() : 0L)) {
                    plugin.getMessageManager().send(breaker, Messages.SIGN_TRADE_BREAK_COLLECT_FIRST);
                    return false;
                }
            }

            Optional<TradeSignRepository.ReclaimResult> reclaimedOpt = repository.deleteAndReclaim(signId);
            if (reclaimedOpt.isEmpty()) {
                return true;
            }
            TradeSignRepository.ReclaimResult reclaimed = reclaimedOpt.get();
            TradeSignRecord snap = reclaimed.snapshot();

            long moneyToRefund = 0L;
            if (snap.giveKind() == TradeSignSpec.Kind.ITEM && reclaimed.stockAmount() > 0) {
                ItemStack template = itemResolver.apply(snap.giveItem());
                if (template != null) {
                    PaidSignService.deliverItems(breaker, template, (int) reclaimed.stockAmount());
                }
            } else if (snap.giveKind() == TradeSignSpec.Kind.MONEY && reclaimed.stockAmount() > 0) {
                moneyToRefund += reclaimed.stockAmount();
            }

            if (snap.payKind() == TradeSignSpec.Kind.ITEM && reclaimed.earnedAmount() > 0) {
                ItemStack template = itemResolver.apply(snap.payItem());
                if (template != null) {
                    PaidSignService.deliverItems(breaker, template, (int) reclaimed.earnedAmount());
                }
            } else if (snap.payKind() == TradeSignSpec.Kind.MONEY && reclaimed.earnedAmount() > 0) {
                moneyToRefund += reclaimed.earnedAmount();
            }

            if (moneyToRefund > 0) {
                long refundMinor = moneyToRefund;
                UUID ownerId = snap.ownerUuid();
                try {
                    io.execute(() -> depositRefundOnBreak(breaker, ownerId, snap.signId(), refundMinor));
                } catch (RejectedExecutionException busy) {
                    plugin.logger().warning("Could not schedule money refund (" + refundMinor + ") for broken trade sign " + signId);
                }
            }
            if (reclaimed.stockAmount() > 0 || reclaimed.earnedAmount() > 0) {
                plugin.getMessageManager().send(breaker, Messages.SIGN_TRADE_RECLAIMED);
            }
            return true;
        } catch (SQLException | RuntimeException failure) {
            plugin.logger().warning("Could not process break for trade sign " + signId + ": " + failure.getMessage());
            plugin.getMessageManager().send(breaker, Messages.SIGN_TRADE_STORAGE_ERROR);
            return false;
        } finally {
            busySigns.remove(signId);
        }
    }

    private void handleOwner(Player owner, Block block, Side side, TradeSignRecord record,
                             BooleanSupplier faceValid, Function<String, ItemStack> itemResolver,
                             Runnable releaseBoth) {
        ItemStack held = owner.getInventory().getItemInMainHand();
        ItemStack giveTemplate = record.giveKind() == TradeSignSpec.Kind.ITEM ? itemResolver.apply(record.giveItem()) : null;
        boolean holdingStockItem = giveTemplate != null && held != null && !held.getType().isAir() && matches(giveTemplate, held);
        boolean wantsRestock = owner.isSneaking() || holdingStockItem;

        if (wantsRestock) {
            if (record.giveKind() == TradeSignSpec.Kind.ITEM) {
                if (giveTemplate == null) {
                    releaseBoth.run();
                    return;
                }
                int available = PaidSignService.countMatching(owner.getInventory().getStorageContents(), giveTemplate);
                int toAdd = holdingStockItem ? Math.min(held.getAmount(), available)
                        : Math.min((int) Math.min(Integer.MAX_VALUE, record.giveAmount()), available);
                if (toAdd <= 0) {
                    releaseBoth.run();
                    plugin.getMessageManager().send(owner, Messages.SIGN_SELL_NOT_ENOUGH,
                            "{amount}", Long.toString(record.giveAmount()),
                            "{item}", itemLabel(giveTemplate, record.giveItem()),
                            "{available}", "0");
                    return;
                }
                List<ItemStack> removed = PaidSignService.removeMatching(owner, giveTemplate, toAdd);
                try {
                    io.execute(() -> {
                        try {
                            Optional<TradeSignRecord> updated = repository.applyDelta(record.signId(), toAdd, 0L);
                            if (updated.isEmpty()) {
                                releaseBoth.run();
                                onPlayer(owner, () -> {
                                    PaidSignService.restoreItems(owner, removed);
                                    plugin.getMessageManager().send(owner, Messages.SIGN_TRADE_MISSING);
                                }, () -> {});
                                return;
                            }
                            TradeSignRecord after = updated.get();
                            updateSignLines(block, side, after);
                            releaseBoth.run();
                            onPlayer(owner, () -> plugin.getMessageManager().send(owner, Messages.SIGN_TRADE_RESTOCKED,
                                    "{added}", Integer.toString(toAdd),
                                    "{item}", itemLabel(giveTemplate, record.giveItem()),
                                    "{stock}", Long.toString(after.stockAmount())), () -> {});
                        } catch (SQLException | RuntimeException failure) {
                            releaseBoth.run();
                            onPlayer(owner, () -> {
                                PaidSignService.restoreItems(owner, removed);
                                plugin.getMessageManager().send(owner, Messages.SIGN_TRADE_STORAGE_ERROR);
                            }, () -> {});
                        }
                    });
                } catch (RejectedExecutionException busy) {
                    releaseBoth.run();
                    PaidSignService.restoreItems(owner, removed);
                    plugin.getMessageManager().send(owner, Messages.ECO_BUSY);
                }
                return;
            }

            // Money-stocked trade sign: sneak-right-click funds one more trade batch.
            Money batch = new Money(record.giveAmount());
            String formattedBatch = display().format(batch);
            UUID workflow = UUID.randomUUID();
            String fingerprint = EconomyReservations.fingerprint(
                    "TRADE_SIGN_FUND", record.signId().toString(), Long.toString(record.version()));
            actions.execute(new PaidActionService.LiveRequest(
                    EconomyReservations.Kind.SIGN,
                    workflow,
                    owner,
                    batch,
                    fingerprint,
                    "Trade sign restock",
                    () -> !closed && faceValid.getAsBoolean(),
                    (valid, complete) -> {
                        if (!valid.getAsBoolean()) {
                            complete.accept(PaidActionOutcome.refused("refused"));
                            return;
                        }
                        try {
                            Optional<TradeSignRecord> updated = repository.applyDelta(record.signId(), batch.minor(), 0L);
                            if (updated.isEmpty()) {
                                complete.accept(PaidActionOutcome.refused("refused"));
                                return;
                            }
                            updateSignLines(block, side, updated.get());
                            complete.accept(PaidActionOutcome.delivered());
                        } catch (SQLException | RuntimeException failure) {
                            complete.accept(PaidActionOutcome.refused("refused"));
                        }
                    },
                    result -> {
                        releaseBoth.run();
                        if (result.status() == PaidActionResult.Status.CAPTURED) {
                            plugin.getMessageManager().send(owner, Messages.SIGN_TRADE_FUNDED,
                                    "{amount}", formattedBatch);
                        } else {
                            handlePaidFailure(owner, formattedBatch, result);
                        }
                    },
                    true
            ));
            return;
        }

        // Normal owner click: collect earnings if any, else report status.
        if (record.earnedAmount() <= 0) {
            releaseBoth.run();
            plugin.getMessageManager().send(owner, Messages.SIGN_TRADE_STATUS,
                    "{stock}", formatAmount(record.giveOffer(), record.stockAmount()),
                    "{earned}", formatAmount(record.payOffer(), 0L));
            return;
        }

        if (record.payKind() == TradeSignSpec.Kind.ITEM) {
            ItemStack payTemplate = itemResolver.apply(record.payItem());
            if (payTemplate == null) {
                releaseBoth.run();
                return;
            }
            int fit = PaidSignService.maxFit(owner.getInventory().getStorageContents(), payTemplate);
            if (fit <= 0) {
                releaseBoth.run();
                plugin.getMessageManager().send(owner, Messages.SIGN_BUY_INVENTORY_FULL,
                        "{amount}", Long.toString(record.earnedAmount()),
                        "{item}", itemLabel(payTemplate, record.payItem()));
                return;
            }
            try {
                io.execute(() -> {
                    try {
                        Optional<TradeSignRepository.CollectResult> collectedOpt = repository.collectEarned(record.signId(), fit);
                        if (collectedOpt.isEmpty() || collectedOpt.get().collected() <= 0) {
                            releaseBoth.run();
                            return;
                        }
                        int taken = (int) collectedOpt.get().collected();
                        onPlayer(owner, () -> {
                            PaidSignService.deliverItems(owner, payTemplate, taken);
                            releaseBoth.run();
                            plugin.getMessageManager().send(owner, Messages.SIGN_TRADE_COLLECTED_ITEMS,
                                    "{amount}", Integer.toString(taken),
                                    "{item}", itemLabel(payTemplate, record.payItem()));
                        }, () -> {
                            restoreEarnedQuietly(record.signId(), taken);
                            releaseBoth.run();
                        });
                    } catch (SQLException | RuntimeException failure) {
                        releaseBoth.run();
                        onPlayer(owner, () -> plugin.getMessageManager().send(owner, Messages.SIGN_TRADE_STORAGE_ERROR), () -> {});
                    }
                });
            } catch (RejectedExecutionException busy) {
                releaseBoth.run();
                plugin.getMessageManager().send(owner, Messages.ECO_BUSY);
            }
            return;
        }

        // Collect earned money into owner's economy account.
        try {
            io.execute(() -> {
                long collected = 0L;
                try {
                    Optional<TradeSignRepository.CollectResult> collectedOpt = repository.collectEarned(record.signId(), Long.MAX_VALUE);
                    if (collectedOpt.isEmpty() || collectedOpt.get().collected() <= 0) {
                        releaseBoth.run();
                        return;
                    }
                    collected = collectedOpt.get().collected();
                    Money payout = new Money(collected);
                    UUID opId = UUID.randomUUID();
                    EconomyResult.Mutation mutation = depositMoney(opId, "TRADE_SIGN_COLLECT", owner.getUniqueId(), payout);
                    long finalCollected = collected;
                    if (mutation instanceof EconomyResult.Committed) {
                        releaseBoth.run();
                        onPlayer(owner, () -> plugin.getMessageManager().send(owner, Messages.SIGN_TRADE_COLLECTED_MONEY,
                                "{amount}", display().format(payout)), () -> {});
                    } else if (mutation instanceof EconomyResult.Unknown) {
                        releaseBoth.run();
                        plugin.logger().warning("Trade sign collect deposit unknown for " + owner.getName()
                                + " (sign=" + record.signId() + ", op=" + opId + ", minor=" + finalCollected + ").");
                        onPlayer(owner, () -> plugin.getMessageManager().send(owner, Messages.ECO_UNKNOWN,
                                "{operation}", opId.toString()), () -> {});
                    } else {
                        restoreEarnedQuietly(record.signId(), finalCollected);
                        releaseBoth.run();
                        onPlayer(owner, () -> {
                            if (mutation instanceof EconomyResult.Rejected rejected) {
                                EconomyRuntime.sendRefusal(plugin, owner, rejected.reason());
                            } else {
                                plugin.getMessageManager().send(owner, Messages.ECO_STORAGE);
                            }
                        }, () -> {});
                    }
                } catch (SQLException | RuntimeException failure) {
                    if (collected > 0) restoreEarnedQuietly(record.signId(), collected);
                    releaseBoth.run();
                    onPlayer(owner, () -> plugin.getMessageManager().send(owner, Messages.SIGN_TRADE_STORAGE_ERROR), () -> {});
                }
            });
        } catch (RejectedExecutionException busy) {
            releaseBoth.run();
            plugin.getMessageManager().send(owner, Messages.ECO_BUSY);
        }
    }

    private void handleCustomer(Player buyer, Block block, Side side, TradeSignRecord record,
                                BooleanSupplier faceValid, Function<String, ItemStack> itemResolver,
                                Runnable releaseBoth) {
        if (record.stockAmount() < record.giveAmount()) {
            releaseBoth.run();
            plugin.getMessageManager().send(buyer, Messages.SIGN_TRADE_OUT_OF_STOCK);
            return;
        }

        // Case 1: Customer pays MONEY -> receives ITEM.
        if (record.payKind() == TradeSignSpec.Kind.MONEY && record.giveKind() == TradeSignSpec.Kind.ITEM) {
            ItemStack giveTemplate = itemResolver.apply(record.giveItem());
            if (giveTemplate == null) {
                releaseBoth.run();
                return;
            }
            int giveCount = (int) record.giveAmount();
            String giveLabel = itemLabel(giveTemplate, record.giveItem());
            if (!PaidSignService.canFit(buyer.getInventory().getStorageContents(), giveTemplate, giveCount)) {
                releaseBoth.run();
                plugin.getMessageManager().send(buyer, Messages.SIGN_BUY_INVENTORY_FULL,
                        "{amount}", Integer.toString(giveCount), "{item}", giveLabel);
                return;
            }
            Money cost = new Money(record.payAmount());
            String formattedCost = display().format(cost);
            UUID workflow = UUID.randomUUID();
            String fingerprint = EconomyReservations.fingerprint(
                    "TRADE_SIGN_BUY", record.signId().toString(), Long.toString(record.version()));
            actions.execute(new PaidActionService.LiveRequest(
                    EconomyReservations.Kind.SIGN,
                    workflow,
                    buyer,
                    cost,
                    fingerprint,
                    "Trade sign purchase",
                    () -> !closed && faceValid.getAsBoolean(),
                    (valid, complete) -> {
                        if (!valid.getAsBoolean()
                                || UtilitySupport.isInventoryBusy(plugin, buyer)
                                || !PaidSignService.canFit(buyer.getInventory().getStorageContents(), giveTemplate, giveCount)) {
                            complete.accept(PaidActionOutcome.refused("refused"));
                            return;
                        }
                        try {
                            Optional<TradeSignRecord> updated = repository.applyDelta(
                                    record.signId(), -record.giveAmount(), record.payAmount());
                            if (updated.isEmpty()) {
                                plugin.getMessageManager().send(buyer, Messages.SIGN_TRADE_OUT_OF_STOCK);
                                complete.accept(PaidActionOutcome.refused("refused"));
                                return;
                            }
                            PaidSignService.deliverItems(buyer, giveTemplate, giveCount);
                            updateSignLines(block, side, updated.get());
                            complete.accept(PaidActionOutcome.delivered());
                        } catch (SQLException | RuntimeException failure) {
                            plugin.getMessageManager().send(buyer, Messages.SIGN_TRADE_STORAGE_ERROR);
                            complete.accept(PaidActionOutcome.refused("refused"));
                        }
                    },
                    result -> {
                        releaseBoth.run();
                        if (result.status() == PaidActionResult.Status.CAPTURED) {
                            plugin.getMessageManager().send(buyer, Messages.SIGN_BUY_BOUGHT,
                                    "{amount}", Integer.toString(giveCount),
                                    "{item}", giveLabel,
                                    "{price}", formattedCost);
                            UtilitySupport.audit(plugin, buyer, "trade_sign_buy",
                                    "sign=" + record.signId() + " owner=" + record.ownerUuid()
                                            + " give=" + giveCount + "x" + record.giveItem()
                                            + " pay=" + record.payAmount());
                        } else {
                            handlePaidFailure(buyer, formattedCost, result);
                        }
                    },
                    true
            ));
            return;
        }

        // Case 2: Customer gives ITEM -> receives MONEY from sign stock.
        if (record.payKind() == TradeSignSpec.Kind.ITEM && record.giveKind() == TradeSignSpec.Kind.MONEY) {
            ItemStack payTemplate = itemResolver.apply(record.payItem());
            if (payTemplate == null) {
                releaseBoth.run();
                return;
            }
            int payCount = (int) record.payAmount();
            String payLabel = itemLabel(payTemplate, record.payItem());
            int available = PaidSignService.countMatching(buyer.getInventory().getStorageContents(), payTemplate);
            if (available < payCount) {
                releaseBoth.run();
                plugin.getMessageManager().send(buyer, Messages.SIGN_SELL_NOT_ENOUGH,
                        "{amount}", Integer.toString(payCount),
                        "{item}", payLabel,
                        "{available}", Integer.toString(available));
                return;
            }
            List<ItemStack> removed = PaidSignService.removeMatching(buyer, payTemplate, payCount);
            Money payout = new Money(record.giveAmount());
            String formattedPayout = display().format(payout);
            try {
                io.execute(() -> {
                    boolean mutatedStock = false;
                    try {
                        Optional<TradeSignRecord> updated = repository.applyDelta(
                                record.signId(), -record.giveAmount(), record.payAmount());
                        if (updated.isEmpty()) {
                            releaseBoth.run();
                            onPlayer(buyer, () -> {
                                PaidSignService.restoreItems(buyer, removed);
                                plugin.getMessageManager().send(buyer, Messages.SIGN_TRADE_OUT_OF_STOCK);
                            }, () -> {});
                            return;
                        }
                        mutatedStock = true;
                        UUID opId = UUID.randomUUID();
                        EconomyResult.Mutation mutation = depositMoney(opId, "TRADE_SIGN_SELL", buyer.getUniqueId(), payout);
                        if (mutation instanceof EconomyResult.Committed) {
                            updateSignLines(block, side, updated.get());
                            releaseBoth.run();
                            onPlayer(buyer, () -> {
                                plugin.getMessageManager().send(buyer, Messages.SIGN_SELL_SOLD,
                                        "{amount}", Integer.toString(payCount),
                                        "{item}", payLabel,
                                        "{price}", formattedPayout);
                                UtilitySupport.audit(plugin, buyer, "trade_sign_sell",
                                        "sign=" + record.signId() + " owner=" + record.ownerUuid()
                                                + " pay=" + payCount + "x" + record.payItem()
                                                + " give=" + record.giveAmount());
                            }, () -> {});
                        } else if (mutation instanceof EconomyResult.Unknown) {
                            updateSignLines(block, side, updated.get());
                            releaseBoth.run();
                            onPlayer(buyer, () -> plugin.getMessageManager().send(buyer, Messages.ECO_UNKNOWN,
                                    "{operation}", opId.toString()), () -> {});
                        } else {
                            reverseDeltaQuietly(record.signId(), record.giveAmount(), -record.payAmount());
                            releaseBoth.run();
                            onPlayer(buyer, () -> {
                                PaidSignService.restoreItems(buyer, removed);
                                if (mutation instanceof EconomyResult.Rejected rejected) {
                                    EconomyRuntime.sendRefusal(plugin, buyer, rejected.reason());
                                } else {
                                    plugin.getMessageManager().send(buyer, Messages.ECO_STORAGE);
                                }
                            }, () -> {});
                        }
                    } catch (SQLException | RuntimeException failure) {
                        if (mutatedStock) reverseDeltaQuietly(record.signId(), record.giveAmount(), -record.payAmount());
                        releaseBoth.run();
                        onPlayer(buyer, () -> {
                            PaidSignService.restoreItems(buyer, removed);
                            plugin.getMessageManager().send(buyer, Messages.SIGN_TRADE_STORAGE_ERROR);
                        }, () -> {});
                    }
                });
            } catch (RejectedExecutionException busy) {
                releaseBoth.run();
                PaidSignService.restoreItems(buyer, removed);
                plugin.getMessageManager().send(buyer, Messages.ECO_BUSY);
            }
            return;
        }

        // Case 3: Item-for-item barter.
        ItemStack payTemplate = itemResolver.apply(record.payItem());
        ItemStack giveTemplate = itemResolver.apply(record.giveItem());
        if (payTemplate == null || giveTemplate == null) {
            releaseBoth.run();
            return;
        }
        int payCount = (int) record.payAmount();
        int giveCount = (int) record.giveAmount();
        String payLabel = itemLabel(payTemplate, record.payItem());
        String giveLabel = itemLabel(giveTemplate, record.giveItem());
        int available = PaidSignService.countMatching(buyer.getInventory().getStorageContents(), payTemplate);
        if (available < payCount) {
            releaseBoth.run();
            plugin.getMessageManager().send(buyer, Messages.SIGN_SELL_NOT_ENOUGH,
                    "{amount}", Integer.toString(payCount),
                    "{item}", payLabel,
                    "{available}", Integer.toString(available));
            return;
        }
        if (!PaidSignService.canFit(buyer.getInventory().getStorageContents(), giveTemplate, giveCount)) {
            releaseBoth.run();
            plugin.getMessageManager().send(buyer, Messages.SIGN_BUY_INVENTORY_FULL,
                    "{amount}", Integer.toString(giveCount), "{item}", giveLabel);
            return;
        }
        List<ItemStack> removed = PaidSignService.removeMatching(buyer, payTemplate, payCount);
        try {
            io.execute(() -> {
                try {
                    Optional<TradeSignRecord> updated = repository.applyDelta(
                            record.signId(), -record.giveAmount(), record.payAmount());
                    if (updated.isEmpty()) {
                        releaseBoth.run();
                        onPlayer(buyer, () -> {
                            PaidSignService.restoreItems(buyer, removed);
                            plugin.getMessageManager().send(buyer, Messages.SIGN_TRADE_OUT_OF_STOCK);
                        }, () -> {});
                        return;
                    }
                    TradeSignRecord after = updated.get();
                    onPlayer(buyer, () -> {
                        PaidSignService.deliverItems(buyer, giveTemplate, giveCount);
                        updateSignLines(block, side, after);
                        releaseBoth.run();
                        plugin.getMessageManager().send(buyer, Messages.SIGN_TRADE_TRADED,
                                "{pay_amount}", Integer.toString(payCount),
                                "{pay_item}", payLabel,
                                "{give_amount}", Integer.toString(giveCount),
                                "{give_item}", giveLabel);
                    }, () -> {
                        reverseDeltaQuietly(record.signId(), record.giveAmount(), -record.payAmount());
                        releaseBoth.run();
                    });
                } catch (SQLException | RuntimeException failure) {
                    releaseBoth.run();
                    onPlayer(buyer, () -> {
                        PaidSignService.restoreItems(buyer, removed);
                        plugin.getMessageManager().send(buyer, Messages.SIGN_TRADE_STORAGE_ERROR);
                    }, () -> {});
                }
            });
        } catch (RejectedExecutionException busy) {
            releaseBoth.run();
            PaidSignService.restoreItems(buyer, removed);
            plugin.getMessageManager().send(buyer, Messages.ECO_BUSY);
        }
    }

    private EconomyResult.Mutation depositMoney(UUID opId, String reason, UUID account, Money amount) throws SQLException {
        EconomyRuntime runtime = PaidWorkflowSupport.runtime(plugin);
        if (runtime == null || !runtime.ready()) {
            return new EconomyResult.Unavailable("economy unavailable");
        }
        EconomyRequest request = runtime.engine().request(
                opId, reason, opId.toString(),
                EconomyRequest.Kind.DEPOSIT, account.toString(),
                account, null, amount);
        return runtime.synchronous(request);
    }

    private void depositRefundOnBreak(Player breaker, UUID ownerId, UUID signId, long refundMinor) {
        try {
            Money amount = new Money(refundMinor);
            UUID opId = UUID.randomUUID();
            EconomyResult.Mutation mutation = depositMoney(opId, "TRADE_SIGN_BREAK", ownerId, amount);
            if (mutation instanceof EconomyResult.Committed) {
                onPlayer(breaker, () -> plugin.getMessageManager().send(breaker, Messages.SIGN_TRADE_COLLECTED_MONEY,
                        "{amount}", display().format(amount)), () -> {});
            } else {
                plugin.logger().warning("Could not deposit broken trade sign refund (" + refundMinor
                        + " minor) to " + ownerId + " for sign " + signId + ": " + mutation);
            }
        } catch (SQLException | RuntimeException failure) {
            plugin.logger().warning("Failed to deposit broken trade sign refund for " + signId + ": " + failure.getMessage());
        }
    }

    private void updateSignLines(Block block, Side side, TradeSignRecord record) {
        boolean shortPrices = plugin.getConfig() == null || plugin.getConfig().getBoolean("sign-short-prices", true);
        EconomyDisplay fmt = display();
        String line2 = TradeSignSpec.formatStockLine(record.giveOffer(), record.stockAmount(),
                m -> fmt.formatSign(m, shortPrices));
        onSignRegion(block, () -> {
            if (closed || !(block.getState() instanceof Sign live)) return;
            var face = live.getSide(side);
            face.setLine(2, line2);
            live.update(false, false);
        }, () -> {});
    }

    private void markSignUnfunded(Block block, Side side, String[] expectedLines) {
        onSignRegion(block, () -> {
            if (closed || !(block.getState() instanceof Sign live)) return;
            var face = live.getSide(side);
            if (!java.util.Arrays.equals(face.getLines(), expectedLines)) return;
            face.setLine(0, ChatColor.RED + "[Trade]");
            live.update(false, false);
        }, () -> {});
    }

    private void rollbackCreatedSign(UUID signId, Player creator, List<ItemStack> removedStock) {
        deleteQuietly(signId);
        onPlayer(creator, () -> PaidSignService.restoreItems(creator, removedStock), () -> {});
    }

    private void deleteQuietly(UUID signId) {
        try {
            repository.delete(signId);
        } catch (SQLException | RuntimeException ignored) {
        }
    }

    private void restoreEarnedQuietly(UUID signId, long amount) {
        try {
            repository.applyDelta(signId, 0L, amount);
        } catch (SQLException | RuntimeException failure) {
            plugin.logger().warning("Could not restore trade sign earnings (" + amount + ") on " + signId + ": " + failure.getMessage());
        }
    }

    private void reverseDeltaQuietly(UUID signId, long stockDelta, long earnedDelta) {
        try {
            repository.applyDelta(signId, stockDelta, earnedDelta);
        } catch (SQLException | RuntimeException failure) {
            plugin.logger().warning("Could not reverse trade sign delta on " + signId + ": " + failure.getMessage());
        }
    }

    private void handlePaidFailure(Player player, String price, PaidActionResult result) {
        switch (result.status()) {
            case UNAVAILABLE -> plugin.getMessageManager().send(player, Messages.SIGN_PAID_UNAVAILABLE);
            case BUSY -> plugin.getMessageManager().send(player, Messages.ECO_BUSY);
            case INSUFFICIENT_FUNDS -> plugin.getMessageManager().send(player, Messages.SIGN_INSUFFICIENT_FUNDS, "{price}", price);
            case RESERVATION_REFUSED -> EconomyRuntime.sendRefusal(plugin, player, result.detail());
            case UNKNOWN_RESERVE -> {
                if (result.unknownOperationId() != null) {
                    plugin.getMessageManager().send(player, Messages.ECO_UNKNOWN, "{operation}", result.unknownOperationId().toString());
                }
            }
            case STORAGE_ERROR, CAPTURE_DEFERRED, RELEASE_DEFERRED -> plugin.getMessageManager().send(player, Messages.ECO_STORAGE);
            case CAPTURED, RELEASED -> {
            }
        }
    }

    private TradeSignRecord newRecord(UUID signId, UUID ownerId, String ownerName, Block block, Side side, TradeSignSpec spec) {
        String worldName = block.getWorld() == null ? "world" : block.getWorld().getName();
        return new TradeSignRecord(
                signId, ownerId, ownerName, worldName,
                block.getX(), block.getY(), block.getZ(), side.name(),
                spec.pay().kind(), spec.pay().itemSpec(), spec.pay().amount(),
                spec.give().kind(), spec.give().itemSpec(), spec.give().amount(),
                spec.initialStock(), 0L, 1L, System.currentTimeMillis()
        );
    }

    private EconomyDisplay display() {
        return plugin.getConfig() == null ? EconomyDisplay.DEFAULT : EconomyDisplay.read(plugin.getConfig());
    }

    private String formatAmount(TradeSignSpec.Offer offer, long amount) {
        if (offer.kind() == TradeSignSpec.Kind.MONEY) {
            return display().format(new Money(amount));
        }
        return amount + "x " + offer.itemSpec();
    }

    static boolean canFitBoth(ItemStack[] storage, @Nullable ItemStack first, long firstCount,
                              @Nullable ItemStack second, long secondCount) {
        if (firstCount > Integer.MAX_VALUE || secondCount > Integer.MAX_VALUE) {
            return false;
        }
        if (first != null && firstCount > 0 && second != null && secondCount > 0) {
            if (matches(first, second)) {
                long combined = firstCount + secondCount;
                return combined <= Integer.MAX_VALUE && PaidSignService.canFit(storage, first, (int) combined);
            }
            // Simulate placing `first` into a shallow copy before checking `second`.
            ItemStack[] copy = new ItemStack[storage.length];
            boolean liveServer = org.bukkit.Bukkit.getServer() != null;
            for (int i = 0; i < storage.length; i++) {
                copy[i] = storage[i] == null ? null
                        : (liveServer ? storage[i].clone() : new ItemStack(storage[i].getType(), storage[i].getAmount()));
            }
            int remaining = (int) firstCount;
            int maxFirst = Math.max(1, first.getMaxStackSize());
            for (int i = 0; i < copy.length && remaining > 0; i++) {
                if (copy[i] != null && !copy[i].getType().isAir() && matches(first, copy[i]) && copy[i].getAmount() < maxFirst) {
                    int put = Math.min(remaining, maxFirst - copy[i].getAmount());
                    copy[i].setAmount(copy[i].getAmount() + put);
                    remaining -= put;
                }
            }
            for (int i = 0; i < copy.length && remaining > 0; i++) {
                if (copy[i] == null || copy[i].getType().isAir()) {
                    int put = Math.min(remaining, maxFirst);
                    ItemStack placed = liveServer ? first.clone() : new ItemStack(first.getType(), put);
                    placed.setAmount(put);
                    copy[i] = placed;
                    remaining -= put;
                }
            }
            return remaining == 0 && PaidSignService.canFit(copy, second, (int) secondCount);
        }
        if (first != null && firstCount > 0) {
            return PaidSignService.canFit(storage, first, (int) firstCount);
        }
        if (second != null && secondCount > 0) {
            return PaidSignService.canFit(storage, second, (int) secondCount);
        }
        return true;
    }

    private static boolean matches(ItemStack template, ItemStack candidate) {
        if (org.bukkit.Bukkit.getServer() != null) {
            return template.isSimilar(candidate);
        }
        return template.getType() == candidate.getType();
    }

    private static String itemLabel(@Nullable ItemStack stack, String fallback) {
        if (stack != null && stack.getType() != null && !stack.getType().isAir()) {
            String[] words = stack.getType().name().toLowerCase(Locale.ROOT).split("_");
            StringBuilder out = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) continue;
                if (out.length() > 0) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            if (out.length() > 0) return out.toString();
        }
        return fallback;
    }

    private void onPlayer(Player player, Runnable task, Runnable retired) {
        if (BukkitServerThread.isFolia()) {
            var owner = BukkitServerThread.entity(plugin, player);
            if (owner.isServerThread()) {
                task.run();
                return;
            }
            if (plugin.getScheduler().runOn(owner, task, retired, 0L) == codes.castled.allium.scheduler.TaskHandle.NONE) {
                retired.run();
            }
            return;
        }
        if (plugin.getScheduler() == null || plugin.getScheduler().isServerThread()) {
            task.run();
        } else {
            plugin.getScheduler().runOnServer(task);
        }
    }

    private void onSignRegion(Block block, Runnable task, Runnable retired) {
        if (BukkitServerThread.isFolia()) {
            World world = block.getWorld();
            int cx = block.getX() >> 4;
            int cz = block.getZ() >> 4;
            var region = BukkitServerThread.region(plugin, world, cx, cz);
            if (region.isServerThread()) {
                task.run();
                return;
            }
            if (plugin.getScheduler().runOn(region, () -> {
                if (world.isChunkLoaded(cx, cz)) task.run();
                else retired.run();
            }, retired, 0L) == codes.castled.allium.scheduler.TaskHandle.NONE) {
                retired.run();
            }
            return;
        }
        if (plugin.getScheduler() == null || plugin.getScheduler().isServerThread()) {
            task.run();
        } else {
            plugin.getScheduler().runOnServer(task);
        }
    }
}
