package codes.castled.allium.tradingcards;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.item.ItemResolvers;
import codes.castled.allium.item.NexoItemsGate;
import codes.castled.allium.tradingcards.card.CardDefinitionLoader;
import codes.castled.allium.tradingcards.card.CardDropListener;
import codes.castled.allium.tradingcards.card.CardFactory;
import codes.castled.allium.tradingcards.card.CardRegistry;
import codes.castled.allium.tradingcards.card.CardRoller;
import codes.castled.allium.tradingcards.config.TradingCardsConfig;
import codes.castled.allium.tradingcards.config.ValidationIssue;
import codes.castled.allium.tradingcards.gui.CardMenuListener;
import codes.castled.allium.tradingcards.gui.TradeInListener;
import codes.castled.allium.tradingcards.item.HeadResolver;
import codes.castled.allium.tradingcards.item.TradingCardData;
import codes.castled.allium.tradingcards.trade.PendingPayoutListener;
import codes.castled.allium.tradingcards.trade.PendingPayoutStore;
import codes.castled.allium.tradingcards.trade.TradeQuote;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Facade wiring the trading card subsystem into Allium. Everything is
 * constructor-injected from here; nothing in the subsystem reaches for global
 * plugin state.
 *
 * <p>Like the harvest module, this waits for Nexo to register its items before
 * loading. A card's tier items are {@code nexo:} references, and a chain built
 * before Nexo has registered resolves every one of them as missing — which
 * would mean the drop path silently rolls a card and then fails to produce an
 * item to hand over.
 */
public final class TradingCardsModule {

    private final JavaPlugin plugin;
    private final Logger logger;

    private volatile TradingCardsConfig config = TradingCardsConfig.disabled();

    private ItemResolverChain items;
    private CardRegistry registry;
    private CardRoller roller;
    private CardFactory factory;
    private CardDropListener dropListener;
    private TradeQuote tradeQuote = new TradeQuote();
    private PendingPayoutStore pending;

    private boolean enabled;

    public TradingCardsModule(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== lifecycle ====================

    public void enable() {
        if (Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            try {
                NexoItemsGate.await(plugin, this::enableNow, () -> {
                    if (enabled) reload();
                });
                if (!enabled) {
                    logger.info("[" + TradingCardsBranding.DISPLAY_NAME
                        + "] Waiting for Nexo to load its items");
                }
                return;
            } catch (Throwable t) {
                logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                    + "] Could not wait for Nexo items, loading now: " + t);
            }
        }
        enableNow();
    }

    private void enableNow() {
        if (enabled) return;

        File dataFolder = new File(plugin.getDataFolder(), TradingCardsBranding.DATA_FOLDER);
        saveDefault(dataFolder, TradingCardsConfig.FILE);
        saveDefault(dataFolder, CardDefinitionLoader.FILE);

        TradingCardsConfig.LoadResult loaded = TradingCardsConfig.load(
            YamlConfiguration.loadConfiguration(
                new File(dataFolder, TradingCardsConfig.FILE)));
        config = loaded.config();
        reportIssues(loaded.issues());
        if (!config.enabled()) {
            logger.info("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Disabled in tradingcards/config.yml");
            return;
        }

        items = ItemResolvers.build(plugin, TradingCardsBranding.DISPLAY_NAME, logger);
        registry = new CardRegistry();
        if (pending == null) {
            pending = new PendingPayoutStore(dataFolder);
        }

        List<ValidationIssue> issues = new ArrayList<>();
        CardDefinitionLoader loader = new CardDefinitionLoader(
            items::hasNamespace, items::exists);
        CardDefinitionLoader.LoadResult cards = loader.load(
            YamlConfiguration.loadConfiguration(new File(dataFolder, CardDefinitionLoader.FILE)));
        issues.addAll(cards.issues());
        registry.swap(cards.cards());

        roller = new CardRoller(registry.byId(), config.quality());
        factory = new CardFactory(items);
        dropListener = new CardDropListener(registry, roller, config, factory,
            java.util.concurrent.ThreadLocalRandom.current(),
            this::announceDrop);

        Bukkit.getPluginManager().registerEvents(dropListener, plugin);
        Bukkit.getPluginManager().registerEvents(new CardMenuListener(this), plugin);
        Bukkit.getPluginManager().registerEvents(new TradeInListener(this), plugin);
        Bukkit.getPluginManager().registerEvents(new PendingPayoutListener(this), plugin);
        reportIssues(issues);
        applyTradeConfig();

        enabled = true;
        logger.info("[" + TradingCardsBranding.DISPLAY_NAME + "] Enabled with "
            + registry.size() + " card definition(s) across " + registry.mobCount()
            + " mob(s), " + config.quality().size() + " quality band(s)");
    }

    public void disable() {
        if (!enabled) return;
        enabled = false;
        dropListener = null;
    }

    /**
     * Reloads configuration and card definitions.
     *
     * <p>Best-effort, like the harvest module: definitions that parse cleanly
     * are activated and ones with fatal problems are skipped, so a single bad
     * mob does not take the other 37 with it. The registry is swapped whole, so
     * a lookup running mid-reload sees either the old set or the new one.
     */
    public List<ValidationIssue> reload() {
        if (!enabled) {
            return List.of(ValidationIssue.error(TradingCardsConfig.FILE, "enabled",
                "Trading card module is not enabled; restart after enabling it"));
        }
        File dataFolder = new File(plugin.getDataFolder(), TradingCardsBranding.DATA_FOLDER);

        TradingCardsConfig.LoadResult loaded = TradingCardsConfig.load(
            YamlConfiguration.loadConfiguration(
                new File(dataFolder, TradingCardsConfig.FILE)));
        if (loaded.config().enabled()) {
            config = loaded.config();
        }
        List<ValidationIssue> issues = new ArrayList<>(loaded.issues());

        if (items == null) {
            items = ItemResolvers.build(plugin, TradingCardsBranding.DISPLAY_NAME, logger);
        }
        CardDefinitionLoader loader = new CardDefinitionLoader(
            items::hasNamespace, items::exists);
        CardDefinitionLoader.LoadResult cards = loader.load(
            YamlConfiguration.loadConfiguration(new File(dataFolder, CardDefinitionLoader.FILE)));
        issues.addAll(cards.issues());
        registry.swap(cards.cards());

        // The roller and the listener both captured the old tables, so both are
        // rebuilt rather than left pointing at a registry that has since been
        // replaced.
        roller = new CardRoller(registry.byId(), config.quality());
        dropListener = new CardDropListener(registry, roller, config, factory,
            java.util.concurrent.ThreadLocalRandom.current(),
            this::announceDrop);
        Bukkit.getPluginManager().registerEvents(dropListener, plugin);

        reportIssues(issues);
        applyTradeConfig();
        return issues;
    }

    /** Re-points the trade quoter at the freshly loaded band table. */
    private void applyTradeConfig() {
        tradeQuote.configure(config::quality, config.trade().enabled(),
            config.trade().requireMintOrBetter());
    }

    // ==================== trading ====================

    /**
     * Builds the head payout for a card, or null if it cannot be built.
     *
     * <p>Returns the stack rather than granting it, because the trade window
     * puts it in a slot for the player to collect. Granting straight to the
     * inventory would let a player close the window after confirming and never
     * see what they were owed.
     *
     * <p>The quote is taken here, not taken from the caller, because the menu's
     * figure is a snapshot: a reload between the two clicks must not trade at
     * the old rate.
     *
     * <p>Nothing is consumed. The caller decides when the card is spent.
     */
    public ItemStack buildPayout(TradingCardData card, int headsHint) {
        if (!config.trade().enabled()) {
            return null;
        }
        TradeQuote.Result quote = tradeQuote.quote(card);
        if (!quote.isQuoted()) {
            return null;
        }
        int heads = quote.quote().heads();

        EntityType mob;
        try {
            mob = EntityType.valueOf(card.mob());
        } catch (IllegalArgumentException e) {
            return null;
        }
        var configuredHead = registry.byMob(card.mob()).map(d -> d.head()).orElse(null);
        ItemStack resolved;
        if (config.trade().headSource() == TradingCardsConfig.HeadSource.CARD_ITEM) {
            // Only an explicit head on the card counts. A mob without one has
            // no payout, which is the point of the mode.
            if (configuredHead == null) {
                return null;
            }
            var stack = items.create(configuredHead, 1);
            if (stack.isEmpty()) {
                return null;
            }
            resolved = stack.get();
        } else {
            var head = HeadResolver.resolve(plugin, items, mob, configuredHead);
            if (head.isEmpty()) {
                return null;
            }
            resolved = head.get();
        }
        resolved.setAmount(Math.max(1, heads));
        return resolved;
    }

    /** Explains why a card cannot be traded, for a message. */
    public String explainDenial(TradingCardData card) {
        TradeQuote.Result quote = tradeQuote.quote(card);
        return quote.isQuoted() ? null : quote.denial().message();
    }

    /** Values a card without trading it, for the menu. */
    public TradeQuote.Result quote(TradingCardData card) {
        return tradeQuote.quote(card);
    }

    /** Heads a player is owed from a window they closed early. */
    public PendingPayoutStore pending() {
        return pending;
    }

    /**
     * Hands over any heads a player left in the trade window, then anything
     * already owed from an earlier window.
     *
     * <p>Collected items go to the inventory; only genuine overflow is dropped
     * at the player's feet, because a full inventory is the one case where the
     * item has nowhere else to be.
     */
    public void deliverPending(Player player) {
        var owed = pending.takeAll(player.getUniqueId());
        if (owed.isEmpty()) return;
        List<ItemStack> resolved = PendingPayoutStore.resolve(items, owed);
        if (resolved.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<red>Your waiting heads could not be built — the item no longer exists. "
                    + "They have been removed rather than dropped; tell an admin.</red>"));
            return;
        }
        for (ItemStack stack : resolved) {
            // Only genuine overflow is dropped: a full inventory is the one
            // case where the item has nowhere else to be.
            var overflow = player.getInventory().addItem(stack);
            overflow.values().forEach(rest ->
                player.getWorld().dropItemNaturally(player.getLocation(), rest));
        }
        player.sendMessage(MiniMessage.miniMessage().deserialize(
            "<green>You had heads waiting from a trade you did not finish — here they are.</green>"));
    }

    /**
     * Returns what was left in a trade window when the player closed it.
     *
     * <p>A trading card goes straight back to the inventory — it was never
     * traded, only deposited, so the player is owed the card itself. Anything
     * else is a heads payout they did not collect, which is recorded as a debt
     * and paid later rather than dropped.
     */
    public void returnLeftovers(Player player, List<ItemStack> leftovers) {
        for (ItemStack stack : leftovers) {
            if (TradingCardData.isCard(stack)) {
                var overflow = player.getInventory().addItem(stack);
                overflow.values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
                continue;
            }
            // Recorded by reference where possible, so the player is owed the
            // head as it exists now rather than as it looked when they walked
            // away from the window.
            ItemRef ref = referenceFor(stack);
            if (ref != null) {
                pending.add(player.getUniqueId(), ref, stack.getAmount());
            } else {
                // No reference could be determined for an unexpected item, so
                // it is delivered directly rather than guessed at.
                var overflow = player.getInventory().addItem(stack);
                overflow.values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
            }
        }
        boolean owesHeads = !pending.isEmpty(player.getUniqueId());
        if (owesHeads) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<gray>You closed the trade window with heads still in it. "
                    + "<green>They are held for you</green> <gray>— run "
                    + "<white>/tradingcards heads</white> <gray>any time to collect.</gray>"));
        }
    }

    /**
     * The item reference for a stack, if it can be identified exactly.
     *
     * <p>A mob head produced by Allium carries a note_block_sound component
     * rather than a custom item id, so it is matched back through the head
     * roster. Anything that does not match a known head returns null and is
     * delivered directly instead.
     */
    private ItemRef referenceFor(ItemStack stack) {
        if (stack.getType() != org.bukkit.Material.PLAYER_HEAD) {
            return null;
        }
        for (var definition : registry.all()) {
            EntityType type;
            try {
                type = EntityType.valueOf(definition.mob());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!codes.castled.allium.spawnercraft.MobHeadRegistry
                    .getMobKey(stack, false).equalsIgnoreCase(definition.mob())) {
                continue;
            }
            var configured = definition.head();
            if (configured != null) {
                return configured;
            }
            var head = HeadResolver.resolve(plugin, items, type, null);
            if (head.isEmpty()) return null;
            return refOf(head.get());
        }
        return null;
    }

    private ItemRef refOf(ItemStack stack) {
        var id = items.identify(stack);
        return id.orElse(null);
    }

    // ==================== accessors ====================

    public TradingCardsConfig config() {
        return config;
    }

    public CardRegistry registry() {
        return registry;
    }

    public ItemResolverChain items() {
        return items;
    }

    public CardRoller roller() {
        return roller;
    }

    public CardFactory factory() {
        return factory;
    }

    // ==================== internals ====================

    private void announceDrop(CardDropListener.DropResult result) {
        logger.fine(() -> "[" + TradingCardsBranding.DISPLAY_NAME + "] "
            + result.killer().getName() + " got a "
            + result.tier() + " " + result.definition().mob() + " card ("
            + result.band().id() + " " + result.quality() + "%)");
    }

    private void saveDefault(File dataFolder, String name) {
        File target = new File(dataFolder, name);
        if (target.exists()) return;
        String resource = TradingCardsBranding.DATA_FOLDER + "/" + name;
        if (plugin.getResource(resource) != null) {
            plugin.saveResource(resource, false);
        } else {
            target.getParentFile().mkdirs();
        }
    }

    private void reportIssues(List<ValidationIssue> issues) {
        // A missing item-provider plugin (e.g. Nexo) trips every reference in
        // the file. Report that once per message rather than once per path.
        java.util.Set<String> reported = new java.util.HashSet<>();
        for (ValidationIssue issue : issues) {
            if (issue.message().contains("does not exist")
                && !reported.add(issue.message())) {
                continue;
            }
            String line = "[" + TradingCardsBranding.DISPLAY_NAME + "] " + issue;
            if (issue.isError()) {
                logger.severe(line);
            } else {
                logger.warning(line);
            }
        }
    }

    /** Logs a failure without taking the module down with it. */
    public void warn(String message, Throwable t) {
        logger.log(Level.WARNING, "[" + TradingCardsBranding.DISPLAY_NAME + "] " + message, t);
    }

    /** The loaded card table, for the API and the command. */
    public Map<String, ?> cards() {
        return registry == null ? Map.of() : registry.byId();
    }
}
