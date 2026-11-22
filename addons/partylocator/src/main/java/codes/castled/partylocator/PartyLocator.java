package codes.castled.partylocator;

import codes.castled.allium.api.AddonCommands;
import codes.castled.allium.api.AddonConnection;
import codes.castled.allium.api.AlliumAddon;
import codes.castled.allium.command.spec.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Party Locator: legacy Allium's /party and /locatorbar as an addon. Parties now survive restarts
 * ({@link PartyStore}); the locator bar keeps party members on each other's bar and limits everyone
 * else to a radius ({@link LocatorBar}); mcMMO parties follow along when mcMMO is installed.
 */
public final class PartyLocator extends AlliumAddon implements Listener {
    private static final List<String> PARTY_ACTIONS = List.of("create", "invite", "join", "leave", "disband", "info", "list", "reload", "visibility");
    private static final List<String> BAR_ACTIONS = List.of("toggle", "staff", "help", "show", "hide", "reset");

    private AddonConnection core;
    private final PartyBook book = new PartyBook();
    private PartyStore store;
    private ThreadPoolExecutor storeWriter;
    private LocatorBar bar;
    private boolean syncMcMMO;

    @Override
    protected void onAddonEnable(AddonConnection connection) {
        core = connection;
        LegacyPartySettings.importOnFirstStart(this);
        saveDefaultConfig();
        store = new PartyStore(getDataFolder().toPath().resolve("parties.yml"));
        try {
            book.load(store.load());
        } catch (Exception unreadable) {
            getLogger().severe("Could not read " + store.file() + "; starting with no parties and leaving the file untouched: " + unreadable.getMessage());
            store = new PartyStore(getDataFolder().toPath().resolve("parties.recovered.yml"));
        }
        storeWriter = core.scheduler().boundedPool("PartyLocator-Store", 1, 256);
        startBar();
        register("party", List.of(), "/party <create|invite|join|leave|disband|info|list>", "allium.party",
                PARTY_ACTIONS, this::party, this::completeParty);
        register("locatorbar", List.of("partybar", "lbar", "pbar"), "/locatorbar [staff|show|hide|reset|help]",
                "allium.locatorbar", BAR_ACTIONS, this::locatorBar, this::completeBar);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Loaded " + book.parties().size() + " parties.");
    }

    @Override
    protected void onAddonDisable() {
        if (bar != null) bar.stop();
        bar = null;
        if (storeWriter != null) {
            storeWriter.shutdown();
            try {
                storeWriter.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            store.write(PartyStore.snapshot(book.parties()));
        } catch (Exception failed) {
            getLogger().severe("Could not save parties: " + failed.getMessage());
        }
    }

    AddonConnection core() { return core; }
    PartyBook book() { return book; }

    private void startBar() {
        if (bar != null) {
            bar.stop();
            org.bukkit.event.HandlerList.unregisterAll(bar);
            bar = null;
        }
        syncMcMMO = getConfig().getBoolean("sync-with-mcmmo", true);
        if (!getConfig().getBoolean("party-locator-bar", true)) return;
        if (LocatorBar.locatorBarRule() == null) {
            getLogger().info("This server has no locator bar (Minecraft 1.21.6+); /party works without it.");
            return;
        }
        if (!getServer().getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().warning("The party locator bar needs PacketEvents; /party works without it.");
            return;
        }
        try {
            bar = new LocatorBar(this, PacketSupport.create(getLogger()));
        } catch (RuntimeException | LinkageError unavailable) {
            getLogger().warning("PacketEvents could not filter waypoints, so the locator bar is left to vanilla: " + unavailable);
            return;
        }
        getServer().getPluginManager().registerEvents(bar, this);
        bar.start(Math.max(0, getConfig().getDouble("show-non-party-members-radius", 128)),
                getConfig().getLong("refresh-ticks", 10));
    }

    private void save() {
        String snapshot = PartyStore.snapshot(book.parties());
        try {
            storeWriter.execute(() -> {
                try {
                    store.write(snapshot);
                } catch (Exception failed) {
                    getLogger().severe("Could not save parties: " + failed.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            getLogger().warning("The party save queue is full; the next change saves again.");
        }
    }

    private void refresh(Collection<UUID> players) {
        if (bar == null) return;
        for (UUID id : players) bar.refresh(getServer().getPlayer(id));
    }

    // -- commands ----------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Handler { void run(Player player, String[] args); }

    private void register(String label, List<String> aliases, String usage, String permission, List<String> actions,
                          Handler handler, AddonCommands.Completer completer) {
        core.commands().register(new CommandSpec(label, "partylocator", "partylocator", aliases, "Parties and the party locator bar",
                usage, usage, permission, null,
                List.of(CommandArgSpec.optional("action", null, SuggestionSpec.literals(actions, null))),
                List.of(), CommandMessageKeys.ofOutcomes(Map.of())), ctx -> {
            if (ctx.sender() instanceof Player player) handler.run(player, ctx.args());
            else ctx.sender().sendMessage("This command requires a player.");
        }, completer);
    }

    private void tell(CommandSender who, String message) {
        core.text().send(who, message);
    }

    private void party(Player player, String[] args) {
        if (args.length == 0) { partyHelp(player); return; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(player);
            case "invite" -> {
                if (args.length < 2) tell(player, "&cUsage: /party invite <player>");
                else invite(player, args[1]);
            }
            case "join" -> {
                if (args.length < 2) tell(player, "&cUsage: /party join <player>");
                else join(player, args[1]);
            }
            case "leave" -> leave(player);
            case "disband" -> disband(player);
            case "info" -> info(player);
            case "list" -> list(player);
            case "reload" -> reload(player);
            case "visibility" -> visibility(player);
            default -> partyHelp(player);
        }
    }

    private void partyHelp(Player player) {
        tell(player, "&6Party Commands:");
        tell(player, "&e/party create &7- Create a new party");
        tell(player, "&e/party invite <player> &7- Invite a player to your party");
        tell(player, "&e/party join <player> &7- Join the party of the specified player");
        tell(player, "&e/party leave &7- Leave your current party");
        tell(player, "&e/party disband &7- Disband your party (leader only)");
        tell(player, "&e/party info &7- Show your party info");
        tell(player, "&e/party list &7- List all parties");
        if (player.hasPermission("allium.party.reload")) {
            tell(player, "&e/party reload &7- Reload party configuration (admin only)");
            tell(player, "&e/party visibility &7- Force visibility refresh (admin only)");
        }
    }

    private void create(Player player) {
        Party existing = book.partyOf(player.getUniqueId());
        if (existing != null) {
            tell(player, existing.leader().equals(player.getUniqueId())
                    ? "&cYou are already the leader of party '" + existing.name() + "'. Use /party disband to remove it first."
                    : "&cYou are already a member of party '" + existing.name() + "'. Use /party leave to leave it first.");
            return;
        }
        Party party = book.create(PartyBook.nameFor(player.getName()), player.getUniqueId());
        if (party == null) { tell(player, "&cFailed to create party."); return; }
        save();
        tell(player, "&aParty '" + party.name() + "' created!");
        if (syncMcMMO) McMMOSync.create(player, party.name());
        refresh(party.members());
    }

    private void invite(Player player, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !player.canSee(target)) { tell(player, "&cPlayer " + targetName + " is not online."); return; }
        switch (book.invite(player.getUniqueId(), target.getUniqueId())) {
            case SUCCESS -> {
                tell(player, "&aInvited " + target.getName() + " to your party.");
                tell(target, "&e" + player.getName() + " has invited you to join their party. Use &a/party join "
                        + player.getName() + " &eto accept.");
            }
            case NO_PARTY -> tell(player, "&cYou must create a party before inviting players.");
            case NOT_LEADER -> tell(player, "&cOnly the party leader can invite players.");
            case TARGET_IN_PARTY -> tell(player, "&cThat player is already in a party.");
            case ALREADY_INVITED -> tell(player, "&cThat player already has a pending invite.");
            case SELF_INVITE -> tell(player, "&cYou cannot invite yourself.");
        }
    }

    private void join(Player player, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !player.canSee(target)) { tell(player, "&cPlayer " + targetName + " is not online."); return; }
        Party party = book.partyOf(target.getUniqueId());
        if (party == null) { tell(player, "&cThat player is not in a party."); return; }
        switch (book.join(party.name(), player.getUniqueId())) {
            case SUCCESS -> {
                save();
                tell(player, "&aJoined party '" + party.name() + "'!");
                tell(target, "&e" + player.getName() + " has joined your party.");
                // mcMMO needs a moment to load a joining player's profile.
                if (syncMcMMO) core.scheduler().runOnServerLater(() -> McMMOSync.join(player, party.name()), 250);
                refresh(party.members());
            }
            case NOT_FOUND -> tell(player, "&cThat party no longer exists.");
            case ALREADY_IN_PARTY -> tell(player, "&cYou are already in a party.");
            case NOT_INVITED -> tell(player, "&cYou do not have an invite to that party.");
        }
    }

    private void leave(Player player) {
        Party party = book.partyOf(player.getUniqueId());
        if (party == null) { tell(player, "&cYou're not in a party."); return; }
        if (party.leader().equals(player.getUniqueId())) {
            tell(player, "&cParty leaders must use &e/party disband &cto disband the party.");
            return;
        }
        Set<UUID> before = new HashSet<>(party.members());
        if (!book.leave(player.getUniqueId())) { tell(player, "&cFailed to leave the party."); return; }
        save();
        tell(player, "&aLeft the party.");
        Player leader = getServer().getPlayer(party.leader());
        if (leader != null) tell(leader, "&e" + player.getName() + " has left the party.");
        if (syncMcMMO) McMMOSync.leave(player);
        refresh(before);
    }

    private void disband(Player player) {
        Party party = book.partyOf(player.getUniqueId());
        if (party == null) { tell(player, "&cYou're not in a party."); return; }
        if (!party.leader().equals(player.getUniqueId())) { tell(player, "&cOnly the party leader can disband the party."); return; }
        Party disbanded = book.disband(player.getUniqueId());
        if (disbanded == null) { tell(player, "&cFailed to disband the party."); return; }
        save();
        tell(player, "&aYour party has been disbanded.");
        for (UUID id : disbanded.members()) {
            Player member = getServer().getPlayer(id);
            if (member == null) continue;
            if (!member.equals(player)) tell(member, "&cYour party has been disbanded by " + player.getName() + ".");
            if (syncMcMMO) McMMOSync.leave(member);
        }
        refresh(disbanded.members());
    }

    private void info(Player player) {
        Party party = book.partyOf(player.getUniqueId());
        if (party == null) { tell(player, "&cYou're not in a party."); return; }
        String members = party.members().stream().map(this::memberName).collect(Collectors.joining("&r, "));
        tell(player, "&6Party: &f" + party.name());
        tell(player, "&6Leader: &f" + (party.leader().equals(player.getUniqueId()) ? "You" : name(party.leader())));
        tell(player, "&6Members (&f" + party.size() + "&6): &r" + members);
    }

    private void list(Player player) {
        if (book.parties().isEmpty()) { tell(player, "&eThere are no parties."); return; }
        tell(player, "&6Parties (&f" + book.parties().size() + "&6):");
        for (Party party : book.parties()) {
            tell(player, "&e" + party.name() + " &7- leader &f" + name(party.leader()) + "&7, " + party.size()
                    + " member" + (party.size() == 1 ? "" : "s"));
        }
    }

    private String memberName(UUID id) {
        Player online = getServer().getPlayer(id);
        return online != null ? "&a" + online.getName() : "&7" + name(id) + " (offline)";
    }

    private String name(UUID id) {
        OfflinePlayer offline = getServer().getOfflinePlayer(id);
        return offline.getName() != null ? offline.getName() : "Unknown";
    }

    private void reload(Player player) {
        if (!player.hasPermission("allium.party.reload")) {
            tell(player, "&cYou don't have permission to reload party configuration.");
            return;
        }
        reloadConfig();
        startBar();
        tell(player, "&aParty configuration reloaded successfully.");
    }

    private void visibility(Player player) {
        if (!player.hasPermission("allium.party.reload")) { tell(player, "&cYou don't have permission."); return; }
        if (bar != null) bar.refreshAll();
        tell(player, "&aVisibility refresh forced. Config: party-locator-bar=" + (bar != null)
                + ", radius=" + (bar == null ? getConfig().getDouble("show-non-party-members-radius", 128) : bar.radius())
                + ", locator-bar-enabled=" + (LocatorBar.locatorBarRule() != null));
    }

    private void locatorBar(Player player, String[] args) {
        String action = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("help")) {
            if (!player.hasPermission(LocatorBar.STAFF) && !player.hasPermission("allium.locatorbar.help")) {
                tell(player, "&cYou don't have permission to view the help.");
                return;
            }
            tell(player, "&6Locator Bar Commands:");
            tell(player, "&e/locatorbar staff &7- Toggle staff locator mode");
            tell(player, "&e/locatorbar show <player> <time> &7- Force show a player");
            tell(player, "&e/locatorbar hide <player> <time> &7- Force hide a player");
            tell(player, "&e/locatorbar reset <player> &7- Reset visibility overrides");
            tell(player, "&7Time formats: 30 (seconds), 1m (minutes), 2h (hours), 1d (days)");
            return;
        }
        if (bar == null) {
            tell(player, "&cThe party locator bar is off: party-locator-bar is false, or PacketEvents or Minecraft 1.21.6+ is missing.");
            return;
        }
        switch (action) {
            case "toggle", "staff" -> {
                if (!player.hasPermission(LocatorBar.STAFF)) {
                    tell(player, "&cYou don't have permission to use staff locator mode.");
                    return;
                }
                tell(player, bar.toggleStaffMode(player)
                        ? "&aStaff locator mode enabled: You can now see all players on the locator bar."
                        : "&eStaff locator mode disabled: You now only see players within the configured radius.");
            }
            case "show", "hide" -> {
                if (args.length < 3) { tell(player, "&cUsage: /locatorbar " + action + " <player> <time>"); return; }
                Player target = match(player, args[1]);
                if (target == null) { tell(player, "&cPlayer " + args[1] + " is not online."); return; }
                long seconds = Durations.parse(args[2]);
                if (seconds <= 0) { tell(player, "&cInvalid time format. Use format like 30, 1m, 2h, 1d"); return; }
                boolean show = action.equals("show");
                bar.force(player, target, seconds, show);
                tell(player, "&aForcing " + target.getName() + " to be " + (show ? "visible" : "hidden") + " for "
                        + Durations.format(seconds) + ".");
                if (!target.equals(player)) {
                    tell(target, "&e" + player.getName() + " has " + (show ? "made you visible on" : "hidden you from")
                            + " the locator bar for " + Durations.format(seconds) + ".");
                }
            }
            case "reset" -> {
                if (args.length < 2) { tell(player, "&cUsage: /locatorbar reset <player>"); return; }
                Player target = match(player, args[1]);
                if (target == null) { tell(player, "&cPlayer " + args[1] + " is not online."); return; }
                if (target.equals(player)) { tell(player, "&cYou cannot modify your own visibility."); return; }
                bar.reset(player, target);
                tell(player, "&aReset visibility overrides for " + target.getName() + ".");
                tell(target, "&e" + player.getName() + " has reset your locator bar visibility.");
            }
            default -> tell(player, "&cUnknown subcommand. Use /locatorbar help for help.");
        }
    }

    private Player match(Player viewer, String name) {
        Player exact = getServer().getPlayerExact(name);
        Player target = exact != null ? exact : getServer().getPlayer(name);
        return target != null && viewer.canSee(target) ? target : null;
    }

    private List<String> completeParty(AddonCommands.Context ctx) {
        String[] args = ctx.args();
        if (args.length <= 1) {
            boolean admin = ctx.sender().hasPermission("allium.party.reload");
            return starting(PARTY_ACTIONS.stream().filter(a -> admin || !a.equals("reload") && !a.equals("visibility")).toList(),
                    args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2 && ("invite".equalsIgnoreCase(args[0]) || "join".equalsIgnoreCase(args[0]))) {
            return starting(visibleNames(ctx.sender(), "invite".equalsIgnoreCase(args[0])), args[1]);
        }
        return List.of();
    }

    private List<String> completeBar(AddonCommands.Context ctx) {
        String[] args = ctx.args();
        if (args.length <= 1) return starting(BAR_ACTIONS, args.length == 0 ? "" : args[0]);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (action.equals("show") || action.equals("hide") || action.equals("reset"))) {
            return starting(visibleNames(ctx.sender(), true), args[1]);
        }
        if (args.length == 3 && (action.equals("show") || action.equals("hide"))) {
            return starting(List.of("30", "1m", "5m", "1h", "1d"), args[2]);
        }
        return List.of();
    }

    private List<String> visibleNames(CommandSender sender, boolean excludeSelf) {
        List<String> names = new ArrayList<>();
        for (Player online : getServer().getOnlinePlayers()) {
            if (excludeSelf && online.equals(sender)) continue;
            if (sender instanceof Player viewer && !viewer.canSee(online)) continue;
            names.add(online.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static List<String> starting(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    // -- membership events -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        for (String partyName : book.invitesFor(event.getPlayer().getUniqueId())) {
            tell(event.getPlayer(), "&eYou have been invited to join party " + partyName + ".");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        // Members stay in their party across logouts; only the invites this player sent lapse.
        book.dropInvitesFrom(event.getPlayer().getUniqueId());
    }
}
