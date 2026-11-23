package codes.castled.curios;

import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AlliumAddon;
import codes.castled.allium.api.ItemDefinition;
import codes.castled.allium.api.ItemIdentity;
import codes.castled.allium.command.spec.*;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class CuriosPlugin extends AlliumAddon implements Listener {
    private final Map<String, String> frames = new LinkedHashMap<>();
    private final List<org.bukkit.permissions.Permission> registeredFramePermissions = new ArrayList<>();
    private static final List<String> ICONS = List.of(
            "charm", "head", "chest", "belt", "ring", "card", "prev", "next", "up", "down",
            "card_menu", "card_locked", "card_prev", "card_next", "card_back", "blank");
    private AddonConnection core;
    private Catalog catalog;
    private CardRules cardRules;
    private CardGuiManager cardGui;
    private CuriosRecoveryStore recovery;
    private GiveCommand give;
    private HeldCardBar heldCardBar;
    private AutoCloseable framePackets;
    private final Map<NamespacedKey, Catalog.Entry> recipes = new HashMap<>();
    private final Map<UUID, Map<Catalog.Slot, ItemStack>> loadouts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> importing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private java.util.concurrent.ThreadPoolExecutor importIo;

    @Override
    protected void onAddonEnable(AddonConnection connection) {
        // Placement in plugins/Allium/addons/ is Allium's to enforce (allium-addon-dir in plugin.yml):
        // it refuses Curios elsewhere before this runs, and its check holds on Paper 1.20.5+, which
        // loads a remapped copy of the jar from plugins/.paper-remapped/.
        core = connection;
        saveDefaultConfig();
        if (ConfigUpgrade.apply(getConfig())) saveUpgradedConfig();
        catalog = new Catalog(getConfig());
        cardRules = new CardRules(getConfig());
        recovery = new CuriosRecoveryStore(this);
        cardGui = new CardGuiManager(this);
        give = new GiveCommand(catalog);
        var choices = Objects.requireNonNull(getConfig().getConfigurationSection("frames"), "frames");
        for (String name : choices.getKeys(false)) {
            if (!name.matches("[a-z0-9_]{1,48}")) throw new IllegalArgumentException("Invalid frame: " + name);
            String permission = choices.getString(name, "");
            frames.put(name, permission);
            if (!permission.isEmpty() && getServer().getPluginManager().getPermission(permission) == null) {
                var node = new org.bukkit.permissions.Permission(permission, org.bukkit.permissions.PermissionDefault.FALSE);
                getServer().getPluginManager().addPermission(node);
                registeredFramePermissions.add(node);
            }
        }
        importIo = core.scheduler().boundedPool("Curios-Import", 1, 32);
        for (Catalog.Entry entry : catalog.entries.values()) {
            Map<String, String> defaults = new LinkedHashMap<>();
            defaults.put("level", "0");
            defaults.put("xp", "0");
            defaults.put("frame", entry.frame());
            defaults.put("bound", "false");
            if (!entry.legacyCard().isEmpty()) {
                defaults.put("quality", "100");
                defaults.put("signatures", "luck,strength,speed");
                defaults.put("bonuses", ",,,,");
            }
            core.items().define(new ItemDefinition(entry.id(), Material.PAPER, "&f" + entry.name(), entry.model(),
                    defaults, true));
        }
        for (Catalog.Entry entry : catalog.entries.values()) registerRecipe(entry);
        register("curios", List.of("relics", "reliques", "tradingcards", "cards"),
                "/curios [menu|inspect|merge|trade|claim|frame|give|import]",
                List.of("menu", "inspect", "merge", "trade", "claim", "frame", "give", "import"),
                this::command, this::completeCurios);
        register("frame", List.of(), "/frame [list|apply] [style]", List.of("list", "apply"),
                (sender, label, args) -> {
                    if (sender instanceof Player player) frame(player, args);
                    else sender.sendMessage("This command requires a player.");
                }, this::completeFrame);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(cardGui, this);
        getServer().getPluginManager().registerEvents(new RelicAbilities(this, core.scheduler()), this);
        heldCardBar = new HeldCardBar(this);
        getServer().getPluginManager().registerEvents(heldCardBar, this);
        heldCardBar.start();
        try {
            getServer().getServicesManager().register(codes.castled.allium.api.ChatHoverFrame.class,
                    new HoverFrames(this), this, org.bukkit.plugin.ServicePriority.Normal);
        } catch (NoClassDefFoundError olderAllium) {
            // Core versions before 1.6 have no chat hover service.
        }
        if (texturedMenu() && getServer().getPluginManager().isPluginEnabled("packetevents")) {
            try {
                framePackets = new FrameItemPackets(this);
            } catch (LinkageError | RuntimeException unavailable) {
                getLogger().warning("Item frame packets are unavailable: " + unavailable);
            }
        }
        getServer().getServicesManager().register(CurioExperience.class, this::awardEquipped,
                this, org.bukkit.plugin.ServicePriority.Normal);
        getServer().getServicesManager().register(CardCrafting.class, new CardCrafting() {
            @Override
            public boolean supports(String mob) {
                return catalog.entries.values().stream().anyMatch(entry -> entry.legacyCard().equalsIgnoreCase(mob));
            }

            @Override
            public OptionalInt previewModel(String mob, Map<String, Double> weights) {
                return choices(mob, weights).keySet().stream()
                        .min(Comparator.comparing(entry -> CardRules.Tier.parse(entry.legacyTier())))
                        .map(entry -> OptionalInt.of(entry.model())).orElseGet(OptionalInt::empty);
            }

            @Override
            public Optional<ItemStack> craft(String mob, Map<String, Double> weights) {
                Catalog.Entry entry = new Catalog.Pool(mob, 1, choices(mob, weights)).roll(ThreadLocalRandom.current().nextDouble());
                return entry == null ? Optional.empty() : Optional.ofNullable(create(entry, Map.of()));
            }

            private Map<Catalog.Entry, Double> choices(String mob, Map<String, Double> weights) {
                var choices = new LinkedHashMap<Catalog.Entry, Double>();
                if (weights.isEmpty()) {
                    for (var pool : catalog.pools) {
                        if (pool.mob().equalsIgnoreCase(mob)) choices.putAll(pool.weights());
                    }
                }
                if (choices.isEmpty()) {
                    for (var entry : catalog.entries.values()) {
                        if (!entry.legacyCard().equalsIgnoreCase(mob)) continue;
                        double weight = weights.isEmpty() ? 1 : weights.getOrDefault(entry.legacyTier(), 0.0);
                        if (weight > 0 && Double.isFinite(weight)) choices.put(entry, weight);
                    }
                }
                return choices;
            }
        }, this, org.bukkit.plugin.ServicePriority.Normal);
        for (Player online : getServer().getOnlinePlayers()) core.onOwner(online, () -> {
            refreshAttributes(online);
            recoverPlayerState(online, false);
        });
    }

    private void saveUpgradedConfig() {
        var file = new java.io.File(getDataFolder(), "config.yml").toPath();
        var backup = file.resolveSibling("config.yml.before-v" + ConfigUpgrade.VERSION);
        try {
            if (java.nio.file.Files.isRegularFile(file) && !java.nio.file.Files.exists(backup)) {
                java.nio.file.Files.copy(file, backup);
            }
        } catch (java.io.IOException e) {
            getLogger().warning("Could not back up config.yml before upgrading it: " + e.getMessage());
        }
        saveConfig();
        getLogger().info("Upgraded config.yml to version " + ConfigUpgrade.VERSION + " (the old file is "
                + backup.getFileName() + "): added missing sections and turned textured-menu on.");
    }

    @FunctionalInterface
    private interface SenderCommand { void run(CommandSender sender, String label, String[] args); }

    private void register(String label, List<String> aliases, String usage, List<String> actions,
                          SenderCommand command, codes.castled.allium.api.AddonCommands.Completer completer) {
        core.commands().register(new CommandSpec(label, "curios", "curios", aliases, "Curios equipment and progression",
                usage, usage, "curios.use", null,
                List.of(CommandArgSpec.optional("action", null, SuggestionSpec.literals(actions, null))),
                List.of(), CommandMessageKeys.ofOutcomes(Map.of())),
                ctx -> command.run(ctx.sender(), ctx.label(), ctx.args()), completer);
    }

    /** An online player {@code viewer} can see, by exact name, or null. */
    private Player visiblePlayer(CommandSender viewer, String name) {
        Player player = getServer().getPlayerExact(name);
        return player != null && (!(viewer instanceof Player p) || p.canSee(player)) ? player : null;
    }

    AddonConnection core() { return core; }
    Catalog catalog() { return catalog; }
    CardRules cardRules() { return cardRules; }
    CuriosRecoveryStore recovery() { return recovery; }
    boolean texturedMenu() { return getConfig().getBoolean("textured-menu", true); }
    int iconModel(String id) { int idx = ICONS.indexOf(id); return idx >= 0 ? 2679900 + idx : 2679900; }
    boolean isEquipmentMenu(InventoryHolder holder) { return holder instanceof Menu; }

    @Override
    protected void onAddonDisable() {
        if (framePackets != null) {
            try {
                framePackets.close();
            } catch (Exception unavailable) {
                getLogger().fine("Could not unregister item frame packets: " + unavailable.getMessage());
            }
        }
        if (cardGui != null) cardGui.closeAll();
        if (heldCardBar != null) heldCardBar.stop();
        for (Player player : getServer().getOnlinePlayers()) {
            if (core.isFolia() && !core.ownsEntity(player)) continue;
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) player.closeInventory();
            clearAttributes(player);
        }
        getServer().getServicesManager().unregisterAll(this);
        if (importIo != null) importIo.shutdownNow();
        loadouts.clear();
        importing.clear();
        recipes.keySet().forEach(getServer()::removeRecipe);
        recipes.clear();
        registeredFramePermissions.forEach(getServer().getPluginManager()::removePermission);
        registeredFramePermissions.clear();
    }

    private List<String> completeCurios(codes.castled.allium.api.AddonCommands.Context ctx) {
        var sender = ctx.sender();
        String[] args = ctx.args();
        boolean admin = sender.hasPermission("curios.admin");
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> actions = new ArrayList<>(List.of("menu", "inspect", "merge", "trade", "claim", "frame"));
            if (admin) {
                actions.add("give");
                actions.add("import");
            }
            return actions.stream().filter(a -> a.startsWith(prefix)).toList();
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("give".equals(action) && admin) {
            List<String> players = new ArrayList<>();
            for (Player online : getServer().getOnlinePlayers()) {
                if (!(sender instanceof Player p) || p.canSee(online)) players.add(online.getName());
            }
            return give.complete(Arrays.copyOfRange(args, 1, args.length), GiveCommand.Scope.of(ctx.label()),
                    name -> visiblePlayer(sender, name) != null, players);
        } else if ("frame".equals(action) && args.length == 2) {
            List<String> out = new ArrayList<>();
            if ("list".startsWith(prefix)) out.add("list");
            out.addAll(completeFrameStyles(sender, prefix));
            return out;
        } else if ("import".equals(action) && admin && args.length == 2) {
            List<String> out = new ArrayList<>();
            if ("held".startsWith(prefix)) out.add("held");
            for (Player online : getServer().getOnlinePlayers()) {
                if ((!(sender instanceof Player p) || p.canSee(online))
                        && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(online.getName());
                }
            }
            return out;
        }
        return List.of();
    }

    private List<String> completeFrame(codes.castled.allium.api.AddonCommands.Context ctx) {
        var sender = ctx.sender();
        String[] args = ctx.args();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "apply").stream().filter(a -> a.startsWith(prefix)).toList();
        }
        if (args.length == 2 && "apply".equalsIgnoreCase(args[0])) {
            return completeFrameStyles(sender, args[1].toLowerCase(Locale.ROOT));
        }
        return List.of();
    }

    private List<String> completeFrameStyles(CommandSender sender, String prefix) {
        var styles = new ArrayList<>(List.of("auto", "off"));
        for (String name : frames.keySet()) {
            if (!(sender instanceof Player player) || canUseFrame(player, name)) styles.add(name);
        }
        return styles.stream().filter(name -> name.startsWith(prefix)).toList();
    }

    private void command(CommandSender sender, String label, String[] args) {
        boolean previous = cardsReply.get();
        cardsReply.set(GiveCommand.Scope.of(label) == GiveCommand.Scope.CARDS);
        try {
            dispatch(sender, label, args);
        } finally {
            cardsReply.set(previous);
        }
    }

    private void dispatch(CommandSender sender, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) { handleGive(sender, label, args); return; }
        if (!(sender instanceof Player player)) { sender.sendMessage("This command requires a player."); return; }
        if (args.length == 0) { open(player, 0); return; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("frame")) {
            // Both legacy forms operate on the held item.
            if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("list")) frame(player, new String[]{"list"});
            else if (args.length == 2) frame(player, new String[]{"apply", args[1].toLowerCase(Locale.ROOT)});
            else tell(player, "Usage: /" + label + " frame [list|<style>]");
            return;
        }
        if (action.equals("inspect")) {
            if (args.length != 1) { tell(player, "Usage: /curios inspect"); return; }
            describe(player, player.getInventory().getItemInMainHand());
            return;
        }
        if (action.equals("menu")) {
            if (args.length != 1) { tell(player, "Usage: /curios menu"); return; }
            cardGui.openHome(player, new CardGuiManager.InventorySource(player.getInventory().getHeldItemSlot()));
            return;
        }
        if (!editable(player)) return;
        switch (action) {
            case "merge" -> {
                if (args.length != 1) { tell(player, "Usage: /curios merge"); return; }
                merge(player);
            }
            case "trade" -> {
                if (args.length != 1) { tell(player, "Usage: /curios trade"); return; }
                trade(player);
            }
            case "claim" -> {
                if (args.length != 1) { tell(player, "Usage: /curios claim"); return; }
                recoverPlayerState(player, true);
            }
            case "import" -> {
                if (!player.hasPermission("curios.admin")) { tell(player, "You do not have permission."); return; }
                if (args.length > 2) { tell(player, "Usage: /curios import [held|<player>]"); return; }
                if (args.length == 2 && args[1].equalsIgnoreCase("held")) importHeld(player);
                else {
                    Player target = args.length == 1 ? player : getServer().getPlayerExact(args[1]);
                    if (target == null || !player.canSee(target)) tell(player, "Player is unavailable.");
                    else importLoadout(player, target);
                }
            }
            default -> tell(player, "Usage: /" + label + " [menu|inspect|merge|trade|claim|frame|give|import]");
        }
    }

    private void handleGive(CommandSender actor, String label, String[] args) {
        if (!actor.hasPermission("curios.admin")) { tell(actor, "You do not have permission."); return; }
        GiveCommand.Scope scope = GiveCommand.Scope.of(label);
        GiveCommand.Result result = give.parse(label, Arrays.copyOfRange(args, 1, args.length), scope,
                name -> visiblePlayer(actor, name) != null);
        if (result.error() != null) { tell(actor, result.error()); return; }
        GiveCommand.Request request = result.request();
        Player target = request.target() != null ? visiblePlayer(actor, request.target())
                : actor instanceof Player self ? self : null;
        if (target == null) { tell(actor, "Name a player: " + GiveCommand.usage(label, scope)); return; }
        Catalog.Entry entry = request.entry();
        ItemStack item = create(entry, request.state());
        if (item == null) { tell(actor, "The curio could not be created."); return; }
        boolean cardsMode = cardsReply.get();
        String what = entry.legacyCard().isEmpty()
                ? entry.name() + " (level " + request.state().get("level") + ")"
                : CardRules.titleCase(entry.legacyTier()) + " " + CardRules.titleCase(entry.legacyCard()) + " card (level "
                        + request.state().get("level") + ", quality " + request.state().get("quality") + "%)";
        core.onOwner(target, () -> {
            if (!target.isOnline() || !core.canEditInventory(target)) {
                tellWithMode(actor, cardsMode, (target == actor ? "Your" : target.getName() + "'s") + " inventory is unavailable right now.");
                return;
            }
            if (!insert(target, item)) {
                tellWithMode(actor, cardsMode, (target == actor ? "Your" : target.getName() + "'s") + " inventory is full.");
                return;
            }
            if (!entry.legacyCard().isEmpty()) {
                tellCards(actor, "Gave " + what + (target == actor ? "" : " to " + target.getName()) + ".");
            } else {
                tellWithMode(actor, cardsMode, "Gave " + what + (target == actor ? "" : " to " + target.getName()) + ".");
            }
        });
    }

    private void frame(Player player, String[] args) {
        if (args.length == 0 || args.length == 1 && args[0].equalsIgnoreCase("list")) {
            tell(player, "Frames: " + String.join(", ", frames.keySet().stream()
                    .filter(name -> canUseFrame(player, name)).toList()));
            return;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("apply")) {
            tell(player, "Usage: /frame [list|apply <style|auto|off>]");
            return;
        }
        String style = args[1].toLowerCase(Locale.ROOT).replace('-', '_');
        if (!style.equals("auto") && !style.equals("off") && !canUseFrame(player, style)) {
            tell(player, "You cannot use that frame.");
            return;
        }
        if (!editable(player)) return;
        ItemStack source = player.getInventory().getItemInMainHand();
        if (source.getType().isAir()) { tell(player, "Hold the item you want to frame."); return; }
        if (entry(source) != null) {
            if (style.equals("off")) { tell(player, "This curio has its own frame."); return; }
            ItemStack styled = changed(source, Map.of("frame", style.equals("auto") ? automaticFrame(player) : style));
            if (styled == null) return;
            player.getInventory().setItemInMainHand(styled);
        } else {
            var meta = source.getItemMeta();
            if (meta == null || !ItemFrameMarker.apply(meta, style)) { tell(player, "That item has no frame."); return; }
            if (!source.setItemMeta(meta)) return;
            player.getInventory().setItemInMainHand(source);
        }
        tell(player, style.equals("off") ? "Frame removed." : "Applied the " + style + " frame.");
    }

    String automaticFrame(Player player) {
        for (var frame : frames.entrySet()) {
            if (!frame.getValue().isEmpty() && canUseFrame(player, frame.getKey())) return frame.getKey();
        }
        return "simple";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void refreshFrames(org.bukkit.event.player.PlayerGameModeChangeEvent event) {
        if (framePackets != null && (event.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE
                || event.getNewGameMode() == org.bukkit.GameMode.CREATIVE)) {
            Player player = event.getPlayer();
            core.runForEntity(player, () -> {
                if (player.isOnline()) player.updateInventory();
            }, () -> {}, 50L);
        }
    }

    private boolean canUseFrame(Player player, String name) {
        String permission = frames.get(name);
        return permission != null && (permission.isEmpty() || player.hasPermission(permission));
    }

    private void describe(Player player, ItemStack stack) {
        Catalog.Entry entry = entry(stack);
        if (entry == null) { tell(player, "Hold one valid curio."); return; }
        ItemIdentity id = identity(stack).orElseThrow();
        Progression progress = progress(id);
        if (!entry.legacyCard().isEmpty()) {
            int quality = CardRules.parseIntSafe(id.state().get("quality"), 100);
            CardRules.QualityBand band = cardRules.bandFor(quality);
            tellCards(player, entry.name() + " · level " + progress.level() + " · " + formatXp(progress.xp())
                    + " XP · " + band.label() + " [" + quality + "%]");
        } else {
            tell(player, entry.name() + " · level " + progress.level() + " · " + formatXp(progress.xp()) + " XP");
        }
    }

    private void merge(Player player) {
        var inv = player.getInventory();
        ItemStack target = inv.getItemInMainHand();
        ItemStack donor = inv.getItemInOffHand();
        Catalog.Entry entry = entry(target);
        if (entry == null || entry != entry(donor) || target.equals(donor)) {
            tell(player, "Hold two separate copies of the same curio, one in each hand.");
            return;
        }
        var mainState = identity(target).orElseThrow();
        var donorState = identity(donor).orElseThrow();
        Progression before = progress(mainState);
        Progression donorProgress = progress(donorState);

        // SF tier-up forge when two max-level trading cards are held
        if (!entry.legacyCard().isEmpty() && before.level() >= entry.maxLevel() && donorProgress.level() >= entry.maxLevel()) {
            int q1 = CardRules.parseIntSafe(mainState.state().get("quality"), 100);
            int q2 = CardRules.parseIntSafe(donorState.state().get("quality"), 100);
            CardRules.MergeVerdict verdict = cardRules.checkMerge(entry, before.level(), q1, entry, donorProgress.level(), q2, catalog.entries);
            if (!verdict.allowed()) {
                tellCards(player, verdict.message());
                return;
            }
            Catalog.Entry nextEntry = catalog.entries.get(verdict.resultId());
            boolean bound = Boolean.parseBoolean(mainState.state().get("bound")) || Boolean.parseBoolean(donorState.state().get("bound"));
            ItemStack forged = create(nextEntry, Map.of(
                    "level", "0", "xp", "0",
                    "quality", Integer.toString(verdict.mergedQuality()),
                    "bound", Boolean.toString(bound),
                    "frame", nextEntry.frame()));
            if (forged == null) { tellCards(player, "The merge could not be saved."); return; }
            inv.setItemInOffHand(null);
            inv.setItemInMainHand(forged);
            tellCards(player, "Merged into " + nextEntry.name() + " (quality " + verdict.mergedQuality() + "%).");
            return;
        }

        Progression after = entry.legacyCard().isEmpty()
                ? before.merge(donorProgress, entry.maxLevel(), entry.step())
                : cardRules.awardCardXp(before.level(), before.xp(),
                        donorProgress.xp() + donorProgress.level() * cardRules.xpForLevel(Math.max(0, donorProgress.level() - 1)) + cardRules.xpForLevel(0),
                        entry.maxLevel());
        if (after.equals(before)) { tell(player, entry, "This curio cannot gain more XP."); return; }
        var changes = new HashMap<>(progressState(after));
        if (Boolean.parseBoolean(donorState.state().get("bound"))) changes.put("bound", "true");
        ItemStack merged = changed(target, changes);
        if (merged == null) { tell(player, entry, "The merge could not be saved."); return; }
        inv.setItemInOffHand(null);
        inv.setItemInMainHand(merged);
        tell(player, entry, "Merged into " + entry.name() + " at level " + after.level() + ".");
    }

    int tradePayoutAmount(Catalog.Entry entry, ItemIdentity id) {
        if (!entry.legacyCard().isEmpty()) {
            int quality = CardRules.parseIntSafe(id.state().get("quality"), 100);
            return Math.max(1, cardRules.bandFor(quality).heads());
        }
        return entry.tradeAmount();
    }

    /**
     * What trading in this item pays. With {@code card-menu.trade-in-pays: heads} (the default) a
     * card pays its mob's head, as legacy did: SpawnerCraft's head when SpawnerCraft provides one,
     * else the vanilla head (zombie, skeleton, ...). Otherwise, and for mobs with neither, the
     * entry's {@code trade-item}. Empty when there is no payout.
     */
    Optional<ItemStack> tradePayout(Catalog.Entry entry, ItemIdentity id) {
        int amount = Math.max(1, tradePayoutAmount(entry, id));
        String mob = entry.legacyCard().toLowerCase(Locale.ROOT);
        if (!mob.isEmpty() && "heads".equalsIgnoreCase(getConfig().getString("card-menu.trade-in-pays", "heads"))) {
            Optional<ItemStack> head = spawnerCraftHead(mob, amount).or(() -> vanillaHead(mob, amount));
            if (head.isPresent()) return head;
        }
        if (entry.trade() == null) return Optional.empty();
        return Optional.of(new ItemStack(entry.trade(), Math.min(entry.trade().getMaxStackSize(), amount)));
    }

    private Optional<ItemStack> spawnerCraftHead(String mob, int amount) {
        CardTradeHeads heads = getServer().getServicesManager().load(CardTradeHeads.class);
        if (heads == null) return Optional.empty();
        try {
            return heads.head(mob, amount).filter(stack -> !stack.getType().isAir())
                    .map(stack -> { stack.setAmount(Math.min(stack.getMaxStackSize(), amount)); return stack; });
        } catch (RuntimeException | LinkageError failed) {
            getLogger().warning("The trade-in head service failed for " + mob + ": " + failed);
            return Optional.empty();
        }
    }

    private static Optional<ItemStack> vanillaHead(String mob, int amount) {
        String name = mob.toUpperCase(Locale.ROOT);
        Material head = Material.matchMaterial(name + "_HEAD");
        if (head == null) head = Material.matchMaterial(name + "_SKULL");
        return head == null || !head.isItem() ? Optional.empty() : Optional.of(new ItemStack(head, Math.min(head.getMaxStackSize(), amount)));
    }

    /** A payout's name for messages and the menu: its display name, or its item's name. */
    static String payoutName(ItemStack payout) {
        var meta = payout.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return org.bukkit.ChatColor.stripColor(meta.getDisplayName());
        return CardRules.titleCase(payout.getType().name());
    }

    private void trade(Player player) {
        var inv = player.getInventory();
        ItemStack source = inv.getItemInMainHand();
        Catalog.Entry entry = entry(source);
        if (entry == null || Boolean.parseBoolean(identity(source).orElseThrow().state().get("bound"))) {
            tell(player, "This item has no available trade-in.");
            return;
        }
        Optional<ItemStack> payout = tradePayout(entry, identity(source).orElseThrow());
        if (payout.isEmpty()) {
            tell(player, "This item has no available trade-in.");
            return;
        }
        inv.setItemInMainHand(payout.get());
        tell(player, entry, "Traded in " + entry.name() + " for " + payout.get().getAmount() + " " + payoutName(payout.get()) + ".");
    }

    Optional<ItemIdentity> identity(ItemStack stack) {
        if (stack == null || stack.getAmount() != 1) return Optional.empty();
        return core.items().identify(stack)
                .filter(item -> item.behavior().filter(id -> id.startsWith("curios:")).isPresent());
    }

    Catalog.Entry entry(ItemStack stack) {
        return identity(stack).map(item -> catalog.entries.get(item.behavior().orElseThrow().substring("curios:".length()))).orElse(null);
    }

    ItemStack create(Catalog.Entry entry, Map<String, String> state) {
        var created = core.items().create("allium:" + entry.id(), 1).stack().orElse(null);
        if (created == null) return null;
        Map<String, String> merged = new LinkedHashMap<>();
        if (!entry.legacyCard().isEmpty()) {
            if (!state.containsKey("quality")) {
                merged.put("quality", Integer.toString(cardRules.rollQuality(ThreadLocalRandom.current())));
            }
            if (!state.containsKey("signatures")) {
                merged.put("signatures", "luck,strength,speed");
            }
            if (!state.containsKey("bonuses")) {
                merged.put("bonuses", ",,,,");
            }
        }
        merged.putAll(state);
        return changed(created, merged);
    }

    ItemStack changed(ItemStack source, Map<String, String> state) {
        Catalog.Entry entry = entry(source);
        if (entry == null) return null;
        ItemStack copy = source.clone();
        if (!core.items().updateState(copy, state)) return null;
        ItemIdentity identity = identity(copy).orElseThrow();
        if (!entry.legacyCard().isEmpty()) {
            return PackPresentation.applyCardPresentation(copy, entry, identity, cardRules, texturedMenu()) ? copy : null;
        }
        var progress = progress(identity);
        var meta = copy.getItemMeta();
        meta.setLore(List.of(ChatColor.GRAY + "Level " + progress.level() + " · " + formatXp(progress.xp()) + " XP",
                ChatColor.DARK_GRAY + "Frame: " + identity.state().getOrDefault("frame", "simple")));
        PackPresentation.tooltip(meta, identity.state().getOrDefault("frame", "simple"));
        return copy.setItemMeta(meta) ? copy : null;
    }

    private static Progression progress(ItemIdentity identity) {
        return new Progression(Integer.parseInt(identity.state().getOrDefault("level", "0")),
                Double.parseDouble(identity.state().getOrDefault("xp", "0")));
    }

    private static Map<String, String> progressState(Progression progress) {
        return Map.of("level", Integer.toString(progress.level()), "xp", Double.toString(progress.xp()));
    }

    private static String formatXp(double xp) {
        return java.math.BigDecimal.valueOf(xp).stripTrailingZeros().toPlainString();
    }

    boolean editable(Player player) {
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR || !core.canEditInventory(player)) {
            tell(player, "Your inventory is unavailable right now.");
            return false;
        }
        return true;
    }

    /** Legacy trading cards' chat prefix. */
    static final String CARDS_PREFIX = "<gradient:#d4a04a:#8a5a2a>[Cards]</gradient> &f";
    private static final String CURIOS_PREFIX = "&6[Curios] &f";
    /** Set while /cards or /tradingcards runs, so every reply to it carries the [Cards] prefix. */
    private final ThreadLocal<Boolean> cardsReply = ThreadLocal.withInitial(() -> false);

    void tell(CommandSender recipient, String message) {
        tellWithMode(recipient, cardsReply.get(), message);
    }

    private void tellWithMode(CommandSender recipient, boolean cardsMode, String message) {
        core.text().send(recipient, (cardsMode ? CARDS_PREFIX : CURIOS_PREFIX) + message);
    }

    /** A message about a trading card, with legacy's [Cards] prefix whatever command or event sent it. */
    void tellCards(CommandSender recipient, String message) { core.text().send(recipient, CARDS_PREFIX + message); }

    /** [Cards] for a message about a trading card, the command's own prefix otherwise. */
    private void tell(CommandSender recipient, Catalog.Entry about, String message) {
        if (about != null && !about.legacyCard().isEmpty()) tellCards(recipient, message);
        else tell(recipient, message);
    }

    /** A card's title as its lore shows it, in the mob's colour. */
    private String cardTitle(Catalog.Entry entry) {
        return CardRules.legacyColourCode(cardRules.mobColour(entry.legacyCard()))
                + CardRules.titleCase(entry.legacyCard()) + " Trading Card";
    }

    private static int emptySlot(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            if (storage[slot] == null || storage[slot].getType().isAir()) return slot;
        }
        return -1;
    }

    private static boolean insert(Player player, ItemStack stack) {
        int slot = emptySlot(player);
        if (slot < 0) return false;
        player.getInventory().setItem(slot, stack);
        return true;
    }

    private NamespacedKey equipmentKey(Catalog.Slot slot) { return new NamespacedKey(this, "equipped_" + slot.key()); }

    ItemStack equipped(Player player, Catalog.Slot slot) {
        return loadouts.computeIfAbsent(player.getUniqueId(), ignored -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(slot, key -> readEquipped(player, key));
    }

    void setEquipped(Player player, Catalog.Slot slot, ItemStack updated) {
        if (updated == null) {
            player.getPersistentDataContainer().remove(equipmentKey(slot));
        } else {
            player.getPersistentDataContainer().set(equipmentKey(slot), PersistentDataType.STRING, encode(updated));
        }
        loadouts.remove(player.getUniqueId());
    }

    private ItemStack readEquipped(Player player, Catalog.Slot slot) {
        String encoded = player.getPersistentDataContainer().get(equipmentKey(slot), PersistentDataType.STRING);
        if (encoded == null) return null;
        var yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(encoded);
            return yaml.getItemStack("item");
        } catch (InvalidConfigurationException | RuntimeException corrupt) {
            return null;
        }
    }

    private static String encode(ItemStack stack) {
        var yaml = new YamlConfiguration();
        yaml.set("item", stack);
        return yaml.saveToString();
    }

    private final class Menu implements InventoryHolder {
        final UUID owner;
        int page;
        final Inventory inventory;
        Menu(Player player, int page) {
            owner = player.getUniqueId();
            this.page = page;
            inventory = PackPresentation.inventory(this, getConfig().getBoolean("textured-menu"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private void open(Player player, int page) {
        Menu menu = new Menu(player, page);
        render(player, menu);
        player.openInventory(menu.inventory);
    }

    private void render(Player player, Menu menu) {
        Inventory inv = menu.inventory;
        inv.clear();
        inv.setItem(0, icon("prev", "Previous page"));
        inv.setItem(8, icon("next", "Next page"));
        for (int index = 0; index < 7 && menu.page * 7 + index < catalog.slots.size(); index++) {
            var slot = catalog.slots.get(menu.page * 7 + index);
            var item = equipped(player, slot);
            inv.setItem(index + 1, item == null ? icon(slot.type(), slot.type() + " " + (slot.index() + 1)) : item.clone());
        }
    }

    private static ItemStack icon(String id, String name) {
        var icon = new ItemStack(Material.PAPER);
        ItemMeta meta = icon.getItemMeta();
        int index = ICONS.indexOf(id);
        if (index >= 0) meta.setCustomModelData(2679900 + index);
        meta.setDisplayName(ChatColor.GRAY + name);
        icon.setItemMeta(meta);
        return icon;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner.equals(player.getUniqueId())
                || event.getClickedInventory() == null
                || !event.getCursor().getType().isAir() || !editable(player)) return;
        if (event.getClick() == ClickType.RIGHT && event.getRawSlot() > 0 && event.getRawSlot() < 8) {
            int slotIndex = menu.page * 7 + event.getRawSlot() - 1;
            if (slotIndex < catalog.slots.size()) {
                Catalog.Slot eqSlot = catalog.slots.get(slotIndex);
                Catalog.Entry eqEntry = entry(equipped(player, eqSlot));
                if (eqEntry != null && !eqEntry.legacyCard().isEmpty()) {
                    cardGui.openHomeAfterClick(player, new CardGuiManager.EquippedSource(eqSlot));
                    return;
                }
            }
            return;
        }
        // Right-clicking a card in your own inventory below this menu opens its card menu. Under a
        // menu like this one, creative clients send real clicks, unlike in the creative inventory.
        if (event.getClick() == ClickType.RIGHT && event.getClickedInventory() == player.getInventory()) {
            Catalog.Entry clicked = entry(event.getCurrentItem());
            if (clicked != null && !clicked.legacyCard().isEmpty()) {
                cardGui.openHomeAfterClick(player, new CardGuiManager.InventorySource(event.getSlot()));
            }
            return;
        }
        if (event.getClick() != ClickType.LEFT) return;
        if (event.getRawSlot() < 9) {
            int slot = event.getRawSlot();
            if (slot == 0 || slot == 8) {
                int page = menu.page + (slot == 0 ? -1 : 1);
                if (page >= 0 && page * 7 < catalog.slots.size()) menu.page = page;
            } else if (slot > 0 && menu.page * 7 + slot - 1 < catalog.slots.size()) {
                unequip(player, catalog.slots.get(menu.page * 7 + slot - 1));
            }
        } else if (event.getClickedInventory() == player.getInventory()) {
            equip(player, event.getSlot());
        }
        render(player, menu);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    private void equip(Player player, int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= player.getInventory().getStorageContents().length) return;
        ItemStack item = player.getInventory().getItem(inventorySlot);
        Catalog.Entry entry = entry(item);
        if (entry == null) return;
        var data = player.getPersistentDataContainer();
        for (Catalog.Slot slot : catalog.slots) {
            NamespacedKey key = equipmentKey(slot);
            if (!slot.type().equals(entry.slot()) || data.has(key, PersistentDataType.STRING)) continue;
            data.set(key, PersistentDataType.STRING, encode(item));
            loadouts.remove(player.getUniqueId());
            player.getInventory().setItem(inventorySlot, null);
            refreshAttributes(player);
            if (!entry.legacyCard().isEmpty()) {
                tellCards(player, "&eEquipped " + cardTitle(entry) + "&e, its boosts are now active.");
            }
            return;
        }
        tell(player, entry, "There is no free " + entry.slot() + " slot.");
    }

    private void unequip(Player player, Catalog.Slot slot) {
        ItemStack item = equipped(player, slot);
        if (item == null) return;
        if (!insert(player, item.clone())) { tell(player, entry(item), "Free an inventory slot first."); return; }
        player.getPersistentDataContainer().remove(equipmentKey(slot));
        loadouts.remove(player.getUniqueId());
        refreshAttributes(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void killed(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        String mob = event.getEntityType().name();
        var rng = ThreadLocalRandom.current();
        for (Catalog.Entry entry : catalog.entries.values()) {
            if (entry.dropMob().equals(mob) && rng.nextDouble() < entry.chance()
                    && qualifies(event, entry.dropCondition())) addDrop(event.getDrops(), entry, null);
        }
        if (killer != null && killer.hasPermission("curios.use")) {
            for (var pool : catalog.pools) {
                if (pool.mob().equals(mob) && rng.nextDouble() < pool.chance()) {
                    addDrop(event.getDrops(), pool.roll(rng.nextDouble()), cardRules.dropToInventory ? killer : null);
                }
            }
            awardEquipped(killer, catalog.killXp);
        }
    }

    private static boolean qualifies(EntityDeathEvent event, Catalog.DropCondition condition) {
        var mob = event.getEntity();
        return switch (condition) {
            case ANY -> true;
            case FREEZE -> mob.getLastDamageCause() != null
                    && mob.getLastDamageCause().getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FREEZE;
            case RAID -> {
                if (!(mob instanceof org.bukkit.entity.Raider)) yield false;
                try {
                    if (org.bukkit.entity.Raider.class.getMethod("getRaid").invoke(mob) != null) yield true;
                } catch (ReflectiveOperationException | LinkageError unavailable) {
                    // Older servers can detect an active nearby raid through the world API.
                }
                var raid = mob.getWorld().locateNearestRaid(mob.getLocation(), 112);
                yield raid != null && raid.getStatus() == org.bukkit.Raid.RaidStatus.ONGOING;
            }
        };
    }

    private int awardEquipped(Player player, double xp) {
        if (!core.ownsEntity(player) || !player.isOnline() || !player.hasPermission("curios.use")
                || !Double.isFinite(xp) || xp <= 0 || !core.canEditInventory(player)) return 0;
        int changes = 0;
        for (Catalog.Slot slot : catalog.slots) {
            ItemStack item = equipped(player, slot);
            Catalog.Entry entry = entry(item);
            if (entry == null) continue;
            Progression before = progress(identity(item).orElseThrow());
            Progression after = entry.legacyCard().isEmpty()
                    ? before.award(xp, entry.maxLevel(), entry.step())
                    : cardRules.awardCardXp(before.level(), before.xp(), xp, entry.maxLevel());
            if (before.equals(after)) continue;
            ItemStack updated = changed(item, progressState(after));
            if (updated != null) {
                player.getPersistentDataContainer().set(equipmentKey(slot), PersistentDataType.STRING, encode(updated));
                changes++;
                if (!entry.legacyCard().isEmpty() && after.level() > before.level()) {
                    tellCards(player, "&eYour " + CardRules.titleCase(entry.legacyCard())
                            + " card is now level &a" + after.level() + "&e! &2" + before.level() + " &f→ &a" + after.level());
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
                }
            }
        }
        if (changes > 0) loadouts.remove(player.getUniqueId());
        return changes;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void loot(LootGenerateEvent event) {
        String table = event.getLootTable().getKey().toString();
        for (Catalog.Entry entry : catalog.entries.values()) {
            if (entry.lootTable().equals(table) && ThreadLocalRandom.current().nextDouble() < entry.chance()) {
                addDrop(event.getLoot(), entry, null);
            }
        }
    }

    private void addDrop(List<ItemStack> drops, Catalog.Entry entry, Player directRecipient) {
        if (entry == null) return;
        ItemStack item = create(entry, Map.of());
        if (item == null) return;
        if (directRecipient != null && directRecipient.isOnline() && insert(directRecipient, item)) {
            tellCards(directRecipient, "&eYou received a " + cardTitle(entry) + "&e!");
            return;
        }
        drops.add(item);
    }

    private void registerRecipe(Catalog.Entry entry) {
        var config = entry.recipe();
        if (config == null) return;
        var key = new NamespacedKey(this, entry.id());
        var preview = new ItemStack(Material.PAPER);
        var recipe = new ShapedRecipe(key, preview).shape(config.getStringList("shape").toArray(String[]::new));
        var ingredients = Objects.requireNonNull(config.getConfigurationSection("ingredients"));
        for (String symbol : ingredients.getKeys(false)) {
            Material material = Material.matchMaterial(ingredients.getString(symbol, ""));
            if (symbol.length() != 1 || material == null || material.isAir() || material.getCraftingRemainingItem() != null) {
                throw new IllegalArgumentException("Invalid recipe ingredient " + symbol + " for " + entry.id());
            }
            recipe.setIngredient(symbol.charAt(0), new RecipeChoice.ExactChoice(new ItemStack(material)));
        }
        if (!getServer().addRecipe(recipe)) throw new IllegalArgumentException("Couldn't register " + key);
        recipes.put(key, entry);
    }

    private Catalog.Entry recipe(Recipe recipe) {
        return recipe instanceof ShapedRecipe shaped ? recipes.get(shaped.getKey()) : null;
    }

    @EventHandler
    public void prepare(PrepareItemCraftEvent event) {
        Catalog.Entry entry = recipe(event.getRecipe());
        if (entry != null) event.getInventory().setResult(create(entry, Map.of("bound", "true")));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void craft(CraftItemEvent event) {
        Catalog.Entry entry = recipe(event.getRecipe());
        if (entry == null) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("curios.use")
                || event.getClick() != ClickType.LEFT || !event.getCursor().getType().isAir() || !editable(player)) return;
        ItemStack result = create(entry, Map.of("bound", "true"));
        if (result == null || emptySlot(player) < 0) { tell(player, "Free an inventory slot first."); return; }
        ItemStack[] matrix = event.getInventory().getMatrix();
        for (int i = 0; i < matrix.length; i++) {
            ItemStack ingredient = matrix[i];
            if (ingredient != null && !ingredient.getType().isAir()) {
                if (ingredient.getAmount() <= 1) matrix[i] = null;
                else { matrix[i] = ingredient.clone(); matrix[i].setAmount(ingredient.getAmount() - 1); }
            }
        }
        event.getInventory().setMatrix(matrix);
        insert(player, result);
    }

    private void importHeld(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        LegacyImport.Decoded decoded = LegacyImport.held(catalog, held);
        ItemStack result = decoded == null ? null : create(decoded.entry(), decoded.state());
        if (result == null) { tell(player, "No supported legacy curio is held."); return; }
        player.getInventory().setItemInMainHand(result);
        tell(player, "Imported " + decoded.entry().name() + ".");
    }

    boolean hasAbility(Player player, Catalog.Ability ability) {
        if (!player.hasPermission("curios.use") || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false;
        for (var slot : catalog.slots) {
            Catalog.Entry entry = entry(equipped(player, slot));
            if (entry != null && entry.abilities().contains(ability)) return true;
        }
        return false;
    }

    @EventHandler
    public void join(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        refreshAttributes(player);
        recoverPlayerState(player, false);
    }

    void recoverPlayerState(Player player, boolean explicitCommand) {
        if (recovery == null || !player.isOnline()) return;
        recovery.reconcilePendingRolls(player, core.economy());
        CuriosRecoveryStore.RecoverySummary summary = recovery.claimAllPending(player);
        if (summary.claimsCompleted() > 0 && summary.claimsRemaining() == 0) {
            tellCards(player, "&aDelivered " + summary.claimsCompleted() + " pending trade-in reward"
                    + (summary.claimsCompleted() == 1 ? "." : "s."));
        } else if (summary.claimsCompleted() > 0 && summary.claimsRemaining() > 0) {
            tellCards(player, "&eDelivered " + summary.claimsCompleted() + " trade-in reward(s); "
                    + summary.claimsRemaining() + " still waiting for inventory space (/curios claim).");
        } else if (summary.claimsRemaining() > 0) {
            tellCards(player, "&eYou have " + summary.claimsRemaining()
                    + " unclaimed trade-in reward(s). Make room in your inventory and run /curios claim.");
        } else if (explicitCommand) {
            tellCards(player, "You have no pending trade-in rewards to claim.");
        }
    }

    @EventHandler
    public void quit(org.bukkit.event.player.PlayerQuitEvent event) {
        clearAttributes(event.getPlayer());
        loadouts.remove(event.getPlayer().getUniqueId());
    }

    private static final UUID ATTR_ARMOR = UUID.nameUUIDFromBytes("curios:card_armor".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID ATTR_HASTE = UUID.nameUUIDFromBytes("curios:card_haste".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID ATTR_KNOCKBACK = UUID.nameUUIDFromBytes("curios:card_knockback".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    void refreshAttributes(Player player) {
        clearAttributes(player);
        if (!player.isOnline() || !player.hasPermission("curios.use")) return;
        double armor = 0.0, haste = 0.0, knockback = 0.0;
        for (Catalog.Slot slot : catalog.slots) {
            ItemStack item = equipped(player, slot);
            Catalog.Entry entry = entry(item);
            if (entry == null || entry.legacyCard().isEmpty()) continue;
            ItemIdentity id = identity(item).orElse(null);
            if (id == null) continue;
            CardRules.Tier tier = CardRules.Tier.parse(entry.legacyTier());
            int unlocked = Math.min(CardRules.BONUS_SLOT_COUNT, tier.ordinal() + 1);
            List<CardRules.BonusSlotData> bonuses = CardRules.parseBonuses(id.state().get("bonuses"));
            for (int i = 0; i < unlocked && i < bonuses.size(); i++) {
                CardRules.BonusSlotData b = bonuses.get(i);
                if (b.isEmpty()) continue;
                CardRules.BonusDef def = CardRules.BONUSES.get(b.boostId());
                if (def == null) continue;
                switch (b.boostId()) {
                    case "armor" -> armor += def.scaled(b.strength());
                    case "haste" -> haste += def.scaled(b.strength()) / 100.0;
                    case "knockback" -> knockback += def.scaled(b.strength()) / 100.0;
                    default -> { }
                }
            }
        }
        applyModifier(player, org.bukkit.attribute.Attribute.GENERIC_ARMOR, ATTR_ARMOR, "curios_armor", armor,
                org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER);
        applyModifier(player, org.bukkit.attribute.Attribute.GENERIC_ATTACK_SPEED, ATTR_HASTE, "curios_haste", haste,
                org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR);
        applyModifier(player, org.bukkit.attribute.Attribute.GENERIC_KNOCKBACK_RESISTANCE, ATTR_KNOCKBACK, "curios_knockback",
                Math.min(1.0, knockback), org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER);
    }

    void clearAttributes(Player player) {
        removeModifier(player, org.bukkit.attribute.Attribute.GENERIC_ARMOR, ATTR_ARMOR, "curios_armor");
        removeModifier(player, org.bukkit.attribute.Attribute.GENERIC_ATTACK_SPEED, ATTR_HASTE, "curios_haste");
        removeModifier(player, org.bukkit.attribute.Attribute.GENERIC_KNOCKBACK_RESISTANCE, ATTR_KNOCKBACK, "curios_knockback");
    }

    @SuppressWarnings("deprecation")
    private static void applyModifier(Player player, org.bukkit.attribute.Attribute attr, UUID id, String name, double amount,
                                      org.bukkit.attribute.AttributeModifier.Operation op) {
        if (amount <= 0.0) return;
        var instance = player.getAttribute(attr);
        if (instance == null) return;
        instance.addModifier(new org.bukkit.attribute.AttributeModifier(id, name, amount, op));
    }

    @SuppressWarnings("deprecation")
    private static void removeModifier(Player player, org.bukkit.attribute.Attribute attr, UUID id, String name) {
        var instance = player.getAttribute(attr);
        if (instance == null) return;
        for (var mod : new ArrayList<>(instance.getModifiers())) {
            if (id.equals(mod.getUniqueId()) || name.equals(mod.getName())) {
                instance.removeModifier(mod);
            }
        }
    }

    private void importLoadout(Player actor, Player target) {
        if (getServer().getPluginManager().isPluginEnabled("Relique")) {
            tell(actor, "Disable legacy Relique before importing its equipment.");
            return;
        }
        var imported = new NamespacedKey(this, "legacy_loadout_imported");
        UUID id = target.getUniqueId();
        boolean cardsMode = cardsReply.get();
        if (!importing.add(id)) { tell(actor, "That import is already running."); return; }
        var directory = java.nio.file.Path.of(getConfig().getString("migration-directory", "config/relique/entities"));
        core.onOwner(target, () -> {
            if (getServer().getPlayer(id) != target) {
                importing.remove(id);
                return;
            }
            if (target.getPersistentDataContainer().has(imported, PersistentDataType.BYTE)) {
                importing.remove(id);
                if (actor.isOnline()) tellWithMode(actor, cardsMode, "That legacy equipment was already imported.");
                return;
            }
            try {
                importIo.execute(() -> {
                    try {
                        var loadout = LegacyImport.load(catalog, directory.resolve(id + ".yml"));
                        core.runForEntity(target, () -> {
                            importing.remove(id);
                            if (getServer().getPlayer(id) != target || !actor.isOnline()
                                    || !editable(target)
                                    || getServer().getPluginManager().isPluginEnabled("Relique")) return;
                            applyImport(actor, target, imported, loadout, cardsMode);
                        }, () -> importing.remove(id), 0L);
                    } catch (java.io.IOException | InvalidConfigurationException | RuntimeException invalid) {
                        importing.remove(id);
                        core.runForSender(actor, () -> {
                            if (actor.isOnline()) tellWithMode(actor, cardsMode, "Import refused: " + invalid.getMessage());
                        }, () -> {}, 0L);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                importing.remove(id);
                if (actor.isOnline()) tellWithMode(actor, cardsMode, "The import queue is full.");
            }
        });
    }

    private void applyImport(Player actor, Player target, NamespacedKey imported,
                             Map<Catalog.Slot, LegacyImport.Decoded> loadout, boolean cardsMode) {
        var data = target.getPersistentDataContainer();
        if (data.has(imported, PersistentDataType.BYTE)) return;
        Map<NamespacedKey, String> values = new LinkedHashMap<>();
        for (var row : loadout.entrySet()) {
            NamespacedKey key = equipmentKey(row.getKey());
            if (data.has(key, PersistentDataType.STRING)) { tellWithMode(actor, cardsMode, "Unequip " + row.getKey().key() + " first."); return; }
            var decoded = row.getValue();
            ItemStack item = create(decoded.entry(), decoded.state());
            if (item == null) { tellWithMode(actor, cardsMode, "Could not create " + decoded.entry().id()); return; }
            values.put(key, encode(item));
        }
        if (values.isEmpty()) { tellWithMode(actor, cardsMode, "The legacy file contains no equipped curios."); return; }
        values.forEach((key, value) -> data.set(key, PersistentDataType.STRING, value));
        data.set(imported, PersistentDataType.BYTE, (byte) 1);
        loadouts.remove(target.getUniqueId());
        target.saveData();
        tellWithMode(actor, cardsMode, "Imported " + values.size() + " equipped curios for " + target.getName() + ".");
    }
}
