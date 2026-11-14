package codes.castled.allium.kit;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.FaultInjection;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.library.ItemBuildException;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.library.SkullProfiles;
import codes.castled.allium.item.spec.GiveSpec;
import codes.castled.allium.item.spec.ItemSpecException;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.mail.MailInventoryLock;
import codes.castled.allium.mail.MailService;
import codes.castled.allium.mail.PlayerDataFiles;
import codes.castled.allium.mail.PlayerDataReceiptProof;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.MailModule;
import codes.castled.allium.scheduler.TaskHandle;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Kits (S16): free kit claiming, cooldowns, one-time kits, {@code /createkit} and {@code /delkit},
 * with durable exactly-once delivery on SQLite and MySQL.
 *
 * <h2>Giving a kit to another player ({@code /kit <name> <player>})</h2>
 * <ul>
 *   <li><b>Permissions:</b> A player sender must hold {@code allium.kit}, {@code allium.kit.others},
 *       <b>and</b> access to the kit ({@code allium.kits.<name>} or {@code allium.kits.*}). The
 *       console requires no per-kit permission unless the kit's permission node is blocked. The
 *       target player does not need {@code allium.kits.<name>}.</li>
 *   <li><b>Cooldown and usage:</b> Giving a kit to another player is a staff/console grant that
 *       bypasses cooldowns ({@code bypassCooldown = true}). Usage ({@code claim_count},
 *       {@code last_claimed_at}) and {@code one-time} eligibility/consumption apply to the
 *       <b>target</b> player ({@code target.getUniqueId()}), never the sender.</li>
 * </ul>
 */
public final class KitService implements Listener {

    public static final String FAULT_CLAIM_SLOW = "kit.claim.slow";
    public static final String FAULT_CLAIM_HALT_BEFORE = "kit.claim.halt-before-deliver";
    public static final String FAULT_CLAIM_HALT_AFTER = "kit.claim.halt-after-deliver";
    public static final String FAULT_WRITE_FAIL = "kit.write-fail";
    static final long SLOW_MILLIS = 4_000L;

    public static final String PERMISSION_KIT = "allium.kit";
    public static final String PERMISSION_KITS = "allium.kits";
    public static final String PERMISSION_KIT_OTHERS = "allium.kit.others";
    public static final String PERMISSION_CREATEKIT = "allium.createkit";
    public static final String PERMISSION_DELKIT = "allium.delkit";
    public static final String PERMISSION_GIVE_PRIVILEGED = "allium.give.privileged";
    public static final String PERMISSION_BYPASS_COOLDOWN = "allium.kit.bypass.cooldown";
    public static final String PERMISSION_PREFIX = "allium.kits.";
    public static final String PERMISSION_WILDCARD = "allium.kits.*";

    private final PluginStart plugin;
    private final KitRepository repository;
    private final KitDefinitionLoader loader;
    private final Supplier<ItemSpecParser> parser;
    private final Supplier<ItemLibrary> library;
    private final Supplier<ItemDeliveryService> delivery;
    private final Executor io;
    private final String serverId;
    private final NamespacedKey receiptKey;
    private final Path playerDataDirectory;
    private final MailInventoryLock locks = new MailInventoryLock();

    private volatile Map<String, KitDefinition> definitions = Map.of();
    private final Set<String> blockedPermissions = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> generations = new ConcurrentHashMap<>();
    private final Map<Player, Long> connections = new IdentityHashMap<>();
    private final AtomicLong generationCounter = new AtomicLong();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<Player> recovering = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Player> recoveryBusy = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Player> quarantined = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Player, Integer> recoveryFailures = new IdentityHashMap<>();
    private TaskHandle recoveryTimer;
    private boolean closed;

    public KitService(PluginStart plugin, KitRepository repository, KitDefinitionLoader loader,
                      Supplier<ItemSpecParser> parser, Supplier<ItemLibrary> library,
                      Supplier<ItemDeliveryService> delivery, Executor io, String serverId) {
        this(plugin, repository, loader, parser, library, delivery, io, serverId,
                PlayerDataFiles.directory(plugin.getServer()));
    }

    public KitService(PluginStart plugin, KitRepository repository, KitDefinitionLoader loader,
                      Supplier<ItemSpecParser> parser, Supplier<ItemLibrary> library,
                      Supplier<ItemDeliveryService> delivery, Executor io, String serverId,
                      Path playerDataDirectory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.library = Objects.requireNonNull(library, "library");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.io = Objects.requireNonNull(io, "io");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.playerDataDirectory = Objects.requireNonNull(playerDataDirectory, "playerDataDirectory");
        this.receiptKey = new NamespacedKey(plugin, "kit_receipts");
    }

    public String serverId() {
        return serverId;
    }

    public void enable() {
        try {
            loader.installDefaults();
        } catch (IOException e) {
            plugin.logger().warning("Could not install default kit definitions: " + e.getMessage());
        }
        reloadDefinitions();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(locks, plugin);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            joined(online);
        }
        recoveryTimer = plugin.getScheduler().repeatOnServer(() -> {
            for (Player player : List.copyOf(recovering)) {
                if (same(player) && !quarantined.contains(player) && !recoveryBusy.contains(player)) {
                    joined(player);
                }
            }
            return true;
        }, 2_000L, 2_000L);
    }

    public void disable() {
        closed = true;
        if (recoveryTimer != null) {
            recoveryTimer.cancel();
        }
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(locks);
    }

    public boolean reloadDefinitions() {
        ensureWildcardPermission();
        KitDefinitionLoader.Loaded loaded = loader.load(parser.get());
        for (KitDefinitionLoader.Rejected rejected : loaded.rejected()) {
            for (String diag : rejected.diagnostics()) {
                plugin.logger().warning("Rejected kit definition " + rejected.file() + " (" + diag + ").");
            }
        }
        Map<String, KitDefinition> active = new LinkedHashMap<>();
        Set<String> seenNames = new HashSet<>();
        for (KitDefinition def : loaded.definitions().values()) {
            seenNames.add(def.name());
            ensurePermission(def.name());
            if (def.enabled()) {
                active.put(def.name(), def);
            }
        }
        blockedPermissions.retainAll(seenNames);
        definitions = Map.copyOf(active);
        return loaded.rejected().isEmpty();
    }

    public Map<String, KitDefinition> definitions() {
        return definitions;
    }

    public boolean ready(Player player) {
        return same(player) && !recovering.contains(player) && !recoveryBusy.contains(player) && !quarantined.contains(player);
    }

    public List<String> completeKits(CommandSender sender, boolean allAdminKits, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        if (!lower.isEmpty() && !KitNames.isValid(lower)) {
            return List.of();
        }
        if (allAdminKits) {
            if (!sender.hasPermission(PERMISSION_DELKIT) && !sender.hasPermission(PERMISSION_CREATEKIT)) {
                return List.of();
            }
            return definitions.keySet().stream()
                    .filter(KitNames::isValid)
                    .filter(name -> name.startsWith(lower))
                    .sorted()
                    .limit(50)
                    .toList();
        }
        if (!sender.hasPermission(PERMISSION_KIT) && !sender.hasPermission(PERMISSION_KITS)) {
            return List.of();
        }
        return definitions.values().stream()
                .filter(def -> KitNames.isValid(def.name()) && !def.paid() && hasKitAccess(sender, def.name()))
                .map(KitDefinition::name)
                .filter(name -> name.startsWith(lower))
                .sorted()
                .limit(50)
                .toList();
    }

    public void listKits(CommandSender sender) {
        List<String> available = definitions.values().stream()
                .filter(def -> KitNames.isValid(def.name()) && !def.paid() && hasKitAccess(sender, def.name()))
                .map(KitDefinition::name)
                .sorted()
                .toList();
        if (available.isEmpty()) {
            send(sender, Messages.KIT_NONE);
            return;
        }
        send(sender, Messages.KIT_LIST, "{kits}", String.join(", ", available));
    }

    public void createKit(Player creator, String rawName, long cooldownSeconds, boolean oneTime) {
        if (!same(creator)) return;
        String name;
        try {
            name = KitNames.validate(rawName);
        } catch (IllegalArgumentException e) {
            send(creator, Messages.KIT_NAME_INVALID);
            return;
        }
        if (cooldownSeconds < 0L || cooldownSeconds > KitDefinition.MAX_COOLDOWN_SECONDS) {
            send(creator, Messages.NUMBER_INVALID, "{input}", Long.toString(cooldownSeconds));
            return;
        }
        if (definitions.containsKey(name) || loader.exists(name)) {
            send(creator, Messages.KIT_ALREADY_EXISTS, "{name}", name);
            return;
        }
        boolean canCapturePrivileged = creator.hasPermission(PERMISSION_GIVE_PRIVILEGED);
        KitItemCapture.CaptureOutcome captured = KitItemCapture.captureInventory(
                plugin.getItemService(),
                creator.getInventory().getStorageContents(),
                canCapturePrivileged
        );
        if (captured instanceof KitItemCapture.CaptureOutcome.PrivilegedDenied denied) {
            send(creator, Messages.KIT_CAPTURE_PRIVILEGED,
                    "{slot}", Integer.toString(denied.slot()),
                    "{item}", denied.item(),
                    "{behavior}", denied.behavior(),
                    "{permission}", PERMISSION_GIVE_PRIVILEGED);
            return;
        }
        if (captured instanceof KitItemCapture.CaptureOutcome.Unsupported unsupported) {
            send(creator, Messages.KIT_CAPTURE_UNSUPPORTED,
                    "{slot}", Integer.toString(unsupported.slot()),
                    "{reason}", unsupported.reason());
            return;
        }
        List<String> specs = ((KitItemCapture.CaptureOutcome.Captured) captured).specs();
        if (specs.isEmpty()) {
            send(creator, Messages.KIT_CREATE_EMPTY);
            return;
        }
        // Validate every captured spec through ItemSpecParser before writing the file
        ItemSpecParser specParser = parser.get();
        for (String specText : specs) {
            try {
                specParser.parse(specText);
            } catch (ItemSpecException e) {
                send(creator, Messages.KIT_INVALID, "{name}", name, "{reason}", e.getMessage());
                return;
            }
        }
        KitDefinition definition = new KitDefinition(
                name, true, cooldownSeconds, oneTime, KitDefinition.ZERO_COST, specs,
                KitDefinitionLoader.DIRECTORY + "/" + name + ".yml");
        try {
            loader.save(definition);
        } catch (IOException e) {
            plugin.logger().warning("Could not save kit " + name + ": " + e.getMessage());
            send(creator, Messages.KIT_FAILED);
            return;
        }
        ensurePermission(name);
        Map<String, KitDefinition> next = new LinkedHashMap<>(definitions);
        next.put(name, definition);
        definitions = Map.copyOf(next);
        send(creator, Messages.KIT_CREATED, "{name}", name, "{items}", Integer.toString(specs.size()));
    }

    public void deleteKit(CommandSender actor, String rawName) {
        String name;
        try {
            name = KitNames.validate(rawName);
        } catch (IllegalArgumentException e) {
            send(actor, Messages.KIT_NAME_INVALID);
            return;
        }
        boolean existed;
        try {
            existed = loader.delete(name);
        } catch (IOException e) {
            plugin.logger().warning("Could not delete kit " + name + ": " + e.getMessage());
            send(actor, Messages.KIT_FAILED);
            return;
        }
        Map<String, KitDefinition> next = new LinkedHashMap<>(definitions);
        KitDefinition removed = next.remove(name);
        definitions = Map.copyOf(next);
        if (!existed && removed == null) {
            send(actor, Messages.KIT_NOT_FOUND, "{name}", name);
            return;
        }
        send(actor, Messages.KIT_DELETED, "{name}", name);
    }

    public void claim(CommandSender actor, Player target, String rawName) {
        if (!same(target)) {
            send(actor, Messages.PLAYER_NOT_FOUND, "{player}", target.getName());
            return;
        }
        boolean other = !(actor instanceof Player p && p.getUniqueId().equals(target.getUniqueId()));
        if (other && !actor.hasPermission(PERMISSION_KIT_OTHERS)) {
            send(actor, Messages.NO_PERMISSION);
            return;
        }
        String name;
        try {
            name = KitNames.validate(rawName);
        } catch (IllegalArgumentException e) {
            send(actor, Messages.KIT_NOT_FOUND, "{name}", rawName);
            return;
        }
        KitDefinition def = definitions.get(name);
        if (def == null || !def.enabled()) {
            send(actor, Messages.KIT_NOT_FOUND, "{name}", name);
            return;
        }
        if (blockedPermissions.contains(name)) {
            send(actor, Messages.KIT_NO_PERMISSION, "{name}", name);
            return;
        }
        if (!(actor instanceof ConsoleCommandSender) && !hasKitAccess(actor, name)) {
            send(actor, Messages.KIT_NO_PERMISSION, "{name}", name);
            return;
        }
        if (def.paid()) {
            send(actor, Messages.KIT_PAID_DENIED, "{name}", name);
            return;
        }
        ItemLibrary itemLibrary = library.get();
        ItemDeliveryService deliveryService = delivery.get();
        if (itemLibrary == null || deliveryService == null) {
            send(actor, Messages.KIT_FAILED);
            return;
        }
        if (busyOrLocked(target) || !emptyCursor(target)) {
            send(actor, Messages.KIT_BUSY);
            return;
        }

        // Build all items from the kit definition through ItemSpecParser + ItemLibrary.
        // Kits are server-authored definitions, so privileged items in a kit mint fresh signed instances.
        ItemSpecParser specParser = parser.get();
        List<ItemLibrary.PreparedItem> preparedItems = new ArrayList<>();
        List<CompletableFuture<SkullProfiles.Resolved>> pendingSkulls = new ArrayList<>();
        try {
            for (String entry : def.items()) {
                GiveSpec spec = specParser.parse(entry);
                ItemLibrary.Prepared built = itemLibrary.build(spec, true);
                for (ItemLibrary.PreparedItem item : built.items()) {
                    preparedItems.add(item);
                    if (item.skull() != null && !item.skull().isDone()) {
                        pendingSkulls.add(item.skull());
                    }
                }
            }
        } catch (ItemSpecException | ItemBuildException e) {
            plugin.logger().warning("Kit '" + name + "' could not build its items: " + e.getMessage());
            send(actor, Messages.KIT_INVALID, "{name}", name, "{reason}", e.getMessage());
            return;
        }

        if (pendingSkulls.isEmpty()) {
            startDurableClaim(actor, target, other, def, materializeStacks(preparedItems));
        } else {
            CompletableFuture.allOf(pendingSkulls.toArray(CompletableFuture[]::new))
                    .whenCompleteAsync((ignored, error) -> {
                        if (!same(target)) return;
                        if (error != null) {
                            send(actor, Messages.KIT_FAILED);
                            return;
                        }
                        startDurableClaim(actor, target, other, def, materializeStacks(preparedItems));
                    }, plugin.getScheduler().serverExecutor());
        }
    }

    private List<ItemStack> materializeStacks(List<ItemLibrary.PreparedItem> preparedItems) {
        List<ItemStack> stacks = new ArrayList<>(preparedItems.size());
        for (ItemLibrary.PreparedItem item : preparedItems) {
            ItemStack stack = item.stack().clone();
            if (item.skull() != null) {
                SkullProfiles.Resolved resolved = item.skull().join();
                if (stack.getItemMeta() instanceof SkullMeta skull && resolved.profile() != null) {
                    skull.setOwnerProfile(resolved.profile());
                    stack.setItemMeta(skull);
                }
            }
            stacks.add(stack);
        }
        return stacks;
    }

    private void startDurableClaim(CommandSender actor, Player target, boolean other,
                                   KitDefinition def, List<ItemStack> stacks) {
        if (!same(target)) {
            if (other) send(actor, Messages.PLAYER_NOT_FOUND, "{player}", target.getName());
            return;
        }
        if (busyOrLocked(target) || !emptyCursor(target)) {
            send(actor, Messages.KIT_BUSY);
            return;
        }
        UUID playerId = target.getUniqueId();
        long generation = generation(playerId);
        UUID deliveryId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        boolean bypassCooldown = other || actor.hasPermission(PERMISSION_BYPASS_COOLDOWN);
        int totalItems = stacks.stream().mapToInt(ItemStack::getAmount).sum();
        String payload = String.join("\n", def.items());
        long now = System.currentTimeMillis();
        KitDeliveryRecord record = new KitDeliveryRecord(
                deliveryId, playerId, def.name(), token, serverId, payload, totalItems,
                def.cooldownMillis(), def.oneTime(), bypassCooldown, now);

        locks.lock(target);
        inFlight.add(deliveryId);
        submitCritical(target, deliveryId, () -> {
            try {
                failIfArmed();
                KitRepository.BeginOutcome outcome = repository.beginClaim(record, System.currentTimeMillis());
                if (!(outcome instanceof KitRepository.BeginOutcome.Claiming claiming)) {
                    onServer(() -> {
                        finishInFlight(target, deliveryId);
                        reportRefusedBegin(actor, def.name(), outcome);
                    });
                    return;
                }
                if (FaultInjection.trip(FAULT_CLAIM_SLOW)) {
                    sleep(SLOW_MILLIS);
                }
                onServer(() -> deliverClaim(actor, target, other, generation, claiming.delivery(), stacks));
            } catch (SQLException e) {
                plugin.logger().warning("Kit claim for " + target.getName() + " failed before delivery: " + e.getMessage());
                onServer(() -> {
                    finishInFlight(target, deliveryId);
                    deferRecovery(target);
                    send(actor, Messages.KIT_FAILED);
                });
            }
        }, false);
    }

    private void reportRefusedBegin(CommandSender actor, String kitName, KitRepository.BeginOutcome outcome) {
        if (outcome instanceof KitRepository.BeginOutcome.AlreadyClaiming) {
            send(actor, Messages.KIT_BUSY);
        } else if (outcome instanceof KitRepository.BeginOutcome.OneTimeClaimed) {
            send(actor, Messages.KIT_ONE_TIME_CLAIMED, "{name}", kitName);
        } else if (outcome instanceof KitRepository.BeginOutcome.OnCooldown cooldown) {
            send(actor, Messages.KIT_COOLDOWN, "{name}", kitName, "{time}", formatDuration(cooldown.remainingMillis()));
        }
    }

    private void deliverClaim(CommandSender actor, Player target, boolean other, long generation,
                              KitDeliveryRecord record, List<ItemStack> stacks) {
        if (FaultInjection.trip(FAULT_CLAIM_HALT_BEFORE)) {
            Runtime.getRuntime().halt(77);
        }
        if (!same(target) || generation(target.getUniqueId()) != generation) {
            release(target, record.id(), record.claimToken());
            return;
        }
        ItemDeliveryService deliveryService = delivery.get();
        if (deliveryService == null || !emptyCursor(target)) {
            release(target, record.id(), record.claimToken());
            send(actor, Messages.KIT_FAILED);
            return;
        }
        ItemDeliveryService.MailPlan plan = deliveryService.planMail(plugin.getServer().getConsoleSender(), target, stacks);
        if (plan == null || !same(target) || generation(target.getUniqueId()) != generation || !emptyCursor(target)) {
            release(target, record.id(), record.claimToken());
            send(actor, Messages.KIT_FAILED);
            return;
        }
        // All-or-nothing REFUSE overflow rule: if any item in the kit does not fit, nothing is
        // placed and the cooldown / one-time status is not consumed.
        if (!plan.remaining().isEmpty()) {
            send(actor, Messages.KIT_FULL, "{name}", record.kitName());
            release(target, record.id(), record.claimToken());
            return;
        }
        if (!deliveryService.applyMail(target, plan)) {
            release(target, record.id(), record.claimToken());
            send(actor, Messages.KIT_FAILED);
            return;
        }
        addReceipt(target, new KitReceipts.Claimed(record.id(), record.claimToken(), record.kitName()));
        try {
            target.saveData();
        } catch (RuntimeException error) {
            inFlight.remove(record.id());
            quarantine(target, "Kit claim " + record.id() + " could not save its delivery receipt", error);
            return;
        }
        if (FaultInjection.trip(FAULT_CLAIM_HALT_AFTER)) {
            Runtime.getRuntime().halt(77);
        }
        String expectedReceipt = playerReceipt(target);
        Path playerFile = playerFile(target);
        submitCritical(target, record.id(), () -> {
            try {
                PlayerDataReceiptProof.verify(playerFile, PlayerDataReceiptProof.KIT_RECEIPTS, expectedReceipt);
                failIfArmed();
                boolean finished = repository.finishClaim(
                        record.id(), record.claimToken(), serverId, System.currentTimeMillis());
                onServer(() -> {
                    finishInFlight(target, record.id());
                    if (!same(target) || generation(target.getUniqueId()) != generation) return;
                    if (!finished) {
                        deferRecovery(target);
                        return;
                    }
                    removeReceipt(target, r -> r instanceof KitReceipts.Claimed c && c.token().equals(record.claimToken()));
                    send(target, Messages.KIT_RECEIVED, "{name}", record.kitName());
                    if (other) {
                        send(actor, Messages.KIT_GIVEN, "{name}", record.kitName(), "{player}", target.getName());
                    }
                });
            } catch (IOException e) {
                onServer(() -> {
                    finishInFlight(target, record.id());
                    quarantine(target, "Kit claim " + record.id() + " has no confirmed saved delivery receipt", e);
                });
            } catch (SQLException e) {
                plugin.logger().warning("Kit claim " + record.id() + " awaits receipt recovery: " + e.getMessage());
                onServer(() -> {
                    finishInFlight(target, record.id());
                    deferRecovery(target);
                });
            }
        }, true);
    }

    private void release(Player target, UUID deliveryId, UUID token) {
        submitCritical(target, deliveryId, () -> {
            try {
                repository.releaseClaim(deliveryId, token, serverId);
                onServer(() -> finishInFlight(target, deliveryId));
            } catch (SQLException e) {
                onServer(() -> {
                    finishInFlight(target, deliveryId);
                    deferRecovery(target);
                });
            }
        }, false);
    }

    private void finishInFlight(Player target, UUID deliveryId) {
        locks.unlock(target);
        inFlight.remove(deliveryId);
    }

    // -- Join / Quit Recovery --------------------------------------------------------------------

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
        if (connection != null) {
            generations.remove(player.getUniqueId(), connection);
        }
        locks.clear(player);
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
        List<KitReceipts.Entry> receipts = receipts(player);
        if (receipts.stream().anyMatch(r -> r instanceof KitReceipts.Unknown)) {
            recoveryBusy.remove(player);
            quarantine(player, "Unknown kit receipt; preserve player data for operator recovery", null);
            return;
        }
        String expectedReceipt = playerReceipt(player);
        Path savedFile = playerFile(player);
        submitRecovery(player, generation, () -> {
            try {
                if (!receipts.isEmpty()) {
                    PlayerDataReceiptProof.verify(savedFile, PlayerDataReceiptProof.KIT_RECEIPTS, expectedReceipt);
                }
                List<KitDeliveryRecord> claiming = repository.claimingFor(player.getUniqueId(), serverId);
                onServer(() -> recover(player, generation, receipts, claiming));
            } catch (IOException e) {
                onServer(() -> {
                    recoveryBusy.remove(player);
                    quarantine(player, "Kit recovery has no confirmed saved receipt for " + player.getUniqueId(), e);
                });
            } catch (SQLException e) {
                onServer(() -> recoveryFailed(player, generation, e));
            }
        });
    }

    private void recover(Player player, long generation, List<KitReceipts.Entry> receipts, List<KitDeliveryRecord> claiming) {
        if (!same(player) || generation(player.getUniqueId()) != generation) return;
        if (claiming.stream().anyMatch(d -> inFlight.contains(d.id()))
                || receipts.stream().anyMatch(r -> r instanceof KitReceipts.Claimed c && inFlight.contains(c.deliveryId()))) {
            recoveryBusy.remove(player);
            locks.unlock(player);
            return; // Wait for the previous connection's in-flight callback to settle.
        }
        List<RecoveryStep> steps = new ArrayList<>();
        for (KitReceipts.Entry entry : receipts) {
            if (entry instanceof KitReceipts.Claimed receipt) {
                KitDeliveryRecord row = claiming.stream()
                        .filter(d -> d.id().equals(receipt.deliveryId()) && d.claimToken().equals(receipt.token()))
                        .findFirst()
                        .orElse(null);
                if (row == null) {
                    steps.add(() -> {
                        if (!repository.claimAcknowledged(receipt.deliveryId(), receipt.token())) {
                            throw new SQLException("missing kit claim acknowledgement " + receipt.token());
                        }
                    });
                } else {
                    steps.add(() -> {
                        if (!repository.finishClaim(receipt.deliveryId(), receipt.token(), serverId, System.currentTimeMillis())) {
                            throw new SQLException("kit claim ownership changed " + receipt.token());
                        }
                    });
                }
            }
        }
        for (KitDeliveryRecord row : claiming) {
            boolean hasReceipt = receipts.stream().anyMatch(r -> r instanceof KitReceipts.Claimed c
                    && c.deliveryId().equals(row.id()) && c.token().equals(row.claimToken()));
            if (!hasReceipt) {
                steps.add(() -> repository.releaseClaim(row.id(), row.claimToken(), serverId));
            }
        }
        submitRecovery(player, generation, () -> {
            try {
                for (RecoveryStep step : steps) {
                    step.run();
                }
                onServer(() -> {
                    if (!same(player) || generation(player.getUniqueId()) != generation) return;
                    if (!receipts.isEmpty()) {
                        removeReceipt(player, receipts::contains);
                        player.saveData();
                    }
                    recovering.remove(player);
                    recoveryBusy.remove(player);
                    recoveryFailures.remove(player);
                    locks.unlock(player);
                });
            } catch (SQLException error) {
                onServer(() -> recoveryFailed(player, generation, error));
            }
        });
    }

    @FunctionalInterface
    private interface RecoveryStep {
        void run() throws SQLException;
    }

    private void recoveryFailed(Player player, long generation, Exception error) {
        if (!same(player) || generation(player.getUniqueId()) != generation) return;
        recoveryBusy.remove(player);
        locks.unlock(player);
        int attempts = recoveryFailures.merge(player, 1, Integer::sum);
        if (attempts == 1 || attempts == 5) {
            plugin.logger().warning("Kit recovery postponed for " + player.getUniqueId() + ": " + error.getMessage());
        }
        if (attempts >= 5) {
            recovering.remove(player);
            player.kickPlayer("Kit recovery is temporarily unavailable; please try again shortly.");
        }
    }

    private void deferRecovery(Player player) {
        if (same(player)) {
            recovering.add(player);
        }
    }

    private void quarantine(Player player, String reason, @Nullable Exception error) {
        plugin.logger().severe(reason + (error == null ? "" : ": " + error));
        if (same(player)) {
            locks.lock(player);
            if (quarantined.add(player)) {
                player.kickPlayer("Your inventory needs kit recovery; please reconnect shortly.");
            }
        }
    }

    // -- Permissions & Helpers -------------------------------------------------------------------

    private boolean hasKitAccess(CommandSender sender, String kitName) {
        return KitNames.isValid(kitName)
                && definitions.containsKey(kitName)
                && !blockedPermissions.contains(kitName)
                && (sender.hasPermission(PERMISSION_PREFIX + kitName) || sender.hasPermission(PERMISSION_WILDCARD));
    }

    private Permission ensureWildcardPermission() {
        var manager = plugin.getServer().getPluginManager();
        Permission wildcard = manager.getPermission(PERMISSION_WILDCARD);
        if (wildcard == null) {
            wildcard = new Permission(PERMISSION_WILDCARD, PermissionDefault.FALSE);
            manager.addPermission(wildcard);
        }
        return wildcard;
    }

    private void ensurePermission(String rawName) {
        String name = KitNames.validate(rawName);
        var manager = plugin.getServer().getPluginManager();
        Permission wildcard = ensureWildcardPermission();
        String node = PERMISSION_PREFIX + name;
        Permission permission = manager.getPermission(node);
        if (permission == null) {
            manager.addPermission(new Permission(node, PermissionDefault.FALSE));
            blockedPermissions.remove(name);
        } else if (permission.getDefault() != PermissionDefault.FALSE) {
            if (blockedPermissions.add(name)) {
                plugin.logger().severe("Kit access blocked: " + node
                        + " already has a non-false default; grant access explicitly with a false-default node");
            }
        } else {
            blockedPermissions.remove(name);
        }
        if (wildcard != null && !blockedPermissions.contains(name)) {
            if (!Boolean.TRUE.equals(wildcard.getChildren().get(node))) {
                wildcard.getChildren().put(node, Boolean.TRUE);
                wildcard.recalculatePermissibles();
            }
        }
    }

    private boolean busyOrLocked(Player player) {
        if (locks.locked(player) || recovering.contains(player) || recoveryBusy.contains(player) || quarantined.contains(player)) {
            return true;
        }
        if (plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("mail") instanceof MailModule mailModule
                && mailModule.isEnabled() && mailModule.mail() != null) {
            MailService mail = mailModule.mail();
            if (mail.busy(player)) {
                return true;
            }
        }
        return false;
    }

    public boolean busy(Player player) {
        return locks.locked(player) || recovering.contains(player) || recoveryBusy.contains(player) || quarantined.contains(player);
    }

    private void submitCritical(Player player, UUID deliveryId, Runnable task, boolean pendingProof) {
        try {
            io.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException error) {
                    onServer(() -> rejectedCritical(player, deliveryId, pendingProof, error));
                }
            });
        } catch (RejectedExecutionException error) {
            rejectedCritical(player, deliveryId, pendingProof, error);
        }
    }

    private void rejectedCritical(Player player, UUID deliveryId, boolean pendingProof, RuntimeException error) {
        inFlight.remove(deliveryId);
        if (pendingProof) {
            quarantine(player, "Kit operation " + deliveryId + " stopped before receipt verification", error);
        } else {
            locks.unlock(player);
            deferRecovery(player);
            plugin.logger().warning("Kit operation " + deliveryId + " awaits reconciliation: " + error);
        }
    }

    private void submitRecovery(Player player, long generation, Runnable task) {
        try {
            io.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException error) {
                    onServer(() -> recoveryFailed(player, generation, error));
                }
            });
        } catch (RejectedExecutionException error) {
            recoveryFailed(player, generation, error);
        }
    }

    private String playerReceipt(Player player) {
        return player.getPersistentDataContainer().get(receiptKey, PersistentDataType.STRING);
    }

    private Path playerFile(Player player) {
        return playerDataDirectory.resolve(player.getUniqueId() + ".dat");
    }

    private List<KitReceipts.Entry> receipts(Player player) {
        String raw = player.getPersistentDataContainer().get(receiptKey, PersistentDataType.STRING);
        return KitReceipts.parse(raw);
    }

    private void addReceipt(Player player, KitReceipts.Entry entry) {
        List<KitReceipts.Entry> all = new ArrayList<>(receipts(player));
        all.add(entry);
        player.getPersistentDataContainer().set(receiptKey, PersistentDataType.STRING, KitReceipts.format(all));
    }

    private void removeReceipt(Player player, Predicate<KitReceipts.Entry> which) {
        if (!same(player)) return;
        List<KitReceipts.Entry> all = new ArrayList<>(receipts(player));
        if (all.removeIf(which)) {
            if (all.isEmpty()) {
                player.getPersistentDataContainer().remove(receiptKey);
            } else {
                player.getPersistentDataContainer().set(receiptKey, PersistentDataType.STRING, KitReceipts.format(all));
            }
        }
    }

    private boolean same(Player player) {
        return !closed && player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }

    private boolean emptyCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        return cursor == null || cursor.getType().isAir() || cursor.getAmount() <= 0;
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
        if (!closed) {
            plugin.getScheduler().runOnServer(() -> {
                if (!closed) task.run();
            });
        }
    }

    private void send(CommandSender to, Messages message, String... replacements) {
        if (!(to instanceof Player p) || same(p)) {
            plugin.getMessageManager().send(to, message, replacements);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static String formatDuration(long remainingMillis) {
        long seconds = Math.max(1L, (remainingMillis + 999L) / 1000L);
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append("s");
        return sb.toString().trim();
    }
}
