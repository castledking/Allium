package codes.castled.allium.tradingcards;

import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemResolverChain;
import codes.castled.allium.item.ItemResolvers;
import codes.castled.allium.item.NexoItemsGate;
import codes.castled.allium.tradingcards.boost.AuraSkillsBridge;
import codes.castled.allium.tradingcards.boost.BoostCatalog;
import codes.castled.allium.tradingcards.boost.BoostListener;
import codes.castled.allium.tradingcards.boost.CropQualityModifier;
import codes.castled.allium.tradingcards.boost.DisarmListener;
import codes.castled.allium.tradingcards.boost.TokenDropListener;
import codes.castled.allium.tradingcards.boost.BoostMechanism;
import codes.castled.allium.tradingcards.boost.BoostService;
import codes.castled.allium.tradingcards.boost.CardProgression;
import codes.castled.allium.tradingcards.boost.EquippedCardTracker;
import codes.castled.allium.tradingcards.integration.QuestsBridge;
import codes.castled.allium.tradingcards.integration.ReliqueIntegration;
import codes.castled.allium.tradingcards.integration.ReliqueCardWriter;
import codes.castled.allium.tradingcards.merge.MergeRules;
import codes.castled.allium.tradingcards.morph.DisguiseBridge;
import codes.castled.allium.tradingcards.morph.MorphService;
import codes.castled.allium.tradingcards.morph.MorphTargetingListener;
import codes.castled.allium.tradingcards.reroll.RerollPricing;
import codes.castled.allium.tradingcards.reroll.RerollService;
import codes.castled.allium.tradingcards.xp.AuraSkillsAbilityListener;
import codes.castled.allium.tradingcards.xp.CardXpListener;
import codes.castled.allium.tradingcards.xp.EcoJobsBridge;
import codes.castled.allium.tradingcards.xp.FishSizeTracker;
import codes.castled.allium.tradingcards.xp.LiteFishBridge;
import codes.castled.allium.tradingcards.xp.CardXpService;
import codes.castled.allium.tradingcards.xp.XpAntiFarmStore;
import codes.castled.allium.tradingcards.xp.XpConfig;
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
import codes.castled.allium.tradingcards.item.CardLore;
import codes.castled.allium.tradingcards.item.HeldCardNameListener;
import codes.castled.allium.tradingcards.TradingCardsModule;
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
import java.util.Optional;
import java.util.Map;
import java.util.logging.Level;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

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

    private XpConfig xpConfig;
    private boolean questsLive;
    private boolean fishingLive;
    private boolean jobsLive;
    private boolean abilitiesLive;
    private FishSizeTracker fishSizes;
    private DisguiseBridge disguises;
    private HeldCardNameListener heldCardNames;
    private codes.castled.allium.tradingcards.integration.BeastTokensBridge beastTokens;
    private MorphService morphs;
    private XpAntiFarmStore antiFarm;
    private CardXpService cardXp;
    private codes.castled.allium.scheduler.TaskHandle antiFarmSave;
    private RerollService reroll;
    private MergeRules mergeRules;
    private BoostService boosts;
    private EquippedCardTracker equipped;
    private CardProgression progression;
    private boolean reliqueSlotInstalled;

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
        saveDefault(dataFolder, BoostCatalog.FILE);
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
        initBoosts(dataFolder);
        initXp(dataFolder, issues);

        // Morph and token drops, after boosts so both can read the live total.
        initMorph(issues);

        // The action bar names a held card, which is the one place its name can
        // be shown given the tooltip deliberately has none.
        heldCardNames = new HeldCardNameListener(plugin);
        heldCardNames.start();

        // BeastTokens is not on the compile classpath, so the bridge is
        // reflection throughout. Optional in the same way as everything else
        // here: a server without it still drops cards and pays xp.
        var tokensBridge =
            new codes.castled.allium.tradingcards.integration.BeastTokensBridge(logger);
        if (tokensBridge.initialise()) {
            beastTokens = tokensBridge;
            // Token drop chance and token yield are separate boosts because they
            // are separate requests: a player who wants more drops is not asking
            // for bigger ones.
            new TokenDropListener(logger,
                id -> boosts.total(id, BoostMechanism.CARD_TOKEN_DROP_CHANCE),
                id -> boosts.total(id, BoostMechanism.CARD_TOKEN_MULTIPLIER))
                .register(plugin);
        }
        reportIssues(issues);

        enabled = true;
        logger.info("[" + TradingCardsBranding.DISPLAY_NAME + "] Enabled with "
            + registry.size() + " card definition(s) across " + registry.mobCount()
            + " mob(s), " + config.quality().size() + " quality band(s)");
    }

    public void disable() {
        if (!enabled) return;
        enabled = false;
        // Quiesce first: an AuraSkills modifier that outlives its card is
        // written to disk on logout and becomes a permanent invisible buff.
        removeAllBoosts();
        if (morphs != null) {
            morphs.clearAll();
        }
        if (heldCardNames != null) {
            heldCardNames.stop();
            heldCardNames = null;
        }
        // Saved on the way down rather than left to the timer: a cooldown that
        // is lost on shutdown is a gate that is not there.
        if (antiFarm != null) {
            antiFarm.save();
        }
        if (antiFarmSave != null) {
            antiFarmSave.cancel();
            antiFarmSave = null;
        }
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
        if (boosts != null) {
            BoostCatalog.LoadResult reloaded = BoostCatalog.load(
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new File(dataFolder, BoostCatalog.FILE)),
                3, 0.35, 6);
            issues.addAll(reloaded.issues());
            boosts.configure(reloaded, config.levelling().boostPerLevel(),
                scaleMinimum(), scaleMaximum());
            removeAllBoosts();
        }
        return issues;
    }

    /**
     * Removes every boost from every online player.
     *
     * <p>Quiesced before the module goes down, and before any persistence, in
     * the same order the harvest module uses. An AuraSkills modifier that
     * outlives the card is written to disk on logout, so a missed teardown here
     * becomes a permanent buff the player cannot see or get rid of.
     */
    public void removeAllBoosts() {
        if (boosts == null) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            boosts.remove(online);
        }
        if (equipped != null) {
            equipped.clearAll();
        }
    }

    /** Cards currently equipped across the server, for the command. */
    public int equippedCards() {
        return equipped == null ? 0 : equipped.trackedPlayers();
    }

    /** The boost ids a player currently has applied, for inspect. */
    public java.util.List<String> appliedBoosts(java.util.UUID player) {
        return boosts == null ? java.util.List.of() : boosts.appliedTo(player);
    }

    /** The card a player has equipped, or null. */
    public TradingCardData equippedCard(java.util.UUID player) {
        return equipped == null ? null : equipped.card(player);
    }

    /** True when the /reliques card slot is actually installed. */
    public boolean isReliqueSlotInstalled() {
        return reliqueSlotInstalled;
    }

    public BoostService boosts() {
        return boosts;
    }

    public EquippedCardTracker equipped() {
        return equipped;
    }

    public CardProgression progression() {
        return progression;
    }

    // ==================== reroll ====================

    /**
     * Performs a reroll on the card in {@code slot} and writes the result back.
     *
     * <p>Reads the card from the inventory rather than trusting the caller's
     * copy, re-derives the price from the current config, and only then charges.
     * A card is written only after the charge succeeds, and a failed write
     * refunds — so a player is never left having paid for a reroll that did not
     * happen.
     *
     * @return the outcome, for the menu to report
     */
    public RerollService.Result reroll(Player player, TradingCardData presented, int slot) {
        if (reroll == null) {
            return new RerollService.Result(RerollService.Outcome.DISABLED, 0.0,
                presented.signatures(), "Rerolling is unavailable.");
        }
        var held = player.getInventory().getItem(slot);
        var current = TradingCardData.read(held);
        if (current.isEmpty()) {
            return new RerollService.Result(RerollService.Outcome.DISABLED, 0.0,
                presented.signatures(), "That slot no longer holds a trading card.");
        }
        TradingCardData card = current.get();
        RerollService.Result result = reroll.reroll(player, card);
        if (!result.succeeded()) {
            return result;
        }

        // Exactly one of the two lists moves, and the result says which: an
        // unlock adds a signature and leaves the bonuses alone, a bonus roll
        // replaces the bonuses and leaves the signatures alone.
        TradingCardData updated = card
            .withReroll(card.rerolls() + 1, result.signatures())
            .withBonuses(result.rerolledBonuses() ? result.bonuses() : card.bonuses());
        TradingCardData.write(held, updated);

        if (equipped != null && equipped.card(player.getUniqueId()) != null) {
            // A card that is currently equipped has just changed; re-apply so
            // the new signature is live immediately rather than at next equip.
            progression.refreshEquipped(player, updated);
        }
        return result;
    }

    public RerollService reroll() {
        return reroll;
    }

    public MergeRules mergeRules() {
        return mergeRules;
    }

    /**
     * Merges two cards into one of the next tier.
     *
     * <p>Both inputs are removed only after the result has been built, so a
     * merge that cannot produce a card leaves the player's items alone. The
     * result is returned rather than given, because the caller adds it to the
     * inventory and can handle overflow itself.
     *
     * @return the merged card, or null when the merge was refused
     */
    public ItemStack mergeCards(Player player, ItemStack first, ItemStack second,
                                MergeRules rules, MergeRules.Verdict verdict) {
        if (!verdict.allowed() || first == null || second == null) {
            return null;
        }
        TradingCardData a = TradingCardData.read(first).orElse(null);
        TradingCardData b = TradingCardData.read(second).orElse(null);
        if (a == null || b == null) {
            return null;
        }
        var definition = registry.byId(a.cardId());
        if (definition.isEmpty()) {
            // The source card's config has gone; there is no item to mint.
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<red>No card definition exists for '" + a.cardId()
                    + "', so it cannot be merged.</red>"));
            return null;
        }
        var result = factory.create(definition.get(), verdict.resultTier(),
            rules.targetLevel(), a.quality(), rules.mergedSignatures(), a.bound(),
            config, java.util.concurrent.ThreadLocalRandom.current());
        if (result.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<red>The merged card's item does not exist. Is Nexo loaded?</red>"));
            return null;
        }
        // Only now are the inputs consumed.
        removeFromInventory(player, first);
        removeFromInventory(player, second);
        return result.get();
    }

    /**
     * Removes one specific stack from a player's inventory.
     *
     * <p>Matched by identity rather than by slot, because the merge window
     * holds copies and the player may have moved the originals since staging.
     */
    private void removeFromInventory(Player player, ItemStack target) {
        var contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) continue;
            if (!stack.isSimilar(target)) continue;
            if (stack.getAmount() <= target.getAmount()) {
                contents[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - target.getAmount());
                contents[slot] = stack;
            }
            player.getInventory().setStorageContents(contents);
            return;
        }
    }

    /** Re-points the trade quoter at the freshly loaded band table. */
    private void applyTradeConfig() {
        tradeQuote.configure(config::quality, config.trade().enabled(),
            config.trade().requireMintOrBetter());
    }

    /**
     * Loads the boost catalogue and wires the equip/unequip path.
     *
     * <p>The Relique slot is installed first and is what makes the rest
     * meaningful: without it there is no slot to equip a card into, so no
     * boost would ever be applied. Both Relique and AuraSkills are optional —
     * the module loads and cards drop either way, they just do not do anything
     * while equipped, and that is reported once rather than thrown.
     */
    private void initBoosts(File dataFolder) {
        List<ValidationIssue> issues = new ArrayList<>();

        AuraSkillsBridge bridge = new AuraSkillsBridge(logger,
            TradingCardsBranding.NAMESPACE + ":card");
        BoostService service = new BoostService(logger, bridge);
        EquippedCardTracker tracker = new EquippedCardTracker();

        BoostCatalog.LoadResult loaded = BoostCatalog.load(
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new File(dataFolder, BoostCatalog.FILE)),
            3, 0.35, 6);
        issues.addAll(loaded.issues());

        service.configure(loaded, config.levelling().boostPerLevel(),
            scaleMinimum(), scaleMaximum());
        // Bonus rolls are the catalogue's, but the cards that carry them are
        // built by the factory, so the pool is handed over once it is known.
        factory.bonuses(loaded.bonusPool(), loaded.bonusRollCount());
        installCardLore(loaded);

        // Crop quality is Allium's own module, reached through its public API
        // rather than by editing crop files — so the bias applies globally while
        // equipped and vanishes with the card. Registered once, and it reads the
        // live boost total on every harvest so a re-equip needs no re-register.
        var harvestApi = Bukkit.getServicesManager()
            .load(codes.castled.allium.harvest.api.AlliumHarvestApi.class);
        if (harvestApi != null) {
            harvestApi.registerWeightModifier(new CropQualityModifier(id -> service.total(id,
                codes.castled.allium.tradingcards.boost.BoostMechanism.CARD_CROP_QUALITY)));
        }
        this.boosts = service;
        this.equipped = tracker;

        // Reroll and merge need the boost catalogue, the economy, and the card
        // definitions, so they are built after the catalogue has loaded.
        RerollPricing pricing = RerollPricing.from(config.reroll());
        var booster = loaded.boosts().isEmpty()
            ? null
            : new RerollService(loaded, pricing,
                codes.castled.allium.PluginStart.getInstance().getEconomyManager(),
                java.util.concurrent.ThreadLocalRandom.current());
        this.reroll = booster;
        this.mergeRules = new MergeRules(config.merge().enabled(),
            config.merge().requireSameMob(),
            config.levelling().maximumLevel(),
            config.merge().targetLevel());

        CardProgression progress = new CardProgression(service, tracker,
            new CardProgression.ProgressRules(
                config.levelling().maximumLevel(),
                config.levelling().boostPerLevel(),
                config.levelling().boostsPerLevel()),
            logger);
        this.progression = progress;
        Bukkit.getPluginManager().registerEvents(progress, plugin);

        boolean reliquePresent = Bukkit.getPluginManager().isPluginEnabled("Relique");
        reliqueSlotInstalled = ReliqueIntegration.install(plugin, logger);
        if (!reliquePresent) {
            logger.info("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Relique is not installed; cards drop but cannot be equipped");
        } else if (!reliqueSlotInstalled) {
            logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                + "] Relique is installed but the card slot could not be added; "
                + "cards drop but cannot be equipped until "
                + "plugins/Relique/relic/allium/slots/card.json exists");
        } else {
            Bukkit.getPluginManager().registerEvents(new BoostListener(service, tracker), plugin);
            // Registered once, unconditionally: the listener reads the live boost
            // total per hit, so a player with no disarm card costs one map lookup.
            Bukkit.getPluginManager().registerEvents(new DisarmListener(
                id -> service.total(id,
                    codes.castled.allium.tradingcards.boost.BoostMechanism.CARD_DISARM),
                Math::random), plugin);
            if (!bridge.isAvailable()) {
                logger.warning("[" + TradingCardsBranding.DISPLAY_NAME
                    + "] AuraSkills is not loaded; signature boosts will not apply");
            } else {
                logger.info("[" + TradingCardsBranding.DISPLAY_NAME + "] Boosts enabled: "
                    + loaded.boosts().size() + " in the catalogue, "
                    + "card slot " + TradingCardsBranding.RELIQUE_SLOT);
            }
        }
        reportIssues(issues);
    }

    /**
     * Pays the flat quest bounties a card grants.
     *
     * <p>Money and tokens are separate currencies with separate sinks, so they
     * are separate boosts: a 20% token rate and a 20% money rate are not
     * comparable numbers and must not share a slider.
     *
     * <p>Both are read live from the applied boosts rather than snapshotted at
     * equip time, so unequipping mid-quest stops the payout without a
     * re-registration, and both are additive across cards.
     */
    private void payQuestBonuses(Player player) {
        if (boosts == null) return;
        var id = player.getUniqueId();
        double money = boosts.total(id,
            codes.castled.allium.tradingcards.boost.BoostMechanism.CARD_MONEY_ON_QUEST);
        if (money > 0.0) {
            var economy = codes.castled.allium.PluginStart.getInstance().getEconomyManager();
            if (economy != null) {
                economy.deposit(id, java.math.BigDecimal.valueOf(money));
                player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gold>Quest bounty: <white>+" + java.math.BigDecimal.valueOf(money)
                        .setScale(2, java.math.RoundingMode.HALF_UP) + "</white>"));
            }
        }
        double tokens = boosts.total(id,
            codes.castled.allium.tradingcards.boost.BoostMechanism.CARD_TOKENS_ON_QUEST);
        if (tokens > 0.0 && beastTokens != null && beastTokens.addTokens(player, tokens)) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<light_purple>Quest tokens: <white>+" + tokens + "</white>"));
        }
    }

    /**
     * Installs the card lore renderer.
     *
     * <p>Installed on {@link TradingCardData} rather than threaded through each
     * write path, because the paths that write a card are spread across classes
     * this one does not own — the drop factory, the reroll handler, the merge
     * handler and Relique's write-back — and a renderer that one of them forgets
     * to call produces a card with correct state and no lore at all.
     *
     * <p>Boost lines come from the catalogue, so the numbers on a card are the
     * configured ones rather than a second set baked into the renderer.
     */
    private void installCardLore(BoostCatalog.LoadResult catalog) {
        var defs = catalog.boosts();
        double perLevel = config.levelling().boostPerLevel();
        double sMin = scaleMinimum();
        double sMax = scaleMaximum();
        int bonusSlots = config.levelling().loreBonusSlots();
        var quality = config.quality();

        TradingCardData.lore(new CardLore(new CardLore.LoreData(
            quality,
            (id, level) -> {
                var boost = defs.get(id);
                if (boost == null) {
                    // A card naming a boost the catalogue does not define is
                    // shown by id rather than dropped, so the player can see
                    // something is wrong instead of a line silently vanishing.
                    return "<red>" + id + "</red>";
                }
                double value = boost.totalAt(level, perLevel, sMin, sMax);
                String amount = boost.mechanism().isMultiplier()
                    ? "x" + trim(value)
                    : "+" + trim(value);
                return boost.display() + " <white>" + amount + "</white>";
            },
            headsLabel(),
            config.levelling().maximumLevel(),
            bonusSlots,
            loreSeparator(),
            // Read live rather than captured: initBoosts runs before the xp
            // curve is loaded, and a reload replaces it. A fallback keeps the
            // progress row off until the real curve arrives rather than throwing
            // on a card write during startup.
            level -> xpConfig == null ? 0.0 : xpConfig.xpForLevel(level))));
    }

    /** Trims a boost value to something a lore line should carry. */
    private static String trim(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /**
     * The rule between lore sections: dark grey, struck through.
     *
     * <p>The strikethrough is a style rather than a character, so it is applied
     * here instead of being written into the config value — the config holds the
     * glyphs and this owns how they are drawn, which means a separator length
     * change needs no tags edited alongside it.
     */
    private String loreSeparator() {
        String rule = config.levelling().loreSeparator();
        return rule.isBlank()
            ? "" : "<dark_gray><strikethrough>" + rule + "</strikethrough></dark_gray>";
    }

    /** What a card trades for, named for the lore line. */
    private String headsLabel() {
        var source = config.trade().headSource();
        if (source == null) {
            return "spawner heads";
        }
        return switch (source) {
            case SPAWNER_HEADS -> "spawner heads";
            case CARD_ITEM -> "card heads";
        };
    }

    /**
     * Wires the xp sources and the anti-farm state.
     *
     * <p>Every source funnels through one service, so a source added later
     * cannot forget the cooldown and once-each gates. Those gates are the whole
     * reason a mob farm or an advancement re-trigger is not a xp fountain, and
     * the easiest mistake when adding a fifth source is to award directly.
     *
     * <p>The store is saved on a timer rather than on every award: a burst of
     * twenty advancements completing at once would otherwise be twenty disk
     * writes, and the state only has to outlive a restart, not a tick.
     */
    private void initXp(File dataFolder, List<ValidationIssue> issues) {
        XpConfig.LoadResult loaded = XpConfig.load(
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new File(dataFolder, TradingCardsConfig.FILE)));
        issues.addAll(loaded.issues());
        this.xpConfig = loaded.config();
        this.antiFarm = new XpAntiFarmStore(dataFolder);

        if (progression == null) {
            issues.add(ValidationIssue.warning(TradingCardsConfig.FILE, "xp",
                "Card xp is unavailable because the boost stage did not load; "
                    + "sources will not fire"));
            return;
        }
        progression.curve(loaded.config()::xpForLevel);

        CardXpService service = new CardXpService(loaded.config(), antiFarm, progression,
            player -> Optional.ofNullable(equippedCard(player)),
            System::currentTimeMillis);
        service.announcement(config.levelling().announce().message(),
            config.levelling().announce().broadcast());
        // The card's xp bonus is read from the applied boosts, so it only counts
        // while the card is actually equipped and the boosts are live.
        BoostService boostService = boosts;
        if (boostService != null) {
            service.multiplier(id -> boostService.multiplier(id,
                codes.castled.allium.tradingcards.boost.BoostMechanism.CARD_XP_MULTIPLIER, 1.0));
        }
        // Without a writer the level only exists in the tracker, so it is lost
        // the moment the card leaves the slot. Relique owns the item, so the
        // write goes through it; on a server without Relique there is no slot to
        // write to and no warning worth logging every award.
        if (reliqueSlotInstalled) {
            ReliqueCardWriter cardWriter = new ReliqueCardWriter(logger);
            service.writer((id, updated) -> {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    cardWriter.write(player, updated);
                }
            });
        }
        this.cardXp = service;
        Bukkit.getPluginManager().registerEvents(new CardXpListener(this, service), plugin);

        this.antiFarmSave = codes.castled.allium.scheduler.SchedulerAdapter.runAsyncRepeating(
            plugin, () -> {
                if (antiFarm != null) antiFarm.save();
            }, 12000L, 12000L);

        // Quest completions arrive reflectively: Allium must not compile
        // against ExcellentQuests, or the trading card module would fail to load
        // on a server without it. The event is a plain Bukkit event, so
        // registerEvent takes its class as a parameter and that is a supported
        // API rather than a workaround.
        if (xpConfig != null && xpConfig.isEnabled("quest-complete")) {
            QuestsBridge quests = new QuestsBridge(logger, TradingCardsBranding.NAMESPACE);
            if (quests.register(plugin, (player, questId) -> {
                if (questId == null) {
                    return;
                }
                // Keyed by the quest id and the player's daily record, so a
                // quest that can be completed more than once a day pays each
                // time and the same completion is never counted twice.
                service.award(player, "quest-complete", 1.0, null,
                    questId + ":" + java.time.LocalDate.now());
                payQuestBonuses(player);
            })) {
                questsLive = true;
            } else if (Bukkit.getPluginManager().isPluginEnabled("ExcellentQuests")) {
                issues.add(ValidationIssue.warning(TradingCardsConfig.FILE,
                    "xp.sources.quest-complete",
                    "Enabled, but this ExcellentQuests build has no "
                        + "QuestCompleteEvent. Replace it with the patched jar "
                        + "from tradingcardjars/, or the source will never fire."));
            }
        }

        // Fishing. LiteFish is premium, so there is no api artifact and no
        // way to compile against it; the bridge is reflection plus the two PDC
        // keys LiteFish already writes onto the caught stack.
        if (xpConfig.isEnabled("fish-large")) {
            fishSizes = new FishSizeTracker(new FishSizeTracker.Rules(
                loaded.config().source("fish-large").maxSamples(),
                xpConfig.source("fish-large").percentile(),
                xpConfig.source("fish-large").minimumSamples()));
            LiteFishBridge fishing = new LiteFishBridge(logger);
            if (fishing.register(plugin, (player, species, weight) -> {
                if (!fishSizes.record(player.getUniqueId(), species, weight)) {
                    return;
                }
                service.award(player, "fish-large", 1.0, species, null);
            })) {
                fishingLive = true;
            } else if (Bukkit.getPluginManager().isPluginEnabled("LiteFish")) {
                issues.add(ValidationIssue.warning(TradingCardsConfig.FILE,
                    "xp.sources.fish-large",
                    "Enabled, but the LiteFish listener could not register. The "
                        + "plugin may be a version without dev.nekomadev.liteFish."
                        + "api.CatchEvent."));
            }
        }

        // EcoJobs. Also reflective: its API is Kotlin top-level functions with
        // no interface, so there is nothing to implement and no artifact to
        // depend on. Scales the plugin's own amount rather than counting
        // events, because the event fires per counter and not per action.
        if (xpConfig.isEnabled("ecojobs-work")) {
            EcoJobsBridge jobs = new EcoJobsBridge(logger);
            if (jobs.register(plugin, (player, jobId, amount) -> {
                service.award(player, "ecojobs-work", amount, jobId, null);
            })) {
                jobsLive = true;
            } else if (Bukkit.getPluginManager().isPluginEnabled("EcoJobs")) {
                issues.add(ValidationIssue.warning(TradingCardsConfig.FILE,
                    "xp.sources.ecojobs-work",
                    "Enabled, but the EcoJobs listener could not register. The "
                        + "plugin may be a version without "
                        + "com.willfp.ecojobs.api.event.PlayerJobExpGainEvent."));
            }
        }

        // The one source compiled against rather than reflected: Allium already
        // declares AuraSkills' api artifacts for the stat modifiers, and the
        // events live in the bukkit half of the same published module.
        if (xpConfig.isEnabled("auraskills-ability")
            && Bukkit.getPluginManager().isPluginEnabled("AuraSkills")) {
            Bukkit.getPluginManager().registerEvents(
                new AuraSkillsAbilityListener(service, logger), plugin);
            abilitiesLive = true;
        }

        long enabled = loaded.config().sources().values().stream()
            .filter(codes.castled.allium.tradingcards.xp.XpConfig.Source::enabled)
            .count();
        logger.info("[" + TradingCardsBranding.DISPLAY_NAME + "] Card xp enabled: "
            + enabled + " of " + loaded.config().sources().size() + " source(s)");
    }

    public XpConfig xpConfig() {
        return xpConfig;
    }

    public CardXpService cardXp() {
        return cardXp;
    }

    public XpAntiFarmStore antiFarm() {
        return antiFarm;
    }

    /** True when the quest listener is live, for the command's diagnostics. */
    public boolean isQuestListenerLive() {
        return questsLive;
    }

    /** True when the LiteFish listener is live. */
    public boolean isFishingListenerLive() {
        return fishingLive;
    }

    /** True when the EcoJobs listener is live. */
    public boolean isJobsListenerLive() {
        return jobsLive;
    }

    /** True when the AuraSkills ability listener is live. */
    public boolean isAbilitiesListenerLive() {
        return abilitiesLive;
    }

    /** The fishing size tracker, for a menu line showing what "large" means. */
    public FishSizeTracker fishSizes() {
        return fishSizes;
    }

    /**
     * Wires the morph feature.
     *
     * <p>The health check is one shared repeating task over morphed players,
     * not one per player: there is no Bukkit event for "health crossed a
     * threshold", so it has to be polled, and a task per morph would be a task
     * per player for a condition that is usually disabled.
     */
    private void initMorph(List<ValidationIssue> issues) {
        if (!config.morph().enabled()) {
            return;
        }
        DisguiseBridge bridge = new DisguiseBridge(plugin, logger);
        if (!bridge.initialise()) {
            if (Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) {
                issues.add(ValidationIssue.warning(TradingCardsConfig.FILE, "morph",
                    "Enabled, but LibsDisguises is installed and its API does not "
                        + "match. /morph will report itself unavailable."));
            }
            return;
        }
        MorphService.Config morphConfig = new MorphService.Config(
            true,
            config.morph().minimumTier(),
            config.morph().durationSeconds(),
            config.morph().deactivateBelowHealth(),
            config.morph().allowFlight(),
            config.morph().stealthUntilAttacked(),
            config.morph().mobsTargetDisguised());
        MorphService service = new MorphService(bridge, morphConfig);
        this.disguises = bridge;
        this.morphs = service;

        Bukkit.getPluginManager().registerEvents(new MorphTargetingListener(service), plugin);
        // Quit cleanup goes through the module rather than MorphService itself:
        // CardProgression already owns the quit event for the Relique slot, and
        // two handlers racing to clear the same player is how a half-torn-down
        // state happens.
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                service.handleQuit(event.getPlayer());
            }
        }, plugin);

        // One shared second-tick for both expiry rules, and only when one of
        // them is actually on. Neither has a Bukkit event: there is no "health
        // crossed a threshold", and a disconnect must not cancel a timed morph.
        if (morphConfig.durationSeconds() > 0 || morphConfig.deactivateBelowHealth() > 0.0) {
            codes.castled.allium.scheduler.SchedulerAdapter.runRepeatingGlobal(
                plugin, () -> {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        service.tick(online);
                    }
                }, 20L, 20L);
        }
        logger.info("[" + TradingCardsBranding.DISPLAY_NAME + "] Morphing enabled: needs a "
            + morphConfig.minimumTier() + " card, "
            + (bridge.isFree() ? "free LibsDisguises (self view only)" : "premium")
            + ", stealth-until-attacked " + (morphConfig.stealthUntilAttacked() ? "on" : "off"));
    }

    public MorphService morphs() {
        return morphs;
    }

    public DisguiseBridge disguises() {
        return disguises;
    }

    /** True when the morph plugin is wired and the feature is usable. */
    public boolean isMorphAvailable() {
        return morphs != null && disguises != null && disguises.isReady();
    }

    /** The scale clamp, from the catalogue's own scale entry. */
    private double scaleMinimum() {
        return 0.6;
    }

    private double scaleMaximum() {
        return 1.9;
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
