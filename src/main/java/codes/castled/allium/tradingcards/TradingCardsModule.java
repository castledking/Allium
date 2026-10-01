package codes.castled.allium.tradingcards;

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
import codes.castled.allium.tradingcards.item.HeadResolver;
import codes.castled.allium.tradingcards.item.TradingCardData;
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
     * Trades a card in for {@code heads} mob heads.
     *
     * <p>Re-reads the card out of the player's hand rather than trusting the
     * menu's copy: the player may have moved or dropped it between opening the
     * menu and clicking, and a trade that consumed the wrong item would be
     * unrecoverable. Nothing is removed until the card and the head have both
     * been resolved.
     *
     * @param expectedSlot the hand slot the card was opened from
     * @return true when the trade completed
     */
    public boolean tradeCard(Player player, int expectedSlot, ItemStack presented) {
        if (!config.trade().enabled()) {
            return false;
        }
        var hand = player.getInventory().getItem(expectedSlot);
        var current = TradingCardData.read(hand);
        if (current.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>That slot no longer holds a trading card.</red>"));
            return false;
        }
        TradingCardData card = current.get();
        TradeQuote.Result quote = tradeQuote.quote(card);
        if (!quote.isQuoted()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>That card cannot be traded: "
                    + quote.denial().message()));
            return false;
        }
        // The menu's figure is a snapshot; the band may have been reloaded
        // since it opened, so the authoritative quote is the one taken here.
        int heads = quote.quote().heads();

        EntityType mob;
        try {
            mob = EntityType.valueOf(card.mob());
        } catch (IllegalArgumentException e) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Unknown mob '" + card.mob() + "' on this card.</red>"));
            return false;
        }
        var configuredHead = registry.byMob(card.mob()).map(d -> d.head()).orElse(null);
        var head = HeadResolver.resolve(plugin, items, mob, configuredHead);
        if (head.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>No head is configured for a "
                    + card.mob() + " card, so there is nothing to trade for.</red>"));
            return false;
        }
        if (card.bound()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>This card was crafted and cannot be traded.</red>"));
            return false;
        }

        ItemStack payout = head.get().clone();
        payout.setAmount(Math.max(1, heads));
        var overflow = player.getInventory().addItem(payout);
        overflow.values().forEach(rest ->
            player.getWorld().dropItemNaturally(player.getLocation(), rest));

        if (config.trade().consumeCard()) {
            player.getInventory().setItem(expectedSlot, null);
        }
        return true;
    }

    /** Values a card without trading it, for the menu. */
    public TradeQuote.Result quote(TradingCardData card) {
        return tradeQuote.quote(card);
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
