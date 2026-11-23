package codes.castled.allium.mail;

import codes.castled.allium.PluginStart;
import codes.castled.allium.economy.*;
import codes.castled.allium.economy.EconomyResult.*;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.IgnoreRepository;
import codes.castled.allium.messaging.IgnoreService;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.messaging.NotificationService;
import codes.castled.allium.messaging.UntrustedText;
import codes.castled.allium.module.MessagingModule;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.naming.PlayerNameService;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Text mail and gifts (items by mail). Storage work runs on a bounded pool; inventories, item
 * encoding and messages on the server thread.
 *
 * <h2>Sending a gift</h2>
 * <ol>
 *   <li>The held stack (or part of it) is copied and encoded, and the sender's inventory is locked.</li>
 *   <li>The gift is stored PENDING, with the mailbox and rate limits checked in the same
 *       transaction. If that fails, nothing left the inventory.</li>
 *   <li>Back on the server thread, the items are taken, a {@code S/<mail>} receipt is added to the
 *       player's own persistent data, and the player's data is saved: the inventory change and
 *       its receipt are now durable together.</li>
 *   <li>The gift is activated (PENDING to READY) and the receipt removed.</li>
 * </ol>
 * If the sender leaves before step 3, the items are still in their saved inventory and the
 * PENDING gift is deleted. After a crash, the receipt decides on their next join: with it, the gift
 * is activated; without it, the items never left and the gift is deleted. Nothing is lost or
 * delivered twice.
 *
 * <h2>Claiming a gift</h2>
 * <ol>
 *   <li>A conditional update moves the gift from READY to CLAIMING with a fresh token. Only one
 *       claimer wins, across servers sharing MySQL.</li>
 *   <li>On the server thread the items are added to the inventory; what does not fit is not
 *       taken. A {@code C/<mail>/<token>/<amounts>} receipt is added and the player's data saved.</li>
 *   <li>The claim is finished under its token: the gift is deleted, or keeps what did not fit.</li>
 * </ol>
 * After a crash, the receipt again decides on the next join: the claim is finished with what the
 * receipt says was delivered, or released unchanged when there is no receipt.
 *
 * <p>Reload never interrupts either: each operation captured its limits when it started, and
 * {@code /allium reload} does not restart this service.
 */
public final class MailService implements Listener {

    public static final String FAULT_SEND_SLOW = "mail.send.slow-persist";
    public static final String FAULT_SEND_HALT = "mail.send.halt-after-take";
    public static final String FAULT_CLAIM_SLOW = "mail.claim.slow";
    public static final String FAULT_CLAIM_HALT_BEFORE = "mail.claim.halt-before-deliver";
    public static final String FAULT_CLAIM_HALT_AFTER = "mail.claim.halt-after-deliver";
    public static final String FAULT_WRITE_FAIL = "mail.write-fail";
    static final long SLOW_MILLIS = 4_000L;

    public static final String PERMISSION_BYPASS_LIMITS = "allium.mail.bypass.limits";

    /** Reserved M3 preflight policy. It must not debit money; transactional economy integration is deferred. */
    @FunctionalInterface public interface CostPolicy {
        CostPolicy FREE = (sender, kind) -> true;
        boolean allows(Player sender, MailKind kind);
    }

    private final PluginStart plugin;
    private final MailRepository repository;
    private final IgnoreRepository ignoreStorage;
    private final PlayerNameService names;
    private final NotificationService notifications;
    private final MailItemCodec codec = new MailItemCodec();
    private final MailInventoryLock locks;
    private final Executor io;
    private final String serverId;
    private final Supplier<MailLimits> limits;
    private final NamespacedKey receiptKey;
    private final java.nio.file.Path playerDataDirectory;
    private final Map<UUID, Long> generations = new ConcurrentHashMap<>();
    private final Map<Player, Long> connections = new java.util.IdentityHashMap<>();
    private final AtomicLong generationCounter = new AtomicLong();
    /** Mail IDs a send or claim in this JVM is working on: recovery never touches them. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private volatile CostPolicy cost = CostPolicy.FREE;
    private final ItemDeliveryService delivery;
    private final Map<Player, List<MailRecord>> mailboxCache = new java.util.IdentityHashMap<>();
    private final Set<Player> recovering = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Set<Player> recoveryBusy = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Set<Player> quarantined = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Map<Player, Integer> recoveryFailures = new java.util.IdentityHashMap<>();
    private TaskHandle recoveryTimer;
    private TaskHandle purgeTimer;
    private boolean closed;

    public MailService(PluginStart plugin, MailRepository repository, IgnoreRepository ignoreStorage, PlayerNameService names,
                       NotificationService notifications, Executor io, String serverId, Supplier<MailLimits> limits) {
        this(plugin, repository, ignoreStorage, names, notifications, io, serverId, limits, PlayerDataFiles.directory(plugin.getServer()));
    }

    MailService(PluginStart plugin, MailRepository repository, IgnoreRepository ignoreStorage, PlayerNameService names,
                NotificationService notifications, Executor io, String serverId, Supplier<MailLimits> limits,
                java.nio.file.Path playerDataDirectory) {
        this.playerDataDirectory = playerDataDirectory;
        this.plugin = plugin;
        this.repository = repository;
        this.ignoreStorage = ignoreStorage;
        this.names = names;
        this.notifications = notifications;
        this.io = io;
        this.serverId = serverId;
        this.limits = limits;
        this.receiptKey = new NamespacedKey(plugin, "mail_receipts");
        this.locks = new MailInventoryLock();
        this.delivery = new ItemDeliveryService(plugin.getScheduler().serverExecutor(), plugin.getScheduler()::isServerThread,
                plugin.getServer()::getPlayer, plugin.getServer().getPluginManager());
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(locks, plugin);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            joined(online);
        }
        // Expired text mail and old send-log rows, every 10 minutes.
        purgeTimer = plugin.getScheduler().repeatOnServer(() -> {
            long now = System.currentTimeMillis();
            long keep = Math.max(86_400_000L, limits.get().windowMillis());
            io.execute(() -> {
                try {
                    repository.purge(now, now - keep);
                } catch (SQLException e) {
                    plugin.logger().warning("Mail purge failed: " + e.getMessage());
                }
            });
            return true;
        }, 60_000L, 600_000L);
        recoveryTimer = plugin.getScheduler().repeatOnServer(() -> {
            for (Player player : List.copyOf(recovering)) {
                if (same(player) && !quarantined.contains(player) && !recoveryBusy.contains(player)) joined(player);
            }
            return true;
        }, 2_000L, 2_000L);
    }

    public void disable() {
        closed = true;
        if (recoveryTimer != null) recoveryTimer.cancel();
        if (purgeTimer != null) purgeTimer.cancel();
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(locks);
    }

    public void setCostPolicy(@Nullable CostPolicy hook) {
        cost = hook == null ? CostPolicy.FREE : hook;
    }

    public java.nio.file.Path playerDataDirectory() { return playerDataDirectory; }

    public String serverId() {
        return serverId;
    }

    public boolean busy(Player player) {
        return locks.locked(player) || recovering.contains(player) || recoveryBusy.contains(player) || quarantined.contains(player);
    }

    private boolean kitBusy(Player player) {
        return plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("kits") instanceof codes.castled.allium.module.KitsModule kits
                && kits.isEnabled() && kits.kits() != null && kits.kits().busy(player);
    }

    // -- Text mail -------------------------------------------------------------------------------

    private Money price(CommandSender sender,MailKind kind){
        if(!(sender instanceof Player))return new Money(0); // Console mail has no payer.
        try{
            Object value=plugin.getConfig().get("mail.costs."+(kind==MailKind.TEXT?"text":"gift"),"0.00");
            if(!(value instanceof String text))throw new IllegalArgumentException("mail costs require quoted decimal text");
            Money amount=Money.parse(text,false);if(amount.minor()>0&&!PaidWorkflowSupport.ready(plugin))throw new IllegalArgumentException("paid mail economy unavailable");return amount;
        }catch(RuntimeException error){send(sender,Messages.MAIL_COST_REFUSED);return null;}
    }
    private MailRepository.SendResult sendPriced(MailRecord mail,MailLimits rules,boolean bypass,Money price,java.nio.file.Path file,String baseline)throws SQLException{
        if(price.minor()==0)return repository.send(mail,rules,bypass);
        var outcome=new java.util.concurrent.atomic.AtomicReference<MailRepository.SendResult>();
        if(mail.kind()==MailKind.GIFT)PaidWorkflowSupport.prove(file,PlayerDataReceiptProof.MAIL_RECEIPTS,baseline);
        EconomyEngine.WorkflowTransition intent=c->{
            var result=repository.send(c,mail,rules,bypass);outcome.set(result);return result==MailRepository.SendResult.SAVED;
        };
        var reservations=PaidWorkflowSupport.reservations(plugin);
        String fingerprint=EconomyReservations.fingerprint(mail.id().toString(),mail.recipient().toString(),mail.sender().toString(),mail.senderName(),mail.kind().name(),mail.body(),mail.items(),Integer.toString(mail.itemCount()),mail.origin(),Long.toString(mail.createdAt()),Long.toString(mail.expiresAt()),mail.source(),mail.sourceRef());
        Mutation result=mail.kind()==MailKind.TEXT?reservations.spend(EconomyReservations.Kind.MAIL,mail.id(),mail.sender(),price,fingerprint,intent):reservations.reserve(EconomyReservations.Kind.MAIL,mail.id(),mail.sender(),price,fingerprint,intent);
        if(result instanceof Rejected)return outcome.get()!=null?outcome.get():MailRepository.SendResult.COST_REFUSED;
        PaidWorkflowSupport.committed(result);return MailRepository.SendResult.SAVED;
    }
    private boolean activate(UUID id,java.nio.file.Path file,String expected)throws SQLException{
        if(!repository.paid(id))return repository.activate(id);
        if(MailReceipts.parse(expected).stream().noneMatch(r->r instanceof MailReceipts.Sent sent&&sent.mail().equals(id)))throw new SQLException("missing matching sender receipt");
        PaidWorkflowSupport.prove(file,PlayerDataReceiptProof.MAIL_RECEIPTS,expected);
        if(PaidWorkflowSupport.reservations(plugin).operatorResolved(EconomyReservations.Kind.MAIL,id))throw new SQLException("operator-resolved workflow remains quarantined");
        return PaidWorkflowSupport.committed(PaidWorkflowSupport.reservations(plugin).capture(EconomyReservations.Kind.MAIL,id,c->{
            return repository.activate(c,id);
        }));
    }
    private boolean cancelPending(UUID id,java.nio.file.Path file,String expected)throws SQLException{
        if(!repository.paid(id))return repository.cancelPending(id);
        if(MailReceipts.parse(expected).stream().anyMatch(r->r instanceof MailReceipts.Sent sent&&sent.mail().equals(id)))throw new SQLException("cannot release a gift with a sender receipt");
        PaidWorkflowSupport.prove(file,PlayerDataReceiptProof.MAIL_RECEIPTS,expected);
        if(PaidWorkflowSupport.reservations(plugin).operatorResolved(EconomyReservations.Kind.MAIL,id))throw new SQLException("operator-resolved workflow remains quarantined");
        return PaidWorkflowSupport.committed(PaidWorkflowSupport.reservations(plugin).release(EconomyReservations.Kind.MAIL,id,c->{
            return repository.cancelPending(c,id);
        }));
    }

    public void sendText(CommandSender sender, String typedName, String body) {
        if (!senderReady(sender)) return;
        MailLimits rules = limits.get();
        String target = MessagingService.safeName(typedName);
        if (body.length() > rules.bodyMaxLength()) {
            send(sender, Messages.MAIL_TOO_LONG, "{max}", Integer.toString(rules.bodyMaxLength()));
            return;
        }
        Player senderPlayer = sender instanceof Player p ? p : null;
        UUID senderId = senderPlayer == null ? null : senderPlayer.getUniqueId();
        String senderName = senderPlayer == null ? plugin.getMessageManager().resolve(Messages.MSG_CONSOLE_NAME) : senderPlayer.getName();
        boolean muted = senderId != null && muted(senderId);
        boolean bypass = sender.hasPermission(PERMISSION_BYPASS_LIMITS);
        if (senderPlayer != null && !cost.allows(senderPlayer, MailKind.TEXT)) {
            send(sender, Messages.MAIL_COST_REFUSED);
            return;
        }
        Money price=price(sender,MailKind.TEXT);if(price==null)return;
        decide(sender, senderId, muted, target).thenAcceptAsync(decision -> {
            if (decision.outcome() != MailPolicy.Outcome.DELIVER) {
                onServer(() -> {
                    refuse(sender, decision.outcome(), target);
                });
                return;
            }
            long now = System.currentTimeMillis();
            MailRecord mail = MailRecord.text(UUID.randomUUID(), decision.recipient().uuid(), senderId, senderName, body,
                    serverId, now, rules.expiresAt(now));
            MailRepository.SendResult result;
            try {
                failIfArmed();
                result = sendPriced(mail,rules,bypass,price,null,null);
            } catch (SQLException e) {
                plugin.logger().warning("Mail to " + target + " could not be stored: " + e.getMessage());
                onServer(() -> {
                    if(e instanceof PaidWorkflowSupport.UnknownCommit unknown)send(sender,Messages.MAIL_COST_UNKNOWN,"{id}",unknown.operationId().toString());
                    else send(sender, Messages.MAIL_FAILED);
                });
                return;
            }
            onServer(() -> {
                if (result != MailRepository.SendResult.SAVED) {
                    limited(sender, result, decision.recipient().name());
                    return;
                }
                send(sender, Messages.MAIL_SENT, "{player}", decision.recipient().name());
                notifyNew(decision.recipient().uuid(), senderName, MailKind.TEXT);
            });
        }, io).exceptionally(error -> {
            onServer(() -> send(sender, Messages.MAIL_FAILED));
            return null;
        });
    }

    /** Sends an automated server notice mail to {@code recipientUuid} (bypasses cost, ignore and rate limits). */
    public void sendSystemText(UUID recipientUuid, String senderName, String body) {
        if (closed || recipientUuid == null || body == null || body.isBlank()) return;
        MailLimits rules = limits.get();
        String trimmed = body.length() > rules.bodyMaxLength() ? body.substring(0, rules.bodyMaxLength()) : body;
        String from = senderName == null || senderName.isBlank() ? "Server" : senderName;
        long now = System.currentTimeMillis();
        MailRecord mail = MailRecord.text(UUID.randomUUID(), recipientUuid, null, from, trimmed,
                serverId, now, rules.expiresAt(now));
        try {
            io.execute(() -> {
                try {
                    if (repository.send(mail, rules, true) == MailRepository.SendResult.SAVED) {
                        onServer(() -> notifyNew(recipientUuid, from, MailKind.TEXT));
                    }
                } catch (SQLException e) {
                    plugin.logger().warning("System mail to " + recipientUuid + " could not be stored: " + e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            plugin.logger().warning("Mail queue is full; system notice to " + recipientUuid + " was not queued.");
        }
    }

    // -- Gifts -------------------------------------------------------------------------------------

    /** Gifts {@code amount} (all, when below 1) of the held stack to {@code typedName}. */
    public void sendGift(Player sender, String typedName, int amount, @Nullable String note) {
        if (!senderReady(sender)) return;
        MailLimits rules = limits.get();
        String target = MessagingService.safeName(typedName);
        if (note != null && note.length() > rules.bodyMaxLength()) {
            send(sender, Messages.MAIL_TOO_LONG, "{max}", Integer.toString(rules.bodyMaxLength()));
            return;
        }
        if (locks.locked(sender) || recovering.contains(sender) || kitBusy(sender) || !emptyCursor(sender)) {
            send(sender, Messages.MAIL_BUSY);
            return;
        }
        PlayerInventory inventory = sender.getInventory();
        int slot = inventory.getHeldItemSlot();
        ItemStack held = inventory.getItem(slot);
        if (held == null || held.getType().isAir()) {
            send(sender, Messages.MAIL_GIFT_EMPTY_HAND);
            return;
        }
        int giving = amount < 1 ? held.getAmount() : Math.min(amount, held.getAmount());
        if (giving > rules.giftMaxItems()) {
            send(sender, Messages.MAIL_GIFT_TOO_MANY, "{max}", Integer.toString(rules.giftMaxItems()));
            return;
        }
        ItemStack gift = held.clone();
        gift.setAmount(giving);
        String encoded;
        try {
            encoded = codec.encode(List.of(gift));
        } catch (RuntimeException e) {
            send(sender, Messages.MAIL_FAILED);
            plugin.logger().warning("A gift from " + sender.getName() + " could not be encoded: " + e);
            return;
        }
        if (!cost.allows(sender, MailKind.GIFT)) {
            send(sender, Messages.MAIL_COST_REFUSED);
            return;
        }
        Money price=price(sender,MailKind.GIFT);if(price==null)return;
        UUID senderId = sender.getUniqueId();
        long generation = generation(senderId);
        boolean muted = muted(senderId);
        boolean bypass = sender.hasPermission(PERMISSION_BYPASS_LIMITS);
        UUID mailId = UUID.randomUUID();
        locks.lock(sender);
        if(price.minor()>0)try{sender.saveData();}catch(RuntimeException error){locks.unlock(sender);send(sender,Messages.MAIL_FAILED);return;}
        String baseline=playerReceipt(sender);java.nio.file.Path baselineFile=playerFile(sender);
        inFlight.add(mailId);
        decide(sender, senderId, muted, target).thenAcceptAsync(decision -> {
            if (decision.outcome() != MailPolicy.Outcome.DELIVER) {
                onServer(() -> {
                    finishSend(sender, mailId);
                    refuse(sender, decision.outcome(), target);
                });
                return;
            }
            MailRecord pending = MailRecord.pendingGift(mailId, decision.recipient().uuid(), senderId, sender.getName(), note,
                    encoded, 1, serverId, System.currentTimeMillis());
            MailRepository.SendResult result;
            try {
                if (FaultInjection.trip(FAULT_SEND_SLOW)) {
                    sleep(SLOW_MILLIS);
                }
                failIfArmed();
                result = sendPriced(pending,rules,bypass,price,baselineFile,baseline);
            } catch (SQLException e) {
                plugin.logger().warning("A gift to " + target + " could not be stored; nothing left " + sender.getName() + ": " + e.getMessage());
                onServer(() -> {
                    finishSend(sender, mailId);
                    deferRecovery(sender);
                    send(sender, Messages.MAIL_FAILED);
                });
                return;
            }
            onServer(() -> {
                if (result != MailRepository.SendResult.SAVED) {
                    finishSend(sender, mailId);
                    limited(sender, result, decision.recipient().name());
                    return;
                }
                takeAndActivate(sender, generation, slot, gift, mailId, decision.recipient(),price.minor()>0);
            });
        }, io).exceptionally(error -> {
            onServer(() -> {
                finishSend(sender, mailId);

                send(sender, Messages.MAIL_FAILED);
            });
            return null;
        });
    }

    /** Step 3 and 4 of a gift send, on the server thread. */
    private void takeAndActivate(Player sender, long generation, int slot, ItemStack gift, UUID mailId, NameCache.Entry recipient,boolean paid) {
        if(FaultInjection.trip("mail.send.halt-before-take"))Runtime.getRuntime().halt(77);
        java.nio.file.Path proofFile=playerFile(sender);String proofReceipt=playerReceipt(sender);
        if (!same(sender) || generation(sender.getUniqueId()) != generation || muted(sender.getUniqueId())) {
            // The sender left: their saved inventory still holds the items. Drop the PENDING gift.
            locks.unlock(sender);

            submitCritical(sender, mailId, () -> {
                try {
                    cancelPending(mailId,proofFile,proofReceipt);
                    plugin.logger().info("Gift " + mailId + " cancelled: " + sender.getName() + " left before the items were taken.");
                } catch (SQLException e) {
                    plugin.logger().warning("Gift " + mailId + " stays PENDING (cancel failed, retried at the sender's next join): " + e.getMessage());
                } finally {
                    inFlight.remove(mailId);
                }
            });
            return;
        }
        PlayerInventory inventory = sender.getInventory();
        ItemStack now = inventory.getItem(slot);
        if (inventory.getHeldItemSlot() != slot || !emptyCursor(sender)
                || now == null || !now.isSimilar(gift) || now.getAmount() < gift.getAmount()) {
            // Cannot happen while locked; refuse rather than take the wrong items.
            locks.unlock(sender);

            send(sender, Messages.MAIL_FAILED);
            submitCritical(sender, mailId, () -> {
                try {
                    cancelPending(mailId,proofFile,proofReceipt);
                } catch (SQLException e) {
                    plugin.logger().warning("Gift " + mailId + " stays PENDING: " + e.getMessage());
                } finally {
                    inFlight.remove(mailId);
                }
            });
            return;
        }
        String partialMarker="P/"+mailId;
        if(paid)addReceipt(sender,new MailReceipts.Unknown(partialMarker));
        int expectedRemaining=now.getAmount()-gift.getAmount();
        try{if (now.getAmount() == gift.getAmount()) {
            inventory.setItem(slot, null);
        } else {
            now.setAmount(now.getAmount() - gift.getAmount());
            inventory.setItem(slot, now);
        }
        }catch(RuntimeException error){
            try{sender.saveData();}catch(RuntimeException ignored){}
            inFlight.remove(mailId);quarantine(sender,"Gift removal has unknown partial effects "+mailId,error);return;
        }
        ItemStack remaining=inventory.getItem(slot);
        boolean exact=expectedRemaining==0?(remaining==null||remaining.getType().isAir()):remaining!=null&&remaining.isSimilar(gift)&&remaining.getAmount()==expectedRemaining;
        if(paid&&!exact){
            try{sender.saveData();}catch(RuntimeException ignored){}
            inFlight.remove(mailId);quarantine(sender,"Gift removal differs from its exact plan "+mailId,null);return;
        }
        if(paid)removeReceipt(sender,r->r instanceof MailReceipts.Unknown unknown&&unknown.raw().equals(partialMarker));
        addReceipt(sender, new MailReceipts.Sent(mailId));
        try {
            sender.saveData();
        } catch (RuntimeException error) {
            quarantine(sender, "Gift " + mailId + " could not save its escrow receipt", error);
            inFlight.remove(mailId);
            return;
        }
        if (FaultInjection.trip(FAULT_SEND_HALT)) {
            plugin.logger().warning("Fault " + FAULT_SEND_HALT + ": halting after taking gift " + mailId + " (simulated crash).");
            Runtime.getRuntime().halt(77);
        }
        String expectedReceipt = playerReceipt(sender);
        java.nio.file.Path playerFile = playerFile(sender);
        submitCritical(sender, mailId, () -> {
            boolean activated;
            try {
                PlayerDataReceiptProof.verify(playerFile, expectedReceipt);
                failIfArmed();
                activated = activate(mailId,playerFile,expectedReceipt);
            } catch (IOException e) {
                onServer(() -> {
                    finishSend(sender, mailId);
                    quarantine(sender, "Gift " + mailId + " has no confirmed saved escrow receipt", e);
                });
                return;
            } catch (SQLException e) {
                plugin.logger().warning("Gift " + mailId + " is safe but not active yet (retried at " + sender.getName()
                        + "'s next join): " + e.getMessage());
                onServer(() -> {
                    finishSend(sender, mailId);
                    deferRecovery(sender);
                    send(sender, Messages.MAIL_GIFT_DELAYED, "{player}", recipient.name());
                });
                return;
            }
            onServer(() -> {
                finishSend(sender, mailId);
                if (!activated) {
                    deferRecovery(sender);
                    return;
                }
                if (same(sender) && generation(sender.getUniqueId()) == generation) {
                    removeReceipt(sender, r -> r instanceof MailReceipts.Sent s && s.mail().equals(mailId));
                }
                send(sender, Messages.MAIL_GIFT_SENT, "{player}", recipient.name(), "{amount}", Integer.toString(gift.getAmount()));
                notifyNew(recipient.uuid(), sender.getName(), MailKind.GIFT);
            });
        }, true);
    }

    private void finishSend(Player sender, UUID mailId) {
        locks.unlock(sender);
        inFlight.remove(mailId);
    }

    // -- Reading, deleting ---------------------------------------------------------------------

    public void read(Player player) {
        read(player, () -> true, ignored -> {});
    }

    /** Reports whether a live viewer received the mailbox result, including an empty mailbox. */
    public void read(Player player, java.util.function.BooleanSupplier valid, java.util.function.Consumer<Boolean> outcome) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        io.execute(() -> {
            List<MailRecord> box;
            try {
                box = repository.mailbox(id, now);
            } catch (SQLException e) {
                plugin.getScheduler().runOnServer(() -> {
                    send(player, Messages.MAIL_FAILED);
                    outcome.accept(false);
                });
                return;
            }
            plugin.getScheduler().runOnServer(() -> {
                if (!same(player) || !valid.getAsBoolean()) { outcome.accept(false); return; }
                mailboxCache.put(player, List.copyOf(box));
                if (box.isEmpty()) {
                    send(player, Messages.MAIL_EMPTY);
                    outcome.accept(true);
                    return;
                }
                send(player, Messages.MAIL_HEADER, "{count}", Integer.toString(box.size()));
                List<UUID> seen = new ArrayList<>();
                for (MailRecord mail : box) {
                    line(player, mail, now);
                    if (mail.unread()) {
                        seen.add(mail.id());
                    }
                }
                outcome.accept(true);
                io.execute(() -> {
                    try {
                        repository.markRead(id, seen);
                    } catch (SQLException e) {
                        plugin.logger().warning("Could not mark mail read for " + player.getName() + ": " + e.getMessage());
                    }
                });
            });
        });
    }

    private void line(Player player, MailRecord mail, long now) {
        String age = age(now - mail.createdAt());
        String body = mail.body() == null ? "" : mail.body();
        Messages key = mail.kind() == MailKind.TEXT ? Messages.MAIL_ENTRY_TEXT
                : mail.state() == MailState.CLAIMING ? Messages.MAIL_ENTRY_CLAIMING : Messages.MAIL_ENTRY_GIFT;
        // A mail body is the sender's untrusted text: shown literally, never as formatting.
        notifications.send(player, new NotificationService.Notification(key, Map.of("{id}", mail.label(),
                "{sender}", MessagingService.safeName(mail.senderName()), "{age}", age,
                "{count}", Integer.toString(mail.itemCount())),
                UntrustedText.parse(body, UntrustedText.Allow.of(node -> false)), NotificationService.Sound.NONE));
    }

    public void delete(Player player, String idPrefix) {
        UUID id = player.getUniqueId();
        find(player, idPrefix, MailKind.TEXT, mail -> {
            if (mail.kind() == MailKind.GIFT) {
                send(player, Messages.MAIL_DELETE_GIFT, "{id}", mail.label());
                return;
            }
            io.execute(() -> {
                boolean deleted;
                try {
                    deleted = repository.deleteText(id, mail.id());
                } catch (SQLException e) {
                    onServer(() -> send(player, Messages.MAIL_FAILED));
                    return;
                }
                onServer(() -> send(player, deleted ? Messages.MAIL_DELETED : Messages.MAIL_NOT_FOUND, "{id}", mail.label()));
            });
        });
    }

    public void clear(Player player) {
        UUID id = player.getUniqueId();
        io.execute(() -> {
            int n;
            try {
                n = repository.clearRead(id);
            } catch (SQLException e) {
                onServer(() -> send(player, Messages.MAIL_FAILED));
                return;
            }
            onServer(() -> send(player, Messages.MAIL_CLEARED, "{count}", Integer.toString(n)));
        });
    }

    // -- Claiming ----------------------------------------------------------------------------------

    public void claim(Player player, String idPrefix) {
        if (!senderReady(player)) return;
        if (locks.locked(player) || recovering.contains(player) || kitBusy(player) || !emptyCursor(player)) {
            send(player, Messages.MAIL_BUSY);
            return;
        }
        if (idPrefix.equalsIgnoreCase("all")) {
            UUID id = player.getUniqueId();
            io.execute(() -> {
                try {
                    List<MailRecord> gifts = repository.mailbox(id, System.currentTimeMillis()).stream()
                            .filter(m -> m.kind() == MailKind.GIFT && m.state() == MailState.READY).toList();
                    onServer(() -> {
                        if (!same(player)) return;
                        if (gifts.isEmpty()) send(player, Messages.MAIL_NO_GIFTS);
                        else claimNext(player, gifts, 0);
                    });
                } catch (SQLException e) { onServer(() -> send(player, Messages.MAIL_FAILED)); }
            });
        } else {
            find(player, idPrefix, MailKind.GIFT, mail -> {
                if (mail.kind() != MailKind.GIFT) send(player, Messages.MAIL_NOT_A_GIFT, "{id}", mail.label());
                else claim(player, mail, () -> {});
            });
        }
    }

    private void claimNext(Player player, List<MailRecord> gifts, int index) {
        if (!same(player) || index >= gifts.size() || recovering.contains(player)) return;
        claim(player, gifts.get(index), () -> claimNext(player, gifts, index + 1));
    }

    private void claim(Player player, MailRecord mail, Runnable after) {
        if (!same(player)) return;
        if (locks.locked(player) || recovering.contains(player) || kitBusy(player) || !emptyCursor(player)) {
            send(player, Messages.MAIL_BUSY);
            return;
        }
        UUID id = player.getUniqueId();
        UUID token = UUID.randomUUID();
        long generation = generation(id);
        locks.lock(player);
        inFlight.add(mail.id());
        submitCritical(player, mail.id(), () -> {
            try {
                failIfArmed();
                if (!repository.beginClaim(mail.id(), id, token, serverId, System.currentTimeMillis())) {
                    onServer(() -> {
                        finishSend(player, mail.id());
                        send(player, Messages.MAIL_CLAIM_TAKEN, "{id}", mail.label());
                        if (same(player)) after.run();
                    });
                    return;
                }
                // The caller's mailbox snapshot may predate another partial claim. Read only our won token's row.
                MailRecord current = repository.find(mail.id()).filter(m -> token.equals(m.claimToken())).orElseThrow(
                        () -> new SQLException("won claim vanished: " + mail.id()));
                if (FaultInjection.trip(FAULT_CLAIM_SLOW)) sleep(SLOW_MILLIS);
                onServer(() -> deliverClaim(player, generation, current, token, after));
            } catch (SQLException e) {
                onServer(() -> {
                    finishSend(player, mail.id());
                    deferRecovery(player);
                    send(player, Messages.MAIL_FAILED);
                });
            }
        });
    }

    private void deliverClaim(Player player, long generation, MailRecord mail, UUID token, Runnable after) {
        if (FaultInjection.trip(FAULT_CLAIM_HALT_BEFORE)) Runtime.getRuntime().halt(77);
        if (!same(player) || generation(player.getUniqueId()) != generation) {
            release(player, mail, token, after);
            return;
        }
        List<ItemStack> items;
        try { items = codec.decode(mail.items()); }
        catch (IOException e) {
            plugin.logger().warning("Gift " + mail.id() + " cannot be claimed: " + e.getMessage());
            send(player, Messages.MAIL_UNREADABLE, "{id}", mail.label(), "{reason}", "unsupported or invalid stored item data");
            release(player, mail, token, after);
            return;
        }
        ItemDeliveryService.MailPlan plan = delivery.planMail(plugin.getServer().getConsoleSender(), player, items);
        if (plan == null || !same(player) || generation(player.getUniqueId()) != generation || !emptyCursor(player)) {
            release(player, mail, token, after);
            return;
        }
        if (plan.given() == 0) {
            send(player, Messages.MAIL_CLAIM_FULL, "{id}", mail.label());
            release(player, mail, token, () -> {});
            return;
        }
        String rest;
        try { rest = plan.remaining().isEmpty() ? null : codec.encode(plan.remaining()); }
        catch (RuntimeException error) { release(player, mail, token, after); return; }
        if (!delivery.applyMail(player, plan)) {
            release(player, mail, token, after);
            return;
        }
        addReceipt(player, new MailReceipts.Claimed(mail.id(), token, plan.delivered()));
        try { player.saveData(); }
        catch (RuntimeException error) {
            inFlight.remove(mail.id());
            quarantine(player, "Claim " + mail.id() + " could not save its delivery receipt", error);
            return;
        }
        if (FaultInjection.trip(FAULT_CLAIM_HALT_AFTER)) Runtime.getRuntime().halt(77);
        String expectedReceipt = playerReceipt(player);
        java.nio.file.Path playerFile = playerFile(player);
        submitCritical(player, mail.id(), () -> {
            try {
                PlayerDataReceiptProof.verify(playerFile, expectedReceipt);
                failIfArmed();
                boolean finished = repository.finishClaim(mail.id(), token, rest, plan.remaining().size());
                onServer(() -> {
                    finishSend(player, mail.id());
                    if (!same(player) || generation(player.getUniqueId()) != generation) return;
                    if (!finished) { deferRecovery(player); return; }
                    removeReceipt(player, r -> r instanceof MailReceipts.Claimed c && c.token().equals(token));
                    mailboxCache.remove(player);
                    int kept = plan.remaining().stream().mapToInt(ItemStack::getAmount).sum();
                    send(player, kept > 0 ? Messages.MAIL_CLAIMED_PART : Messages.MAIL_CLAIMED,
                            "{id}", mail.label(), "{given}", Integer.toString(plan.given()), "{kept}", Integer.toString(kept));
                    after.run();
                });
            } catch (IOException e) {
                onServer(() -> {
                    finishSend(player, mail.id());
                    quarantine(player, "Claim " + mail.id() + " has no confirmed saved delivery receipt", e);
                });
            } catch (SQLException e) {
                plugin.logger().warning("Claim " + mail.id() + " awaits receipt recovery: " + e.getMessage());
                onServer(() -> { finishSend(player, mail.id()); deferRecovery(player); });
            }
        }, true);
    }

    private void release(Player player, MailRecord mail, UUID token, Runnable after) {
        submitCritical(player, mail.id(), () -> {
            try {
                repository.releaseClaim(mail.id(), token);
                onServer(() -> { finishSend(player, mail.id()); if (same(player)) after.run(); });
            } catch (SQLException e) {
                onServer(() -> { finishSend(player, mail.id()); deferRecovery(player); });
            }
        });
    }

    // -- Joining: recovery and the unread count ------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinEarly(PlayerJoinEvent event) {
        long generation = generationCounter.incrementAndGet();
        generations.put(event.getPlayer().getUniqueId(), generation);
        connections.put(event.getPlayer(), generation);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        joined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Long connection = connections.remove(player);
        if (connection != null) generations.remove(player.getUniqueId(), connection);
        locks.clear(player);
        mailboxCache.remove(player);
        recovering.remove(player);
        recoveryBusy.remove(player);
        quarantined.remove(player);
        recoveryFailures.remove(player);
    }

    private void joined(Player player) {
        if (!same(player) || !recoveryBusy.add(player)) return;
        recovering.add(player);
        locks.lock(player);
        generations.computeIfAbsent(player.getUniqueId(), k -> generationCounter.incrementAndGet());
        long generation = generation(player.getUniqueId());
        connections.putIfAbsent(player, generation);
        List<MailReceipts.Entry> receipts = receipts(player);
        if (receipts.stream().anyMatch(r -> r instanceof MailReceipts.Unknown)) {
            recoveryBusy.remove(player);
            quarantine(player, "Unknown mail receipt; preserve player data for operator recovery", null);
            return;
        }
        String expectedReceipt = playerReceipt(player);
        java.nio.file.Path savedFile = playerFile(player);
        submitRecovery(player, generation, () -> {
            try {
                if (!receipts.isEmpty()) PlayerDataReceiptProof.verify(savedFile, expectedReceipt);
                List<MailRecord> pending = repository.pendingFrom(player.getUniqueId(), serverId);
                if(receipts.isEmpty())for(var row:pending)if(repository.paid(row.id()))PaidWorkflowSupport.prove(savedFile,PlayerDataReceiptProof.MAIL_RECEIPTS,expectedReceipt);
                List<MailRecord> claiming = repository.claimingFor(player.getUniqueId(), serverId);
                onServer(() -> recover(player, generation, receipts, pending, claiming));
            } catch (IOException e) {
                onServer(() -> {
                    recoveryBusy.remove(player);
                    quarantine(player, "Mail recovery has no confirmed saved receipt for " + player.getUniqueId(), e);
                });
            } catch (SQLException e) { onServer(() -> recoveryFailed(player, generation, e)); }
        });
    }

    private void recover(Player player, long generation, List<MailReceipts.Entry> receipts,
                         List<MailRecord> pending, List<MailRecord> claiming) {
        if (!same(player) || generation(player.getUniqueId()) != generation) return;
        if (pending.stream().anyMatch(m -> inFlight.contains(m.id())) || claiming.stream().anyMatch(m -> inFlight.contains(m.id()))
                || receipts.stream().anyMatch(r -> inFlight.contains(r instanceof MailReceipts.Sent sent ? sent.mail()
                : ((MailReceipts.Claimed) r).mail()))) {
            recoveryBusy.remove(player);
            locks.unlock(player);
            return; // A previous connection's callback must settle before we interpret an absent receipt.
        }
        java.nio.file.Path recoveryFile=playerFile(player);
        String recoverySnapshot=MailReceipts.format(receipts);
        List<RecoveryStep> steps = new ArrayList<>();
        try {
            for (MailReceipts.Entry entry : receipts) {
                if (entry instanceof MailReceipts.Sent sent) {
                    steps.add(() -> { if (!activate(sent.mail(),recoveryFile,recoverySnapshot)) throw new SQLException("missing activation proof " + sent.mail()); });
                } else if (entry instanceof MailReceipts.Claimed receipt) {
                    MailRecord row = claiming.stream().filter(m -> m.id().equals(receipt.mail()) && receipt.token().equals(m.claimToken()))
                            .findFirst().orElse(null);
                    if (row == null) {
                        steps.add(() -> { if (!repository.claimAcknowledged(receipt.mail(), receipt.token()))
                            throw new SQLException("missing claim acknowledgement " + receipt.token()); });
                    } else {
                        List<ItemStack> stored = codec.decode(row.items());
                        if (stored.size() != receipt.delivered().size()) throw new IOException("receipt stack count mismatch");
                        List<ItemStack> remaining = new ArrayList<>();
                        for (int i = 0; i < stored.size(); i++) {
                            int given = receipt.delivered().get(i);
                            if (given < 0 || given > stored.get(i).getAmount()) throw new IOException("invalid receipt quantity");
                            int left = stored.get(i).getAmount() - given;
                            if (left > 0) { ItemStack keep = stored.get(i).clone(); keep.setAmount(left); remaining.add(keep); }
                        }
                        String rest = remaining.isEmpty() ? null : codec.encode(remaining);
                        steps.add(() -> { if (!repository.finishClaim(receipt.mail(), receipt.token(), rest, remaining.size()))
                            throw new SQLException("claim ownership changed " + receipt.token()); });
                    }
                }
            }
            for (MailRecord row : pending) {
                if (receipts.stream().noneMatch(r -> r instanceof MailReceipts.Sent sent && sent.mail().equals(row.id())))
                    steps.add(() -> cancelPending(row.id(),recoveryFile,recoverySnapshot));
            }
            for (MailRecord row : claiming) {
                if (row.claimToken() != null && receipts.stream().noneMatch(r -> r instanceof MailReceipts.Claimed c
                        && c.mail().equals(row.id()) && c.token().equals(row.claimToken())))
                    steps.add(() -> repository.releaseClaim(row.id(), row.claimToken()));
            }
        } catch (IOException | RuntimeException error) { recoveryFailed(player, generation, error); return; }
        submitRecovery(player, generation, () -> {
            try {
                for (RecoveryStep step : steps) step.run();
                onServer(() -> {
                    if (!same(player) || generation(player.getUniqueId()) != generation) return;
                    removeReceipt(player, receipts::contains);
                    player.saveData();
                    recovering.remove(player);
                    recoveryBusy.remove(player);
                    recoveryFailures.remove(player);
                    locks.unlock(player);
                    unreadSummary(player);
                });
            } catch (SQLException error) { onServer(() -> recoveryFailed(player, generation, error)); }
        });
    }

    @FunctionalInterface private interface RecoveryStep { void run() throws SQLException; }

    private void recoveryFailed(Player player, long generation, Exception error) {
        if (!same(player) || generation(player.getUniqueId()) != generation) return;
        recoveryBusy.remove(player);
        locks.unlock(player);
        int attempts = recoveryFailures.merge(player, 1, Integer::sum);
        if (attempts == 1 || attempts == 5) plugin.logger().warning("Mail recovery postponed for " + player.getUniqueId() + ": " + error.getMessage());
        if (attempts >= 5) {
            // No interpretation of missing database rows while storage is unavailable. Rejoin retries.
            recovering.remove(player);
            player.kickPlayer("Mail recovery is temporarily unavailable; please try again shortly.");
        }
    }

    private void deferRecovery(Player player) {
        if (same(player)) recovering.add(player);
    }

    private void quarantine(Player player, String reason, @Nullable Exception error) {
        plugin.logger().severe(reason + (error == null ? "" : ": " + error));
        if (same(player)) {
            // Kicks are cancellable. Preserve the lock even when another plugin keeps the connection alive.
            locks.lock(player);
            if (quarantined.add(player)) player.kickPlayer("Your inventory needs mail recovery; please reconnect shortly.");
        }
    }

    private void unreadSummary(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        io.execute(() -> {
            int unread;
            try {
                unread = repository.unread(id, now);
            } catch (SQLException e) {
                return;
            }
            if (unread > 0 && plugin.getConfig().getBoolean("mail.notify.on-join", true)) {
                onServer(() -> {
                    if (player.isOnline()) {
                        notifications.send(player, new NotificationService.Notification(Messages.MAIL_UNREAD,
                                Map.of("{count}", Integer.toString(unread)), null, NotificationService.Sound.MESSAGE));
                    }
                });
            }
        });
    }

    /** New mail for a recipient online on this server: one line, at most one sound. */
    private void notifyNew(UUID recipient, String senderName, MailKind kind) {
        Player online = plugin.getServer().getPlayer(recipient);
        if (online == null || !plugin.getConfig().getBoolean("mail.notify.new-mail", true)) {
            return;
        }
        notifications.send(online, new NotificationService.Notification(kind == MailKind.GIFT ? Messages.MAIL_NEW_GIFT : Messages.MAIL_NEW,
                Map.of("{sender}", MessagingService.safeName(senderName)), null, NotificationService.Sound.MESSAGE));
    }

    // -- Shared steps --------------------------------------------------------------------------

    private record Decision(MailPolicy.Outcome outcome, @Nullable NameCache.Entry recipient) {
    }

    /** Local pending changes may refuse a send; shared persisted values must permit it too. */
    private CompletableFuture<Decision> decide(CommandSender sender, @Nullable UUID senderId, boolean wasMuted, String target) {
        CompletableFuture<Decision> result = new CompletableFuture<>();
        boolean bypassIgnore = sender.hasPermission(MailPolicy.BYPASS_IGNORE);
        boolean bypassToggle = sender.hasPermission(MailPolicy.BYPASS_TOGGLE);
        java.util.function.Predicate<String> permissions = node -> node.equals(MailPolicy.BYPASS_IGNORE) ? bypassIgnore : bypassToggle;
        CompletableFuture<Optional<NameCache.Entry>> resolution;
        try { resolution = names.resolve(target); }
        catch (RuntimeException error) { return CompletableFuture.completedFuture(new Decision(MailPolicy.Outcome.UNAVAILABLE, null)); }
        resolution.whenComplete((found, error) -> onServer(() -> {
            NameCache.Entry recipient = error == null && found != null ? found.orElse(null) : null;
            if (error != null) { result.complete(new Decision(MailPolicy.Outcome.UNAVAILABLE, null)); return; }
            if (recipient == null || recipient.uuid().equals(senderId)) {
                result.complete(new Decision(MailPolicy.decide(senderId, permissions, wasMuted,
                        recipient == null ? null : recipient.uuid(), MailPolicy.Ignore.NO), recipient));
                return;
            }
            PreferencesModule preferences = preferences();
            if (preferences == null) { result.complete(new Decision(MailPolicy.Outcome.UNAVAILABLE, recipient)); return; }
            MailPolicy.Outcome local = localPolicy(senderId, permissions, wasMuted, recipient.uuid());
            if (local != MailPolicy.Outcome.DELIVER) { result.complete(new Decision(local, recipient)); return; }
            var defaults = preferences.defaults();
            try { io.execute(() -> {
                try {
                    MailPolicy.Ignore ignore = senderId != null && ignoreStorage.load(recipient.uuid()).contains(senderId)
                            ? MailPolicy.Ignore.YES : MailPolicy.Ignore.NO;
                    var stored = preferences.repository().load(recipient.uuid())
                            .orElse(codes.castled.allium.preference.StoredPreferences.empty(recipient.uuid()));
                    MailPolicy.Acceptance storedAcceptance = stored.effective(defaults).acceptMessages()
                            ? MailPolicy.Acceptance.ACCEPTS : MailPolicy.Acceptance.REFUSES;
                    onServer(() -> {
                        MailPolicy.Outcome current = localPolicy(senderId, permissions, wasMuted, recipient.uuid());
                        result.complete(new Decision(current == MailPolicy.Outcome.DELIVER
                                ? MailPolicy.decide(senderId, permissions, false, recipient.uuid(), ignore, storedAcceptance)
                                : current, recipient));
                    });
                } catch (SQLException | RuntimeException failure) {
                    result.complete(new Decision(MailPolicy.Outcome.UNAVAILABLE, recipient));
                }
            }); } catch (java.util.concurrent.RejectedExecutionException rejected) {
                result.complete(new Decision(MailPolicy.Outcome.UNAVAILABLE, recipient));
            }
        }));
        return result;
    }

    private MailPolicy.Outcome localPolicy(@Nullable UUID sender, java.util.function.Predicate<String> permission,
                                          boolean wasMuted, UUID recipient) {
        Player online = plugin.getServer().getPlayer(recipient);
        MailPolicy.Acceptance acceptance = online == null ? MailPolicy.Acceptance.ACCEPTS : acceptance(recipient);
        IgnoreService ignores = ignoreService();
        MailPolicy.Ignore ignore = sender == null || online == null || ignores == null ? MailPolicy.Ignore.NO
                : switch (ignores.ignores(recipient, sender)) {
                    case YES -> MailPolicy.Ignore.YES;
                    case NO -> MailPolicy.Ignore.NO;
                    case UNKNOWN -> MailPolicy.Ignore.UNKNOWN;
                };
        return MailPolicy.decide(sender, permission, wasMuted || sender != null && muted(sender), recipient, ignore, acceptance);
    }

    private MailPolicy.Acceptance acceptance(UUID player) {
        PreferencesModule module = preferences();
        if (module == null) return MailPolicy.Acceptance.UNAVAILABLE;
        return switch (module.acceptance(player, PreferenceKey.ACCEPT_MESSAGES)) {
            case ACCEPTS -> MailPolicy.Acceptance.ACCEPTS;
            case REFUSES -> MailPolicy.Acceptance.REFUSES;
            case UNAVAILABLE -> MailPolicy.Acceptance.UNAVAILABLE;
        };
    }

    @Nullable
    private IgnoreService ignoreService() {
        return plugin.getModuleManager().getModule("messaging") instanceof MessagingModule m && m.isEnabled() ? m.ignores() : null;
    }

    private boolean muted(UUID player) {
        return plugin.getModuleManager().getModule("messaging") instanceof MessagingModule m && m.isEnabled()
                && m.messaging() != null && m.messaging().muted(player);
    }

    private void refuse(CommandSender sender, MailPolicy.Outcome outcome, String target) {
        switch (outcome) {
            case SENDER_MUTED -> {
                if ((!(sender instanceof Player p) || same(p))
                        && plugin.getModuleManager().getModule("messaging") instanceof MessagingModule m
                        && m.isEnabled() && m.messaging() != null) {
                    m.messaging().sendMutedNotice(sender);
                } else {
                    send(sender, Messages.MSG_MUTED);
                }
            }
            case NOT_FOUND -> send(sender, Messages.MAIL_UNKNOWN_PLAYER, "{player}", target);
            case SELF -> send(sender, Messages.MAIL_SELF);
            case CANNOT_RECEIVE -> send(sender, Messages.MAIL_CANNOT_RECEIVE, "{player}", target);
            case UNAVAILABLE -> send(sender, Messages.MAIL_UNAVAILABLE, "{player}", target);
            case DELIVER -> {
            }
        }
    }

    private void limited(CommandSender sender, MailRepository.SendResult result, String target) {
        MailLimits rules = limits.get();
        if(result==MailRepository.SendResult.COST_REFUSED){send(sender,Messages.MAIL_COST_REFUSED);}
        else if (result == MailRepository.SendResult.MAILBOX_FULL) {
            send(sender, Messages.MAIL_MAILBOX_FULL, "{player}", target);
        } else {
            send(sender, Messages.MAIL_RATE_LIMITED, "{sends}", Integer.toString(rules.sendsPerWindow()),
                    "{seconds}", Long.toString(rules.windowMillis() / 1000L));
        }
    }

    /**
     * Finds one mail by its mailbox number ({@code 1} or {@code #1}; text and gifts share one
     * sequence) or, as before numbers existed, by a unique prefix of its ID. The caller reports a
     * number of the wrong kind ("not a gift", "gifts cannot be deleted").
     */
    private void find(Player player, String typed, MailKind kind, java.util.function.Consumer<MailRecord> found) {
        String token = typed.toLowerCase(Locale.ROOT);
        boolean numbered = token.matches("#?[0-9]{1,4}");
        if (!numbered && !token.matches("[0-9a-f-]{4,36}")) {
            send(player, Messages.MAIL_NOT_FOUND, "{id}", MessagingService.safeName(typed));
            return;
        }
        int number = numbered ? Integer.parseInt(token.startsWith("#") ? token.substring(1) : token) : 0;
        String prefix = token;
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        io.execute(() -> {
            List<MailRecord> matches;
            try {
                matches = repository.mailbox(id, now).stream()
                        .filter(m -> numbered ? m.number() == number : m.id().toString().startsWith(prefix))
                        .toList();
            } catch (SQLException e) {
                onServer(() -> send(player, Messages.MAIL_FAILED));
                return;
            }
            onServer(() -> {
                if (!same(player)) return;
                if (matches.size() == 1) {
                    found.accept(matches.get(0));
                } else {
                    send(player, Messages.MAIL_NOT_FOUND, "{id}", MessagingService.safeName(typed));
                }
            });
        });
    }

    private String playerReceipt(Player player) {
        return player.getPersistentDataContainer().get(receiptKey, PersistentDataType.STRING);
    }

    private java.nio.file.Path playerFile(Player player) {
        return playerDataDirectory.resolve(player.getUniqueId() + ".dat");
    }

    private void submitCritical(Player player, UUID mailId, Runnable task) {
        submitCritical(player, mailId, task, false);
    }

    private void submitCritical(Player player, UUID mailId, Runnable task, boolean pendingProof) {
        try { io.execute(() -> {
            try { task.run(); }
            catch (RuntimeException error) { onServer(() -> rejectedCritical(player, mailId, pendingProof, error)); }
        }); }
        catch (java.util.concurrent.RejectedExecutionException error) { rejectedCritical(player, mailId, pendingProof, error); }
    }

    private void rejectedCritical(Player player, UUID mailId, boolean pendingProof, RuntimeException error) {
        inFlight.remove(mailId);
        if (pendingProof) {
            // A void save may have failed. Do not expose newly delivered items or run same-session SQL recovery.
            quarantine(player, "Mail operation " + mailId + " stopped before receipt verification", error);
        } else {
            locks.unlock(player);
            deferRecovery(player);
            plugin.logger().warning("Mail operation " + mailId + " awaits reconciliation: " + error);
        }
    }

    private void submitRecovery(Player player, long generation, Runnable task) {
        try { io.execute(() -> {
            try { task.run(); }
            catch (RuntimeException error) { onServer(() -> recoveryFailed(player, generation, error)); }
        }); }
        catch (java.util.concurrent.RejectedExecutionException error) { recoveryFailed(player, generation, error); }
    }

    private List<MailReceipts.Entry> receipts(Player player) {
        String raw = player.getPersistentDataContainer().get(receiptKey, PersistentDataType.STRING);
        return MailReceipts.parse(raw);
    }

    private void addReceipt(Player player, MailReceipts.Entry entry) {
        List<MailReceipts.Entry> all = new ArrayList<>(receipts(player));
        all.add(entry);
        player.getPersistentDataContainer().set(receiptKey, PersistentDataType.STRING, MailReceipts.format(all));
    }

    private void removeReceipt(Player player, java.util.function.Predicate<MailReceipts.Entry> which) {
        if (!same(player)) return;
        List<MailReceipts.Entry> all = new ArrayList<>(receipts(player));
        if (all.removeIf(which)) {
            if (all.isEmpty()) {
                player.getPersistentDataContainer().remove(receiptKey);
            } else {
                player.getPersistentDataContainer().set(receiptKey, PersistentDataType.STRING, MailReceipts.format(all));
            }
        }
    }

    /** Unread mail and unclaimed gifts per online player, for placeholders; read off the server thread. */
    public record Counts(int unread, int gifts) {
        public static final Counts NONE = new Counts(0, 0);
    }

    private final Map<UUID, Counts> counts = new java.util.concurrent.ConcurrentHashMap<>();

    public Counts counts(UUID player) {
        return counts.getOrDefault(player, Counts.NONE);
    }

    /** Recounts the given players' mailboxes on the mail worker, and forgets everyone else. */
    public void refreshCounts(java.util.Collection<UUID> online) {
        List<UUID> ids = List.copyOf(online);
        counts.keySet().retainAll(ids);
        long now = System.currentTimeMillis();
        try {
            io.execute(() -> {
                for (UUID id : ids) {
                    try {
                        List<MailRecord> box = repository.mailbox(id, now);
                        int unread = (int) box.stream().filter(MailRecord::unread).count();
                        int gifts = (int) box.stream().filter(m -> m.kind() == MailKind.GIFT && m.state() == MailState.READY).count();
                        counts.put(id, new Counts(unread, gifts));
                    } catch (SQLException e) {
                        return;
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            // the next refresh tries again
        }
    }

    public List<String> completeMailbox(Player player, boolean claimableOnly) {
        return mailboxCache.getOrDefault(player, List.of()).stream()
                .filter(m -> claimableOnly ? m.kind() == MailKind.GIFT && m.state() == MailState.READY : m.kind() == MailKind.TEXT)
                .limit(100).map(MailRecord::label).toList();
    }

    private boolean same(Player player) {
        return !closed && player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }

    private boolean emptyCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        return cursor == null || cursor.getType().isAir() || cursor.getAmount() <= 0;
    }

    private boolean senderReady(CommandSender sender) {
        if (sender instanceof Player player) {
            if (!same(player)) return false;
            PreferencesModule prefs = preferences();
            if (prefs != null && prefs.effective(player.getUniqueId()).isEmpty()) {
                send(player, Messages.MAIL_UNAVAILABLE, "{player}", player.getName());
                return false;
            }
        }
        return true;
    }

    @Nullable private PreferencesModule preferences() {
        return plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule m && m.isEnabled() ? m : null;
    }

    private long generation(UUID player) {
        return generations.getOrDefault(player, -1L);
    }

    private void failIfArmed() throws SQLException {
        if (FaultInjection.trip(FAULT_WRITE_FAIL)) {
            throw new SQLException("fault " + FAULT_WRITE_FAIL);
        }
    }

    private void onServer(Runnable task) {
        if (!closed) plugin.getScheduler().runOnServer(() -> { if (!closed) task.run(); });
    }

    private void send(CommandSender to, Messages message, String... replacements) {
        if (!(to instanceof Player p) || same(p)) plugin.getMessageManager().send(to, message, replacements);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String age(long millis) {
        long minutes = Math.max(0L, millis) / 60_000L;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + "h ago";
        }
        return (hours / 24) + "d ago";
    }
}
