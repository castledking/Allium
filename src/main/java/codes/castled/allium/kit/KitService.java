package codes.castled.allium.kit;

import codes.castled.allium.PluginStart;
import codes.castled.allium.economy.*;
import codes.castled.allium.economy.EconomyResult.*;
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
import codes.castled.allium.scheduler.BukkitServerThread;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Kits: free claims, E5 paid self claims, cooldowns, one-time kits, {@code /createkit} and {@code /delkit}.
 * Free delivery supports SQLite/MySQL; paid receipt-backed reservations use Phase 1 SQLite only.
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
    public static final String PERMISSION_SHOWKIT = "allium.showkit";
    public static final String PERMISSION_KITRESET = "allium.kitreset";
    public static final String PERMISSION_KITRESET_OTHERS = "allium.kitreset.others";
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
    private final Map<Player, Long> connections = Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicLong generationCounter = new AtomicLong();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<Player> recovering = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Set<Player> recoveryBusy = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Set<Player> quarantined = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Map<Player, Integer> recoveryFailures = Collections.synchronizedMap(new IdentityHashMap<>());
    private TaskHandle recoveryTimer;
    private volatile boolean closed;

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
            onPlayerNow(online, () -> joined(online), () -> {});
        }
        recoveryTimer = plugin.getScheduler().repeatOnServer(() -> {
            List<Player> pending;
            synchronized (recovering) {
                pending = List.copyOf(recovering);
            }
            for (Player player : pending) {
                onPlayer(player, () -> {
                    if (same(player) && !quarantined.contains(player) && !recoveryBusy.contains(player)) {
                        joined(player);
                    }
                }, () -> {
                    recovering.remove(player);
                    recoveryBusy.remove(player);
                });
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
            if (!sender.hasPermission(PERMISSION_DELKIT) && !sender.hasPermission(PERMISSION_CREATEKIT)
                    && !sender.hasPermission(PERMISSION_KITRESET)) {
                return List.of();
            }
            return definitions.keySet().stream()
                    .filter(KitNames::isValid)
                    .filter(name -> name.startsWith(lower))
                    .sorted()
                    .limit(50)
                    .toList();
        }
        if (!sender.hasPermission(PERMISSION_KIT) && !sender.hasPermission(PERMISSION_KITS)
                && !sender.hasPermission(PERMISSION_SHOWKIT)) {
            return List.of();
        }
        return definitions.values().stream()
                .filter(def -> KitNames.isValid(def.name()) && (!def.paid() || sender instanceof Player && PaidWorkflowSupport.ready(plugin)) && hasKitAccess(sender, def.name()))
                .map(KitDefinition::name)
                .filter(name -> name.startsWith(lower))
                .sorted()
                .limit(50)
                .toList();
    }

    public void listKits(CommandSender sender) {
        List<String> available = definitions.values().stream()
                .filter(def -> KitNames.isValid(def.name()) && (!def.paid() || sender instanceof Player && PaidWorkflowSupport.ready(plugin)) && hasKitAccess(sender, def.name()))
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
        codes.castled.allium.command.essentials.UtilitySupport.onGlobal(plugin, () -> ensurePermission(name));
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

    private static final class PreviewHolder implements org.bukkit.inventory.InventoryHolder {
        private org.bukkit.inventory.Inventory inventory;

        @Override
        public @org.jetbrains.annotations.NotNull org.bukkit.inventory.Inventory getInventory() {
            return inventory;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreviewClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PreviewHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreviewDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PreviewHolder) {
            event.setCancelled(true);
        }
    }

    /** Opens a read-only preview GUI of a kit's items for a player, or lists them in chat for the console. */
    public void showKit(CommandSender sender, String rawName) {
        String name;
        try {
            name = KitNames.validate(rawName == null ? null : rawName.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(sender, Messages.KIT_NOT_FOUND, "{name}", rawName == null ? "" : rawName);
            return;
        }
        KitDefinition def = definitions.get(name);
        if (def == null || !def.enabled()) {
            send(sender, Messages.KIT_NOT_FOUND, "{name}", name);
            return;
        }
        if (!(sender instanceof ConsoleCommandSender) && (blockedPermissions.contains(name) || !hasKitAccess(sender, name))) {
            send(sender, Messages.KIT_NO_PERMISSION, "{name}", name);
            return;
        }
        ItemLibrary itemLibrary = library.get();
        ItemSpecParser specParser = parser.get();
        if (itemLibrary == null || specParser == null) {
            send(sender, Messages.KIT_FAILED);
            return;
        }
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
            plugin.logger().warning("Kit '" + name + "' could not build preview items: " + e.getMessage());
            send(sender, Messages.KIT_INVALID, "{name}", name, "{reason}", e.getMessage());
            return;
        }

        Runnable present = () -> {
            List<ItemStack> stacks = materializeStacks(preparedItems);
            if (!(sender instanceof Player player)) {
                send(sender, Messages.KIT_SHOW_HEADER, "{name}", def.name(), "{count}", Integer.toString(stacks.size()));
                for (ItemStack stack : stacks) {
                    if (stack == null || stack.getType().isAir()) continue;
                    String label = stack.hasItemMeta() && stack.getItemMeta() != null && stack.getItemMeta().hasDisplayName()
                            ? stack.getItemMeta().getDisplayName()
                            : codes.castled.allium.command.essentials.UtilitySupport.formatMaterialName(stack.getType());
                    send(sender, Messages.KIT_SHOW_ENTRY,
                            "{amount}", Integer.toString(stack.getAmount()),
                            "{item}", label);
                }
                return;
            }
            if (!same(player)) return;
            int rows = Math.max(1, Math.min(6, (stacks.size() + 8) / 9));
            int size = rows * 9;
            String title = "Kit: " + def.name();
            if (title.length() > 32) title = title.substring(0, 32);
            PreviewHolder holder = new PreviewHolder();
            org.bukkit.inventory.Inventory inv = plugin.getServer().createInventory(holder, size, title);
            holder.inventory = inv;
            int slot = 0;
            for (ItemStack stack : stacks) {
                if (slot >= size) break;
                if (stack != null && !stack.getType().isAir()) {
                    inv.setItem(slot++, stack.clone());
                }
            }
            player.openInventory(inv);
        };

        if (pendingSkulls.isEmpty()) {
            present.run();
        } else {
            CompletableFuture.allOf(pendingSkulls.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, error) -> onSender(sender, () -> {
                        if (error != null) {
                            send(sender, Messages.KIT_FAILED);
                            return;
                        }
                        present.run();
                    }));
        }
    }

    /** Clears a player's cooldown (and one-time usage) for one kit. */
    public void resetKit(CommandSender actor, String rawName, @Nullable String targetArg) {
        String name;
        try {
            name = KitNames.validate(rawName == null ? null : rawName.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(actor, Messages.KIT_NOT_FOUND, "{name}", rawName == null ? "" : rawName);
            return;
        }
        if (!definitions.containsKey(name)) {
            send(actor, Messages.KIT_NOT_FOUND, "{name}", name);
            return;
        }
        if (targetArg == null) {
            if (!(actor instanceof Player player)) {
                send(actor, Messages.PLAYER_ONLY);
                return;
            }
            runKitReset(actor, player.getUniqueId(), player.getName(), name, true);
            return;
        }
        boolean targetingSelf = actor instanceof Player p && p.getName() != null && p.getName().equalsIgnoreCase(targetArg);
        if (!targetingSelf && !actor.hasPermission(PERMISSION_KITRESET_OTHERS)) {
            send(actor, Messages.NO_PERMISSION);
            return;
        }
        Player online = plugin.getServer().getPlayerExact(targetArg);
        if (online != null) {
            if (!codes.castled.allium.naming.PlayerNameVisibility.canSeeTarget(actor, online)) {
                send(actor, Messages.PLAYER_NOT_FOUND, "{player}", targetArg);
                return;
            }
            runKitReset(actor, online.getUniqueId(), online.getName(), name, actor.equals(online));
            return;
        }
        var names = plugin.getPlayerNames();
        if (names == null) {
            send(actor, Messages.PLAYER_NOT_FOUND, "{player}", targetArg);
            return;
        }
        try {
            names.resolve(targetArg).whenComplete((found, error) -> onSender(actor, () -> {
                if (error != null) {
                    send(actor, Messages.KIT_FAILED);
                    return;
                }
                if (found == null || found.isEmpty()) {
                    send(actor, Messages.PLAYER_NOT_FOUND, "{player}", targetArg);
                    return;
                }
                var entry = found.get();
                boolean self = actor instanceof Player p && p.getUniqueId().equals(entry.uuid());
                runKitReset(actor, entry.uuid(), entry.name(), name, self);
            }));
        } catch (RejectedExecutionException rejected) {
            send(actor, Messages.KIT_FAILED);
        }
    }

    private void runKitReset(CommandSender actor, UUID targetId, String targetName, String kitName, boolean self) {
        try {
            io.execute(() -> {
                try {
                    boolean cleared = repository.resetUsage(targetId, kitName);
                    onSender(actor, () -> {
                        if (!cleared) {
                            send(actor, Messages.KIT_RESET_NONE, "{name}", kitName, "{player}", targetName);
                        } else if (self) {
                            send(actor, Messages.KIT_RESET_SELF, "{name}", kitName);
                        } else {
                            send(actor, Messages.KIT_RESET_OTHER, "{name}", kitName, "{player}", targetName);
                        }
                    });
                } catch (SQLException e) {
                    plugin.logger().warning("Could not reset kit " + kitName + " for " + targetName + ": " + e.getMessage());
                    onSender(actor, () -> send(actor, Messages.KIT_FAILED));
                }
            });
        } catch (RejectedExecutionException rejected) {
            send(actor, Messages.KIT_FAILED);
        }
    }

    public void claim(CommandSender actor, Player target, String rawName) {
        claim(actor, target, rawName, null, () -> true, null);
    }

    /** Whether {@code player} has finished kit join-recovery and is idle enough for onboarding kit delivery. */
    public boolean readyForOnboarding(Player player) {
        return !closed && same(player) && !busyOrLocked(player);
    }

    /**
     * Grants a free starter kit during first-join onboarding using the console actor and full
     * crash-safe kit receipt pipeline. {@code onOutcome} is invoked once with {@code true} if the
     * kit items were delivered or {@code false} if delivery was refused or unavailable before
     * modifying the player's inventory.
     */
    public void grantOnboardingKit(Player target, String rawName, @Nullable Consumer<Boolean> onOutcome) {
        Consumer<Boolean> once = onceOutcome(onOutcome);
        if (closed || !same(target)) {
            once.accept(false);
            return;
        }
        claim(plugin.getServer().getConsoleSender(), target, rawName, new Money(0), () -> true, once);
    }

    private static Consumer<Boolean> onceOutcome(@Nullable Consumer<Boolean> delegate) {
        if (delegate == null) return ignored -> {};
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        return result -> {
            if (done.compareAndSet(false, true)) {
                delegate.accept(result);
            }
        };
    }

    /**
     * Claims a kit from a {@code [Kit]} sign with an optional sign-price override. When
     * {@code signPriceOverride} is non-null, it replaces the kit's configured command cost
     * (charging {@code signPriceOverride} through the same E5 reservation/receipt workflow, or
     * claiming free when {@code signPriceOverride} is zero).
     */
    public void claimFromSign(Player player, String rawName, @Nullable Money signPriceOverride) {
        claimFromSign(player, rawName, signPriceOverride, () -> true);
    }

    /** Claims a kit from a {@code [Kit]} sign with an optional sign-price override and sign-validity recheck. */
    public void claimFromSign(Player player, String rawName, @Nullable Money signPriceOverride, BooleanSupplier valid) {
        if (!player.hasPermission(PERMISSION_KIT)) {
            send(player, Messages.NO_PERMISSION);
            return;
        }
        claim(player, player, rawName, signPriceOverride, valid == null ? () -> true : valid, null);
    }

    private void claim(CommandSender actor, Player target, String rawName, @Nullable Money signPriceOverride,
                       BooleanSupplier valid, @Nullable Consumer<Boolean> onOutcome) {
        Consumer<Boolean> report = onceOutcome(onOutcome);
        if (!same(target)) {
            send(actor, Messages.PLAYER_NOT_FOUND, "{player}", target.getName());
            report.accept(false);
            return;
        }
        boolean other = !(actor instanceof Player p && p.getUniqueId().equals(target.getUniqueId()));
        if (other && !actor.hasPermission(PERMISSION_KIT_OTHERS)) {
            send(actor, Messages.NO_PERMISSION);
            report.accept(false);
            return;
        }
        String name;
        try {
            // Kit names are stored lowercase; "/kit Starter" means the kit "starter".
            name = KitNames.validate(rawName == null ? null : rawName.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(actor, Messages.KIT_NOT_FOUND, "{name}", rawName);
            report.accept(false);
            return;
        }
        KitDefinition configured = definitions.get(name);
        if (configured == null || !configured.enabled()) {
            send(actor, Messages.KIT_NOT_FOUND, "{name}", name);
            report.accept(false);
            return;
        }
        KitDefinition def = signPriceOverride == null
                ? configured
                : new KitDefinition(configured.name(), configured.enabled(), configured.cooldownSeconds(),
                        configured.oneTime(), signPriceOverride.toString(), configured.items(), configured.sourceFile());
        if (blockedPermissions.contains(name)) {
            send(actor, Messages.KIT_NO_PERMISSION, "{name}", name);
            report.accept(false);
            return;
        }
        if (!(actor instanceof ConsoleCommandSender) && !hasKitAccess(actor, name)) {
            send(actor, Messages.KIT_NO_PERMISSION, "{name}", name);
            report.accept(false);
            return;
        }
        if (def.paid() && (other || !PaidWorkflowSupport.ready(plugin))) {
            send(actor, Messages.KIT_PAID_DENIED, "{name}", name);
            report.accept(false);
            return;
        }
        ItemLibrary itemLibrary = library.get();
        ItemDeliveryService deliveryService = delivery.get();
        if (itemLibrary == null || deliveryService == null) {
            send(actor, Messages.KIT_FAILED);
            report.accept(false);
            return;
        }
        boolean bypassCooldown = other || actor.hasPermission(PERMISSION_BYPASS_COOLDOWN);
        if (busyOrLocked(target) || (!other && !emptyCursor(target))) {
            send(actor, Messages.KIT_BUSY);
            report.accept(false);
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
            report.accept(false);
            return;
        }

        String targetName = target.getName();
        Runnable missingTarget = () -> {
            if (other) send(actor, Messages.PLAYER_NOT_FOUND, "{player}", targetName);
            report.accept(false);
        };
        if (pendingSkulls.isEmpty()) {
            List<ItemStack> stacks = materializeStacks(preparedItems);
            onPlayerNow(target, () -> startDurableClaim(actor, target, other, bypassCooldown, def, stacks, valid, report), missingTarget);
        } else {
            CompletableFuture.allOf(pendingSkulls.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            send(actor, Messages.KIT_FAILED);
                            report.accept(false);
                            return;
                        }
                        onPlayer(target, () -> {
                            if (!same(target)) {
                                missingTarget.run();
                                return;
                            }
                            startDurableClaim(actor, target, other, bypassCooldown, def, materializeStacks(preparedItems), valid, report);
                        }, missingTarget);
                    });
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

    private void startDurableClaim(CommandSender actor, Player target, boolean other, boolean bypassCooldown,
                                   KitDefinition def, List<ItemStack> stacks, BooleanSupplier valid,
                                   Consumer<Boolean> report) {
        if (!same(target)) {
            if (other) send(actor, Messages.PLAYER_NOT_FOUND, "{player}", target.getName());
            report.accept(false);
            return;
        }
        if (busyOrLocked(target) || !emptyCursor(target)) {
            send(actor, Messages.KIT_BUSY);
            report.accept(false);
            return;
        }
        UUID playerId = target.getUniqueId();
        long generation = generation(playerId);
        UUID deliveryId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        int totalItems = stacks.stream().mapToInt(ItemStack::getAmount).sum();
        String payload = String.join("\n", def.items());
        long now = System.currentTimeMillis();
        KitDeliveryRecord record = new KitDeliveryRecord(
                deliveryId, playerId, def.name(), token, serverId, payload, totalItems,
                def.cooldownMillis(), def.oneTime(), bypassCooldown, now);

        locks.lock(target);
        // A valid saved baseline is required to prove absence if the JVM halts before delivery.
        if(def.paid())try{target.saveData();}catch(RuntimeException error){locks.unlock(target);send(actor,Messages.KIT_FAILED);report.accept(false);return;}
        String baseline=playerReceipt(target);Path baselineFile=playerFile(target);
        inFlight.add(deliveryId);
        submitCritical(target, deliveryId, () -> {
            try {
                failIfArmed();
                KitRepository.BeginOutcome outcome = beginClaim(record,def,baselineFile,baseline);
                if (!(outcome instanceof KitRepository.BeginOutcome.Claiming claiming)) {
                    Runnable finish = () -> {
                        finishInFlight(target, deliveryId);
                        reportRefusedBegin(actor, def.name(), outcome);
                        report.accept(false);
                    };
                    onPlayer(target, finish, finish);
                    return;
                }
                if (FaultInjection.trip(FAULT_CLAIM_SLOW)) {
                    sleep(SLOW_MILLIS);
                }
                onPlayer(target, () -> deliverClaim(actor, target, other, generation, claiming.delivery(), stacks, def.paid(), valid, report),
                        () -> {
                            report.accept(false);
                            releaseRetired(target, claiming.delivery().id(), claiming.delivery().claimToken(), baselineFile, baseline);
                        });
            } catch (SQLException e) {
                plugin.logger().warning("Kit claim for " + target.getName() + " failed before delivery: " + e.getMessage());
                onPlayer(target, () -> {
                    finishInFlight(target, deliveryId);
                    deferRecovery(target);
                    send(actor, Messages.KIT_FAILED);
                    report.accept(false);
                }, () -> {
                    finishInFlight(target, deliveryId);
                    send(actor, Messages.KIT_FAILED);
                    report.accept(false);
                });
            }
        }, false);
    }

    private KitRepository.BeginOutcome beginClaim(KitDeliveryRecord record,KitDefinition definition,Path file,String baseline)throws SQLException {
        if(!definition.paid())return repository.beginClaim(record,System.currentTimeMillis());
        Money amount;try{amount=Money.parse(definition.cost(),false).positive();}catch(RuntimeException invalid){return new KitRepository.BeginOutcome.CostRefused();}
        var observed=new java.util.concurrent.atomic.AtomicReference<KitRepository.BeginOutcome>();
        PaidWorkflowSupport.prove(file,PlayerDataReceiptProof.KIT_RECEIPTS,baseline);
        Mutation result=PaidWorkflowSupport.reservations(plugin).reserve(EconomyReservations.Kind.KIT,record.id(),record.playerUuid(),amount,EconomyReservations.fingerprint(record.id().toString(),record.playerUuid().toString(),record.kitName(),record.claimToken().toString(),record.claimOwner(),record.itemsPayload(),Integer.toString(record.itemCount()),Long.toString(record.cooldownMillis()),Boolean.toString(record.oneTime()),Boolean.toString(record.bypassCooldown()),Long.toString(record.createdAt())),c->{
            var outcome=repository.beginClaim(c,record,System.currentTimeMillis());observed.set(outcome);
            return outcome instanceof KitRepository.BeginOutcome.Claiming claim&&claim.delivery().id().equals(record.id())&&claim.delivery().claimToken().equals(record.claimToken());
        });
        if(result instanceof Rejected){return observed.get() instanceof KitRepository.BeginOutcome.Claiming?new KitRepository.BeginOutcome.AlreadyClaiming():observed.get()!=null?observed.get():new KitRepository.BeginOutcome.CostRefused();}
        PaidWorkflowSupport.committed(result);
        return repository.findDelivery(record.id()).<KitRepository.BeginOutcome>map(KitRepository.BeginOutcome.Claiming::new).orElseGet(KitRepository.BeginOutcome.AlreadyClaiming::new);
    }
    private boolean finishClaim(UUID id,UUID token,long now,Path file,String expected)throws SQLException {
        if(!repository.paid(id))return repository.finishClaim(id,token,serverId,now);
        if(KitReceipts.parse(expected).stream().noneMatch(r->r instanceof KitReceipts.Claimed receipt&&receipt.deliveryId().equals(id)&&receipt.token().equals(token)))throw new SQLException("missing matching kit receipt");
        PaidWorkflowSupport.prove(file,PlayerDataReceiptProof.KIT_RECEIPTS,expected);
        if(PaidWorkflowSupport.reservations(plugin).operatorResolved(EconomyReservations.Kind.KIT,id))throw new SQLException("operator-resolved workflow remains quarantined");
        return PaidWorkflowSupport.committed(PaidWorkflowSupport.reservations(plugin).capture(EconomyReservations.Kind.KIT,id,c->{
            return repository.finishClaim(c,id,token,serverId,now);
        }));
    }
    private boolean releaseClaim(UUID id,UUID token,Path file,String expected)throws SQLException {
        if(!repository.paid(id))return repository.releaseClaim(id,token,serverId);
        if(KitReceipts.parse(expected).stream().anyMatch(r->r instanceof KitReceipts.Claimed receipt&&receipt.deliveryId().equals(id)))throw new SQLException("cannot release a kit with a delivery receipt");
        PaidWorkflowSupport.prove(file,PlayerDataReceiptProof.KIT_RECEIPTS,expected);
        if(PaidWorkflowSupport.reservations(plugin).operatorResolved(EconomyReservations.Kind.KIT,id))throw new SQLException("operator-resolved workflow remains quarantined");
        return PaidWorkflowSupport.committed(PaidWorkflowSupport.reservations(plugin).release(EconomyReservations.Kind.KIT,id,c->{
            return repository.releaseClaim(c,id,token,serverId);
        }));
    }

    private void reportRefusedBegin(CommandSender actor, String kitName, KitRepository.BeginOutcome outcome) {
        if(outcome instanceof KitRepository.BeginOutcome.CostRefused){
            send(actor,Messages.KIT_COST_REFUSED);
        }else if (outcome instanceof KitRepository.BeginOutcome.AlreadyClaiming) {
            send(actor, Messages.KIT_BUSY);
        } else if (outcome instanceof KitRepository.BeginOutcome.OneTimeClaimed) {
            send(actor, Messages.KIT_ONE_TIME_CLAIMED, "{name}", kitName);
        } else if (outcome instanceof KitRepository.BeginOutcome.OnCooldown cooldown) {
            send(actor, Messages.KIT_COOLDOWN, "{name}", kitName, "{time}", formatDuration(cooldown.remainingMillis()));
        }
    }

    private void deliverClaim(CommandSender actor, Player target, boolean other, long generation,
                              KitDeliveryRecord record, List<ItemStack> stacks, boolean paid, BooleanSupplier valid,
                              Consumer<Boolean> report) {
        if (FaultInjection.trip(FAULT_CLAIM_HALT_BEFORE)) {
            Runtime.getRuntime().halt(77);
        }
        if (!same(target) || generation(target.getUniqueId()) != generation || !valid.getAsBoolean()) {
            report.accept(false);
            release(target, record.id(), record.claimToken());
            return;
        }
        ItemDeliveryService deliveryService = delivery.get();
        if (deliveryService == null || !emptyCursor(target)) {
            report.accept(false);
            release(target, record.id(), record.claimToken());
            send(actor, Messages.KIT_FAILED);
            return;
        }
        ItemDeliveryService.MailPlan plan = deliveryService.planMail(plugin.getServer().getConsoleSender(), target, stacks);
        if (plan == null || !same(target) || generation(target.getUniqueId()) != generation || !emptyCursor(target)) {
            report.accept(false);
            release(target, record.id(), record.claimToken());
            send(actor, Messages.KIT_FAILED);
            return;
        }
        // All-or-nothing REFUSE overflow rule: if any item in the kit does not fit, nothing is
        // placed and the cooldown / one-time status is not consumed.
        if (!plan.remaining().isEmpty()) {
            send(actor, Messages.KIT_FULL, "{name}", record.kitName());
            report.accept(false);
            release(target, record.id(), record.claimToken());
            return;
        }
        // An in-memory marker accompanies any unexpected partial mutation into native playerdata.
        String partialMarker="P/"+record.id();
        if(paid)addReceipt(target,new KitReceipts.Unknown(partialMarker));
        boolean applied;
        try{applied=deliveryService.applyMail(target,plan);}catch(RuntimeException error){
            try{target.saveData();}catch(RuntimeException ignored){}
            inFlight.remove(record.id());quarantine(target,"Kit apply failed with unknown partial effects "+record.id(),error);
            report.accept(false);
            return;
        }
        if (!applied) {
            if(paid)removeReceipt(target,r->r instanceof KitReceipts.Unknown unknown&&unknown.raw().equals(partialMarker));
            report.accept(false);
            release(target, record.id(), record.claimToken());
            send(actor, Messages.KIT_FAILED);
            return;
        }
        // REFUSE partial kits. If an unexpected provider/plugin changes the applied result, retain
        // the hold and quarantine; neither a charge nor a timeout refund can prove completion.
        if(paid&&!java.util.Arrays.equals(plan.after(),target.getInventory().getStorageContents())){
            try{target.saveData();}catch(RuntimeException ignored){}
            inFlight.remove(record.id());quarantine(target,"Kit inventory effects differ from the complete plan "+record.id(),null);
            report.accept(false);
            return;
        }
        if(paid)removeReceipt(target,r->r instanceof KitReceipts.Unknown unknown&&unknown.raw().equals(partialMarker));
        addReceipt(target, new KitReceipts.Claimed(record.id(), record.claimToken(), record.kitName()));
        try {
            target.saveData();
        } catch (RuntimeException error) {
            inFlight.remove(record.id());
            quarantine(target, "Kit claim " + record.id() + " could not save its delivery receipt", error);
            report.accept(false);
            return;
        }
        // Items are now delivered and saved with a durable receipt; mark onboarding delivery succeeded.
        report.accept(true);
        if (FaultInjection.trip(FAULT_CLAIM_HALT_AFTER)) {
            Runtime.getRuntime().halt(77);
        }
        String expectedReceipt = playerReceipt(target);
        Path playerFile = playerFile(target);
        submitCritical(target, record.id(), () -> {
            try {
                PlayerDataReceiptProof.verify(playerFile, PlayerDataReceiptProof.KIT_RECEIPTS, expectedReceipt);
                failIfArmed();
                boolean finished = finishClaim(record.id(),record.claimToken(),System.currentTimeMillis(),playerFile,expectedReceipt);
                onPlayer(target, () -> {
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
                }, () -> finishInFlight(target, record.id()));
            } catch (IOException e) {
                onPlayer(target, () -> {
                    finishInFlight(target, record.id());
                    quarantine(target, "Kit claim " + record.id() + " has no confirmed saved delivery receipt", e);
                }, () -> finishInFlight(target, record.id()));
            } catch (SQLException e) {
                plugin.logger().warning("Kit claim " + record.id() + " awaits receipt recovery: " + e.getMessage());
                onPlayer(target, () -> {
                    finishInFlight(target, record.id());
                    deferRecovery(target);
                }, () -> finishInFlight(target, record.id()));
            }
        }, true);
    }

    private void release(Player target, UUID deliveryId, UUID token) {
        Path proofFile = playerFile(target);
        String proofReceipt = playerReceipt(target);
        releaseWithProof(target, deliveryId, token, proofFile, proofReceipt);
    }

    private void releaseRetired(Player target, UUID deliveryId, UUID token, Path proofFile, @Nullable String proofReceipt) {
        releaseWithProof(target, deliveryId, token, proofFile, proofReceipt);
    }

    private void releaseWithProof(Player target, UUID deliveryId, UUID token, Path proofFile, @Nullable String proofReceipt) {
        submitCritical(target, deliveryId, () -> {
            try {
                releaseClaim(deliveryId, token, proofFile, proofReceipt);
                onPlayer(target, () -> finishInFlight(target, deliveryId), () -> finishInFlight(target, deliveryId));
            } catch (SQLException e) {
                onPlayer(target, () -> {
                    finishInFlight(target, deliveryId);
                    deferRecovery(target);
                }, () -> finishInFlight(target, deliveryId));
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
                if(receipts.isEmpty())for(var row:claiming)if(repository.paid(row.id()))PaidWorkflowSupport.prove(savedFile,PlayerDataReceiptProof.KIT_RECEIPTS,expectedReceipt);
                onPlayer(player, () -> recover(player, generation, receipts, claiming), () -> finishRecoveryRetired(player));
            } catch (IOException e) {
                onPlayer(player, () -> {
                    recoveryBusy.remove(player);
                    quarantine(player, "Kit recovery has no confirmed saved receipt for " + player.getUniqueId(), e);
                }, () -> finishRecoveryRetired(player));
            } catch (SQLException e) {
                onPlayer(player, () -> recoveryFailed(player, generation, e), () -> finishRecoveryRetired(player));
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
        Path recoveryFile=playerFile(player);
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
                        if (!finishClaim(receipt.deliveryId(),receipt.token(),System.currentTimeMillis(),recoveryFile,KitReceipts.format(receipts))) {
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
                steps.add(() -> releaseClaim(row.id(),row.claimToken(),recoveryFile,KitReceipts.format(receipts)));
            }
        }
        submitRecovery(player, generation, () -> {
            try {
                for (RecoveryStep step : steps) {
                    step.run();
                }
                onPlayer(player, () -> {
                    if (!same(player) || generation(player.getUniqueId()) != generation) return;
                    if (!receipts.isEmpty()) {
                        removeReceipt(player, receipts::contains);
                        player.saveData();
                    }
                    recovering.remove(player);
                    recoveryBusy.remove(player);
                    recoveryFailures.remove(player);
                    locks.unlock(player);
                }, () -> finishRecoveryRetired(player));
            } catch (SQLException error) {
                onPlayer(player, () -> recoveryFailed(player, generation, error), () -> finishRecoveryRetired(player));
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
            wildcard = new Permission(PERMISSION_WILDCARD, PermissionDefault.OP);
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
                    onPlayer(player, () -> rejectedCritical(player, deliveryId, pendingProof, error),
                            () -> finishInFlight(player, deliveryId));
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
                    onPlayer(player, () -> recoveryFailed(player, generation, error),
                            () -> finishRecoveryRetired(player));
                }
            });
        } catch (RejectedExecutionException error) {
            recoveryFailed(player, generation, error);
        }
    }

    private void finishRecoveryRetired(Player player) {
        recoveryBusy.remove(player);
        locks.unlock(player);
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
            plugin.logger().warning("Kit could not schedule player work for " + player.getName() + ": " + failure.getMessage());
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

    private void onSender(CommandSender sender, Runnable task) {
        if (closed) return;
        if (sender instanceof Player player) {
            onPlayer(player, task, () -> {});
            return;
        }
        plugin.getScheduler().runOnServer(() -> {
            if (!closed) task.run();
        });
    }

    private void send(CommandSender to, Messages message, String... replacements) {
        if (closed) return;
        BukkitServerThread.onOwner(plugin, plugin.getScheduler(), to, () -> {
            if (!closed && (!(to instanceof Player p) || same(p))) {
                plugin.getMessageManager().send(to, message, replacements);
            }
        });
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
