package codes.castled.allium.legacy;

import codes.castled.allium.AlliumRuntime;
import codes.castled.allium.ServerVersion;
import codes.castled.allium.legacy.LegacyPlayerSettings.Setting;
import codes.castled.allium.messaging.ReplyTargets;
import codes.castled.allium.portable.Homes;
import codes.castled.allium.portable.PlayerNameSearch;
import codes.castled.allium.portable.SpectateCommands;
import codes.castled.allium.portable.UtilityMessages;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure Java 8 compatibility runtime for Minecraft 1.12.2 through 1.19.4 (Bukkit, Spigot, Paper,
 * Purpur) inside the unified Allium jar.
 */
public final class LegacyBukkitRuntime implements AlliumRuntime, Listener {

    private static final long TPA_EXPIRY_MILLIS = 60_000L;

    private static final class TpaRequest {
        final UUID sender;
        final UUID target;
        final boolean here;
        final long expiresAt;

        TpaRequest(UUID sender, UUID target, boolean here, long expiresAt) {
            this.sender = sender;
            this.target = target;
            this.here = here;
            this.expiresAt = expiresAt;
        }
    }

    private final JavaPlugin plugin;
    private final ServerVersion version;
    private final int javaMajor;
    private final LegacyConfigManager configManager;
    private final LegacyLanguageManager languageManager;
    private final LegacyMessageManager messageManager;
    private final LegacyCommandManager commandManager;
    private final LegacyHelpCommand helpCommand;
    private final LegacyEconomyModule economy;
    private final LegacyKitsModule kits;
    private final LegacyModerationModule moderation;
    private final LegacyPlayerInfoModule playerInfo;
    private final LegacyChatModule chat;
    private final LegacyHideModule hide;
    private final LegacyTravelModule travel;
    private final LegacyUtilitiesModule utilities;
    private final LegacyTextModule text;
    private final LegacyChatClear chatClear;
    private final LegacyCompanionTeleports companions;
    private final LegacyPlayerSettings settings;
    private final LegacyEditSignCommand editSign;
    private final LegacyUnlimitedCommand unlimited;
    private final LegacyOfflineTeleport offlineTeleport;
    private final LegacyRedeemCommand redeem;
    private final LegacyAutoRestart autorestart;
    private final LegacyInventoryClear inventoryClear;
    private final LegacyTimedFlight flight;
    private final LegacyFlightProtection flightProtection;
    private final LegacyExperienceCommand experience;
    private final LegacyXpBottle xpBottle;
    private final LegacyStaffReports staffReports;
    private final LegacyExplosiveCommands explosiveCommands;
    private final Set<UUID> godPlayers = new HashSet<>();
    private ReplyTargets replyTargets = new ReplyTargets();
    private final Map<UUID, Location> backLocations = new HashMap<>();
    private final Map<UUID, TpaRequest> incomingTpa = new HashMap<>();
    private final Map<String, Location> warps = new LinkedHashMap<>();
    private Location spawnLocation;

    public LegacyBukkitRuntime(JavaPlugin plugin, ServerVersion version, int javaMajor) {
        this.plugin = plugin;
        this.version = version;
        this.javaMajor = javaMajor;
        this.configManager = new LegacyConfigManager(plugin);
        this.languageManager = new LegacyLanguageManager(plugin, configManager);
        this.messageManager = new LegacyMessageManager(plugin, configManager, languageManager, version);
        this.editSign = new LegacyEditSignCommand(plugin, messageManager);
        this.commandManager = new LegacyCommandManager(plugin, configManager);
        this.redeem = new LegacyRedeemCommand(plugin, configManager, commandManager, messageManager);
        this.autorestart = new LegacyAutoRestart(plugin, configManager, messageManager);
        this.helpCommand = new LegacyHelpCommand(commandManager, messageManager);
        this.economy = new LegacyEconomyModule(plugin, configManager, messageManager);
        this.settings = new LegacyPlayerSettings(plugin, commandManager, economy, messageManager);
        this.inventoryClear = new LegacyInventoryClear(plugin, settings, messageManager, this::canSeeTeleportTarget);
        this.unlimited = new LegacyUnlimitedCommand(plugin, settings, commandManager, messageManager);
        this.kits = new LegacyKitsModule(plugin, messageManager);
        this.moderation = new LegacyModerationModule(plugin, messageManager);
        this.offlineTeleport = new LegacyOfflineTeleport(plugin, configManager, moderation, messageManager,
                this::canSeeTeleportTarget);
        this.chatClear = new LegacyChatClear(plugin, configManager, messageManager, this::canSeeTeleportTarget);
        this.companions = new LegacyCompanionTeleports(plugin, configManager, messageManager, version,
                this::findTeleportPlayer, this::canSeeTeleportTarget);
        this.playerInfo = new LegacyPlayerInfoModule(plugin, moderation, economy, messageManager);
        this.staffReports = new LegacyStaffReports(plugin, configManager, messageManager, playerInfo, this::canSeeTeleportTarget);
        this.explosiveCommands = new LegacyExplosiveCommands(plugin, messageManager, this::canSeeTeleportTarget);
        this.flightProtection = new LegacyFlightProtection(plugin, settings, messageManager);
        this.flight = new LegacyTimedFlight(plugin, configManager, playerInfo, messageManager, flightProtection, this::canSeeTeleportTarget);
        this.experience = new LegacyExperienceCommand(plugin, messageManager, this::canSeeTeleportTarget);
        this.xpBottle = new LegacyXpBottle(plugin, messageManager, this::canSeeTeleportTarget);
        this.chat = new LegacyChatModule(plugin, configManager, messageManager, moderation, playerInfo, version,
                this::canSeeTeleportTarget);
        this.hide = new LegacyHideModule(plugin, configManager, messageManager, commandManager,
                this::canSeeTeleportTarget);
        this.commandManager.setHideModule(this.hide);
        this.travel = new LegacyTravelModule(plugin, this);
        this.utilities = new LegacyUtilitiesModule(plugin, this);
        this.text = new LegacyTextModule(plugin, configManager, moderation);
    }

    @Override
    public void onLoad() {
        // Nothing required during onLoad in legacy compatibility mode.
    }

    @Override
    public void onEnable() {
        long startupStartTime = System.currentTimeMillis();
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        configManager.loadAll();
        languageManager.load();
        messageManager.enable();
        commandManager.enable();
        loadWarps();
        loadSpawn();
        economy.enable();
        settings.reload();
        offlineTeleport.reload();
        kits.enable();
        moderation.enable();
        playerInfo.enable();
        staffReports.reload();
        flight.enable();
        xpBottle.enable();
        chat.enable();
        hide.enable();
        travel.enable();
        org.bukkit.plugin.PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(this, plugin);
        pm.registerEvents(economy, plugin);
        pm.registerEvents(kits, plugin);
        pm.registerEvents(moderation, plugin);
        pm.registerEvents(playerInfo, plugin);
        pm.registerEvents(chat, plugin);
        pm.registerEvents(hide, plugin);
        pm.registerEvents(utilities, plugin);
        pm.registerEvents(explosiveCommands, plugin);
        pm.registerEvents(text, plugin);
        pm.registerEvents(unlimited, plugin);
        pm.registerEvents(offlineTeleport, plugin);
        pm.registerEvents(redeem, plugin);
        pm.registerEvents(xpBottle, plugin);
        pm.registerEvents(companions, plugin);
        text.reload();
        autorestart.reload();
        String reason = version.isAtLeast(1, 20, 0)
                ? "Java " + javaMajor + " detected (Java 17+ required for full 1.20+ modules)"
                : "Minecraft " + version + " (full modern modules activate on 1.20+ with Java 17+)";
        plugin.getLogger().info("Allium v" + plugin.getDescription().getVersion()
                + " enabled in 1.12.2-1.19.4 compatibility mode on "
                + LegacyCompat.platformName(plugin.getServer()) + " — " + reason + ".");
        String langCode = languageManager.getActiveLocale();
        String authorTag = languageManager.getText("core.startup.author-tag", "&7by castledking");
        String pluginTag = languageManager.getText("core.startup.plugin-tag", "&dThe modern essentials suite for Minecraft servers");
        String startupDetails = languageManager.getText("core.startup.details", "&fStartup details: {0}").replace("{0}", "");
        String bootTime = "&a" + Math.max(0L, System.currentTimeMillis() - startupStartTime) + "ms";
        String bootFinished = languageManager.getText("core.startup.boot-finished", "&7Boot finished in: &a{0}").replace("{0}", bootTime);
        int activeCmds = commandManager.activeCommandCount();
        codes.castled.allium.StartupHeader.StartupInfo info = codes.castled.allium.StartupHeader.StartupInfo.builder()
                .locale(langCode)
                .version(plugin.getDescription().getVersion())
                .authorTag(authorTag)
                .pluginTag(pluginTag)
                .startupDetailsHeader(startupDetails)
                .bootFinished(bootFinished)
                .hexColorSupported(codes.castled.allium.StartupHeader.supportsHexColors(plugin.getServer()))
                .addRow("Platform:", "&a" + codes.castled.allium.StartupHeader.detectPlatformVersion(plugin.getServer()),
                        "Runtime:", "&eLegacy (Java " + javaMajor + ")")
                .addRow("Modules:", "&a6/6",
                        "Commands:", "&a" + (activeCmds > 0 ? activeCmds : 150))
                .addRow("Locale:", "&a" + langCode,
                        "Messages loaded:", "&a" + languageManager.getLoadedEntryCount())
                .addRow("Storage:", "&aFlatFile",
                        "Warps/Kits:", "&a" + warps.size() + "/" + kits.kitCount())
                .build();
        codes.castled.allium.StartupHeader.display(plugin, info);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(economy);
        HandlerList.unregisterAll(kits);
        HandlerList.unregisterAll(moderation);
        HandlerList.unregisterAll(playerInfo);
        HandlerList.unregisterAll(chat);
        HandlerList.unregisterAll(hide);
        HandlerList.unregisterAll(utilities);
        HandlerList.unregisterAll(explosiveCommands);
        HandlerList.unregisterAll(text);
        HandlerList.unregisterAll(unlimited);
        HandlerList.unregisterAll(offlineTeleport);
        HandlerList.unregisterAll(redeem);
        HandlerList.unregisterAll(xpBottle);
        HandlerList.unregisterAll(companions);
        text.stop();
        chatClear.stop();
        autorestart.close();
        inventoryClear.reset();
        flight.close();
        flightProtection.close();
        explosiveCommands.close();
        companions.stop();
        messageManager.disable();
        economy.disable();
        kits.disable();
        moderation.disable();
        hide.disable();
        chat.disable();
        playerInfo.disable();
        travel.disable();
        utilities.disable();
        godPlayers.clear();
        replyTargets = new ReplyTargets();
        editSign.clear();
        backLocations.clear();
        incomingTpa.clear();
        warps.clear();
        spawnLocation = null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        boolean protectedLanding = event.getCause() == EntityDamageEvent.DamageCause.FALL && flightProtection.consumeLanding(player);
        if (protectedLanding || godPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        flightProtection.forget(event.getPlayer());
        if (event.getFrom() != null && event.getFrom().getWorld() != null) {
            backLocations.put(event.getPlayer().getUniqueId(), event.getFrom().clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        flight.forget(player);
        flightProtection.forget(player);
        if (player.getLocation() != null && player.getLocation().getWorld() != null) {
            backLocations.put(player.getUniqueId(), player.getLocation().clone());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        flight.forget(event.getPlayer());
        flightProtection.forget(event.getPlayer());
        godPlayers.remove(id);
        chatClear.forget(id);
        inventoryClear.forget(id);
        incomingTpa.remove(id);
        replyTargets.forget(id);
        editSign.forget(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (commandManager.isDisabled(name) || commandManager.isDisabled(label)) {
            messageManager.send(sender, "unknown-command",
                    "{prefix} &cUnknown command: /{cmd}. Type &f/help &cfor help.", "{cmd}", label);
            return true;
        }
        if (commandManager.isModuleDisabled(name)) {
            String mod = commandManager.moduleOf(name);
            send(sender, ChatColor.RED + "Module '" + mod + "' is disabled in config.yml.");
            return true;
        }
        if ("help".equals(name)) {
            return helpCommand.execute(sender, args);
        }
        if (messageManager.handles(name)) {
            return messageManager.onCommand(sender, args);
        }
        if (text.handles(name)) return text.execute(sender, name, args);
        Homes.Action homeAction = homeAction(name);
        if (homeAction != null) {
            return handleHome(sender, homeAction, args);
        }
        switch (name) {
            case "allium":
                return handleAllium(sender, args);
            case "redeem":
                return redeem.execute(sender, args);
            case "autorestart":
                return autorestart.execute(sender, args);
            case "voterestart":
                return autorestart.voteCommand(sender, args);
            case "ping":
                return handlePing(sender, args);
            case "coords":
                return handleCoords(sender, args);
            case "depth":
                return handleDepth(sender);
            case "setwarp":
                return handleSetWarp(sender, args);
            case "warp":
                return handleWarp(sender, args);
            case "delwarp":
                return handleDelWarp(sender, args);
            case "warps":
                return handleWarps(sender);
            case "setspawn":
                return handleSetSpawn(sender);
            case "spawn":
                return handleSpawn(sender, args);
            case "tpa":
                return handleTpa(sender, args, false);
            case "tpahere":
                return handleTpa(sender, args, true);
            case "tpaccept":
                return handleTpAccept(sender);
            case "tpdeny":
                return handleTpDeny(sender);
            case "tpacancel":
                return handleTpCancel(sender);
            case "tp":
                return handleTp(sender, args);
            case "tphere":
                return handleTpHere(sender, args);
            case "tppet":
                return companions.execute(sender, true, args);
            case "tpent":
                return companions.execute(sender, false, args);
            case "spectate":
                return handleSpectate(sender, args);
            case "tpoffline":
                return offlineTeleport.execute(sender, args);
            case "back":
                return handleBack(sender);
            case "msg":
                return handleMsg(sender, args);
            case "reply":
                return handleReply(sender, args);
            case "rtoggle":
                return handleReplyToggle(sender, args);
            case "msgtoggle":
                return settings.direct(sender, Setting.MSG, args);
            case "tptoggle":
                return settings.direct(sender, Setting.TPA, args);
            case "tpauto":
                return settings.direct(sender, Setting.TP_AUTO, args);
            case "paytoggle":
            case "payconfirmtoggle":
                return economy.changePaymentPreference(sender, name, label, args);
            case "toggle":
                return settings.execute(sender, true, args);
            case "toggles":
                return settings.execute(sender, false, args);
            case "editsign":
                return editSign.execute(sender, args);
            case "clearchat":
                return chatClear.execute(sender, args);
            case "unlimited":
                return unlimited.execute(sender, args);
            case "heal":
                return handleHeal(sender, args);
            case "feed":
                return handleFeed(sender, args);
            case "fly":
                return flight.execute(sender, args);
            case "toggleslowfall":
                return flightProtection.execute(sender, args);
            case "xp":
                return experience.execute(sender, args);
            case "xpbottle":
                return xpBottle.execute(sender, args);
            case "report":
                return staffReports.report(sender, args);
            case "helpop":
                return staffReports.help(sender, args);
            case "escalate":
                return staffReports.escalate(sender, LegacyStaffReports.actor(sender), args, 0);
            case "beezooka":
            case "kittycannon":
                return explosiveCommands.cannon(sender, name, args);
            case "nuke":
                return explosiveCommands.nuke(sender, args);
            case "god":
                return handleGod(sender, args);
            case "gamemode":
                return handleGameMode(sender, label, args);
            case "speed":
                return handleSpeed(sender, label, args);
            case "clearinventory":
                return inventoryClear.execute(sender, args);
            case "clearinventoryconfirmtoggle":
                return inventoryClear.toggleConfirmation(sender, label, args);
            case "enderchest":
                return handleEnderChest(sender, args);
            case "workbench":
                return handleWorkbench(sender);
            case "hat":
                return handleHat(sender);
            case "time":
                return handleTime(sender, args);
            case "day":
                return setWorldTime(sender, args.length > 0 ? args[0] : null, 1000L, "day");
            case "night":
                return setWorldTime(sender, args.length > 0 ? args[0] : null, 13000L, "night");
            case "weather":
                return handleWeather(sender, label, args);
            case "thunder":
                return setWorldWeather(sender, args.length > 0 ? args[0] : null, "storm");
            case "broadcast":
                return handleBroadcast(sender, args);
            default:
                if (economy.handles(name)) return economy.onCommand(sender, name, args);
                if (kits.handles(name)) return kits.onCommand(sender, name, args);
                if (travel.handles(name)) return travel.onCommand(sender, name, args);
                if (moderation.handles(name)) return moderation.onCommand(sender, name, args);
                if (chat.handles(name)) return chat.onCommand(sender, name, label, args);
                if (playerInfo.handles(name)) return playerInfo.onCommand(sender, name, args);
                if (utilities.handles(name)) return utilities.onCommand(sender, name, args);
                send(sender, ChatColor.YELLOW + "Command /" + label
                        + " requires Minecraft 1.20+ with Java 17+ (running Allium compatibility mode on Minecraft "
                        + version + ").");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ("redeem".equals(name)) return Collections.emptyList();
        if (commandManager.isDisabled(name) || commandManager.isDisabled(alias) || commandManager.isModuleDisabled(name)) {
            return Collections.emptyList();
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if ("autorestart".equals(name) || "voterestart".equals(name)) {
            return autorestart.complete(sender, "voterestart".equals(name), args, prefix);
        }
        if ("toggle".equals(name)) return settings.complete(sender, args);
        if ("fly".equals(name)) return flight.complete(sender, args, prefix);
        if ("toggleslowfall".equals(name)) return flightProtection.complete(sender, args, prefix);
        if ("xp".equals(name)) return experience.complete(sender, args, prefix);
        if ("xpbottle".equals(name)) return xpBottle.complete(sender, args, prefix);
        if ("report".equals(name) || "escalate".equals(name)) {
            return args.length == 1 ? staffReports.complete(sender, name, prefix) : Collections.emptyList();
        }
        if ("helpop".equals(name)) return Collections.emptyList();
        if ("beezooka".equals(name) || "kittycannon".equals(name)) return Collections.emptyList();
        if ("nuke".equals(name)) return explosiveCommands.completeNuke(sender, args, prefix);
        if ("clearinventory".equals(name)) return inventoryClear.complete(sender, args, prefix);
        if ("clearinventoryconfirmtoggle".equals(name)) return Collections.emptyList();
        if ("clearchat".equals(name) && args.length == 1) return chatClear.complete(sender, prefix);
        if ("paytoggle".equals(name) || "payconfirmtoggle".equals(name)) {
            return economy.completePaymentPreference(sender, name, alias, args, prefix);
        }
        if ("editsign".equals(name)) {
            return command.testPermissionSilent(sender) ? editSign.complete(args, prefix) : Collections.emptyList();
        }
        if ("unlimited".equals(name) && args.length == 1) return unlimited.complete(sender, prefix);

        if ("help".equals(name)) {
            return helpCommand.complete(sender, args);
        }
        if (messageManager.handles(name)) {
            return messageManager.onTabComplete(args, prefix);
        }

        if (text.handles(name)) {
            return command.testPermissionSilent(sender) ? text.complete(sender, name, args) : Collections.emptyList();
        }
        Homes.Action homeAction = homeAction(name);
        if (homeAction != null) {
            if (homeAction.suggestsNames() && args.length == 1 && sender instanceof Player
                    && hasHomePermission(sender, homeAction)) {
                return homes((Player) sender, false).suggestions(args[0]);
            }
            return Collections.emptyList();
        }
        if ("spectate".equals(name) && args.length == 1) {
            return sender.hasPermission("allium.spectate") ? teleportPlayerSuggestions(sender, prefix) : Collections.emptyList();
        }
        if ("tpoffline".equals(name) && args.length == 1) return offlineTeleport.complete(sender, prefix);
        if ("tppet".equals(name) && args.length == 1) return companions.complete(sender, true, prefix);
        if ("tpent".equals(name) && args.length == 1) return companions.complete(sender, false, prefix);
        if ("allium".equals(name)) {
            if (args.length == 1) {
                List<String> subs = new ArrayList<String>(Arrays.asList("version", "help", "reload"));
                if (sender.hasPermission(LegacyHideModule.ADMIN) || sender.isOp()) {
                    subs.add("hide");
                }
                if (sender instanceof ConsoleCommandSender && sender.hasPermission("allium.escalate")
                        && !commandManager.isDisabled("escalate") && !commandManager.isModuleDisabled("escalate")) {
                    subs.add("escalate");
                }
                return filterPrefix(subs, prefix);
            }
            if (args.length >= 2 && "hide".equalsIgnoreCase(args[0])) {
                return hide.completeAdmin(sender, Arrays.copyOfRange(args, 1, args.length));
            }
            return Collections.emptyList();
        }
        if (("warp".equals(name) || "delwarp".equals(name)) && args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String warp : warps.keySet()) {
                if ("delwarp".equals(name) || canUseWarp(sender, warp)) {
                    if (warp.startsWith(prefix)) out.add(warp);
                }
            }
            Collections.sort(out);
            return out;
        }
        if ("gamemode".equals(name) && args.length == 1 && isGenericGamemodeLabel(alias)) {
            return filterPrefix(Arrays.asList("survival", "creative", "adventure", "spectator"), prefix);
        }
        if ("time".equals(name) && args.length == 1) {
            return filterPrefix(Arrays.asList("day", "noon", "night", "midnight"), prefix);
        }
        if ("weather".equals(name) && args.length == 1) {
            return filterPrefix(Arrays.asList("sun", "rain", "storm"), prefix);
        }
        if ((args.length == 1 && Arrays.asList("tpa", "tpahere", "tp", "tphere").contains(name))
                || (args.length == 2 && "tp".equals(name))) {
            if (!command.testPermissionSilent(sender) || args.length == 2 && !sender.hasPermission("allium.tp.others")) {
                return Collections.emptyList();
            }
            return teleportPlayerSuggestions(sender, prefix);
        }
        if (args.length == 1 && Arrays.asList("ping", "coords", "msg", "heal", "feed", "god",
                "clearinventory", "enderchest", "spawn").contains(name)) {
            return onlinePlayerSuggestions(prefix);
        }
        if (args.length == 2 && ("gamemode".equals(name) || "speed".equals(name))) {
            return onlinePlayerSuggestions(prefix);
        }
        if (economy.handles(name)) return economy.onTabComplete(sender, name, args, prefix);
        if (kits.handles(name)) return kits.onTabComplete(sender, name, args, prefix);
        if (travel.handles(name)) return travel.onTabComplete(sender, name, args, prefix);
        if (moderation.handles(name)) return moderation.onTabComplete(sender, name, args, prefix);
        if (chat.handles(name)) return chat.onTabComplete(sender, name, alias, args, prefix);
        if (playerInfo.handles(name)) return playerInfo.onTabComplete(sender, name, args, prefix);
        if (utilities.handles(name)) return utilities.onTabComplete(sender, name, args, prefix);
        return Collections.emptyList();
    }

    // ==================== Core & utility commands ====================

    private boolean handleAllium(CommandSender sender, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            send(sender, ChatColor.GOLD + "Allium " + plugin.getDescription().getVersion()
                    + ChatColor.GRAY + " (1.12.2-1.19.4 compatibility mode on Minecraft " + version + ")");
            send(sender, ChatColor.YELLOW + "Commands: " + ChatColor.WHITE
                    + "/allium version, /ping, /coords, /depth, /sethome, /home, /delhome, /homes, "
                    + "/setwarp, /warp, /delwarp, /warps, /setspawn, /spawn, /tpa, /tpahere, /tpaccept, "
                    + "/tpdeny, /tpacancel, /tp, /tphere, /back, /msg, /reply, /msgtoggle, /heal, /feed, "
                    + "/fly, /god, /gamemode, /speed, /clear, /enderchest, /workbench, /hat, /time, /weather, "
                    + "/broadcast, /rules, /motd");
            return true;
        }
        if ("version".equalsIgnoreCase(args[0])) {
            send(sender, UtilityMessages.version(
                    plugin.getDescription().getVersion(),
                    LegacyCompat.platformName(plugin.getServer()),
                    version.raw));
            return true;
        }
        if ("hide".equalsIgnoreCase(args[0])) {
            return hide.handleAdmin(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if ("escalate".equalsIgnoreCase(args[0])) {
            if (!(sender instanceof ConsoleCommandSender)) {
                messageManager.send(sender, "error.console-only", "{prefix} &cThis command can only be used from the console.");
            } else if (commandManager.isDisabled("escalate") || commandManager.isModuleDisabled("escalate")) {
                messageManager.send(sender, "error.module-disabled", "{prefix} &cThis feature is currently disabled.");
            } else if (args.length < 4 || !staffReports.escalate(sender, args[1], args, 2)) {
                send(sender, ChatColor.RED + "Usage: /allium escalate <staffmember> <target> <reason>");
            }
            return true;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("allium.reload") && !sender.isOp()) {
                messageManager.send(sender, "no-permission",
                        "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", "allium reload");
                return true;
            }
            configManager.reloadAll();
            languageManager.reload();
            messageManager.reload();
            commandManager.reload();
            loadWarps();
            loadSpawn();
            economy.reload();
            settings.reload();
            inventoryClear.reset();
            offlineTeleport.reload();
            kits.reload();
            travel.reload();
            moderation.reload();
            staffReports.reload();
            playerInfo.reload();
            chat.reload();
            hide.reload();
            text.reload();
            autorestart.reload();
            messageManager.send(sender, "core.reload-success", "{prefix} &aConfiguration reloaded.");
            return true;
        }
        send(sender, ChatColor.RED + "Usage: /allium [version|help|reload|hide]");
        return true;
    }

    private boolean handlePing(CommandSender sender, String[] args) {
        if (args.length > 1) {
            send(sender, ChatColor.RED + "Usage: /ping [player]");
            return true;
        }
        Player target = resolveTarget(sender, args, 0, "allium.ping.others");
        if (target == null) return true;
        int latency = LegacyCompat.ping(target);
        if (latency < 0) {
            send(sender, ChatColor.RED + "Ping is unavailable on this server.");
        } else {
            send(sender, UtilityMessages.ping(target.getName(), latency));
        }
        return true;
    }

    private boolean handleCoords(CommandSender sender, String[] args) {
        if (args.length > 1) {
            send(sender, ChatColor.RED + "Usage: /coords [player]");
            return true;
        }
        Player target = resolveTarget(sender, args, 0, "allium.getpos.others");
        if (target == null) return true;
        Location loc = target.getLocation();
        send(sender, UtilityMessages.coordinates(
                target.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), loc.getWorld().getName()));
        return true;
    }

    private boolean handleDepth(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        int sea = player.getWorld().getSeaLevel();
        int diff = player.getLocation().getBlockY() - sea;
        if (diff == 0) {
            send(player, ChatColor.GRAY + "You are at sea level (Y=" + sea + ").");
        } else if (diff > 0) {
            send(player, ChatColor.GRAY + "You are " + diff + " block(s) above sea level (Y=" + player.getLocation().getBlockY() + ").");
        } else {
            send(player, ChatColor.GRAY + "You are " + (-diff) + " block(s) below sea level (Y=" + player.getLocation().getBlockY() + ").");
        }
        return true;
    }

    // ==================== Homes ====================

    private boolean handleHome(CommandSender sender, Homes.Action action, String[] args) {
        if (!hasHomePermission(sender, action)) {
            send(sender, ChatColor.RED + "You do not have permission to use /" + action.command + ".");
            return true;
        }
        if (!(sender instanceof Player)) {
            send(sender, Homes.PLAYERS_ONLY);
            return true;
        }
        if (args.length > (action.acceptsName() ? 1 : 0)) {
            send(sender, ChatColor.RED + "Usage: /" + action.command + (action.acceptsName() ? " [name]" : ""));
            return true;
        }
        Player player = (Player) sender;
        homes(player, action == Homes.Action.SET).execute(
                action, args.length == 0 ? null : args[0], msg -> send(sender, msg));
        return true;
    }

    private static boolean hasHomePermission(CommandSender sender, Homes.Action action) {
        String perm = "allium." + action.command;
        if (sender.isPermissionSet(perm)) {
            return sender.hasPermission(perm);
        }
        return true;
    }

    private static Homes.Action homeAction(String commandName) {
        for (Homes.Action action : Homes.Action.values()) {
            if (action.command.equals(commandName)) return action;
        }
        return null;
    }

    private Homes homes(Player player, boolean setting) {
        return new Homes(plugin.getDataFolder().toPath().resolve("homes"), player.getUniqueId(),
                setting ? homeLimit(player) : Homes.DEFAULT_LIMIT, () -> {
                    Location loc = player.getLocation();
                    return new Homes.Position(loc.getWorld().getUID().toString(), loc.getX(), loc.getY(), loc.getZ(),
                            loc.getYaw(), loc.getPitch());
                }, point -> {
                    World world = resolveWorld(point.world);
                    return world != null && player.teleport(new Location(world, point.x, point.y, point.z,
                            point.yaw, point.pitch));
                });
    }

    private World resolveWorld(String idOrName) {
        try {
            World byUuid = plugin.getServer().getWorld(UUID.fromString(idOrName));
            if (byUuid != null) return byUuid;
        } catch (IllegalArgumentException ignored) {
        }
        return plugin.getServer().getWorld(idOrName);
    }

    private static int homeLimit(Player player) {
        if (player.hasPermission("allium.sethome.unlimited") || player.hasPermission("allium.sethome.*")) {
            return Homes.MAX_LIMIT;
        }
        int limit = Homes.DEFAULT_LIMIT;
        for (String prefix : Arrays.asList("allium.sethome.multiple.", "allium.sethome.")) {
            for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                String node = info.getPermission();
                if (info.getValue() && node.startsWith(prefix)) {
                    try {
                        limit = Math.max(limit, Integer.parseInt(node.substring(prefix.length())));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return limit;
    }

    // ==================== Warps & Spawn ====================

    private boolean handleSetWarp(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        if (!sender.hasPermission("allium.setwarp") && !sender.isOp()) {
            send(sender, ChatColor.RED + "You do not have permission to set warps.");
            return true;
        }
        if (args.length != 1 || !args[0].matches("[A-Za-z0-9_-]{1,32}")) {
            send(sender, ChatColor.RED + "Usage: /setwarp <name>");
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        warps.put(key, player.getLocation().clone());
        saveWarps();
        send(sender, ChatColor.GREEN + "Warp '" + key + "' set.");
        return true;
    }

    private boolean handleWarp(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        if (args.length != 1) {
            return handleWarps(sender);
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        Location dest = warps.get(key);
        if (dest == null || dest.getWorld() == null) {
            send(sender, ChatColor.RED + "Warp '" + key + "' was not found.");
            return true;
        }
        if (!canUseWarp(sender, key)) {
            send(sender, ChatColor.RED + "You do not have permission to use warp '" + key + "'.");
            return true;
        }
        player.teleport(dest);
        send(sender, ChatColor.GREEN + "Warped to '" + key + "'.");
        return true;
    }

    private boolean handleDelWarp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("allium.delwarp") && !sender.isOp()) {
            send(sender, ChatColor.RED + "You do not have permission to delete warps.");
            return true;
        }
        if (args.length != 1) {
            send(sender, ChatColor.RED + "Usage: /delwarp <name>");
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        if (warps.remove(key) == null) {
            send(sender, ChatColor.RED + "Warp '" + key + "' was not found.");
            return true;
        }
        saveWarps();
        send(sender, ChatColor.GREEN + "Warp '" + key + "' deleted.");
        return true;
    }

    private boolean handleWarps(CommandSender sender) {
        List<String> visible = new ArrayList<>();
        for (String name : warps.keySet()) {
            if (canUseWarp(sender, name)) visible.add(name);
        }
        Collections.sort(visible);
        send(sender, visible.isEmpty()
                ? ChatColor.GRAY + "No warps are available."
                : ChatColor.GOLD + "Warps: " + ChatColor.WHITE + String.join(", ", visible));
        return true;
    }

    private static boolean canUseWarp(CommandSender sender, String warp) {
        if (sender.isOp() || sender.hasPermission("allium.warps.*") || sender.hasPermission("allium.warps." + warp)) {
            return true;
        }
        return sender.isPermissionSet("allium.warp") && sender.hasPermission("allium.warp");
    }

    private boolean handleSetSpawn(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        spawnLocation = player.getLocation().clone();
        player.getWorld().setSpawnLocation(spawnLocation.getBlockX(), spawnLocation.getBlockY(), spawnLocation.getBlockZ());
        saveSpawn();
        send(sender, ChatColor.GREEN + "Server spawn set.");
        return true;
    }

    private boolean handleSpawn(CommandSender sender, String[] args) {
        if (args.length > 1) {
            send(sender, ChatColor.RED + "Usage: /spawn [player]");
            return true;
        }
        Player target = resolveTarget(sender, args, 0, "allium.spawn.others");
        if (target == null) return true;
        Location dest = spawnLocation;
        if (dest == null || dest.getWorld() == null) {
            List<World> worlds = plugin.getServer().getWorlds();
            if (worlds.isEmpty()) {
                send(sender, ChatColor.RED + "No spawn world is loaded.");
                return true;
            }
            dest = worlds.get(0).getSpawnLocation();
        }
        target.teleport(dest);
        send(target, ChatColor.GREEN + "Teleported to spawn.");
        if (!target.equals(sender)) {
            send(sender, ChatColor.GREEN + "Sent " + target.getName() + " to spawn.");
        }
        return true;
    }

    // ==================== Teleport & TPA ====================

    private boolean handleTpa(CommandSender sender, String[] args, boolean here) {
        Player from = requirePlayer(sender);
        if (from == null) return true;
        if (args.length != 1) {
            send(sender, ChatColor.RED + "Usage: /" + (here ? "tpahere" : "tpa") + " <player>");
            return true;
        }
        Player target = findTeleportPlayer(sender, args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }
        if (target.equals(from)) {
            send(sender, ChatColor.RED + "You cannot send a teleport request to yourself.");
            return true;
        }
        if (!settings.enabled(target.getUniqueId(), Setting.TPA) && !from.hasPermission("allium.tptoggle.bypass")) {
            send(sender, ChatColor.RED + target.getName() + " is not accepting teleport requests.");
            return true;
        }
        sendTpaRequest(from, target, here);
        return true;
    }

    void sendTpaRequest(Player from, Player target, boolean here) {
        if (settings.enabled(target.getUniqueId(), Setting.TP_AUTO)) {
            if (here) {
                target.teleport(from.getLocation());
                send(target, ChatColor.GREEN + "Auto-accepted teleport to " + from.getName() + ".");
                send(from, ChatColor.GREEN + target.getName() + " auto-accepted your teleport request.");
            } else {
                from.teleport(target.getLocation());
                send(from, ChatColor.GREEN + "Teleported to " + target.getName() + " (auto-accepted).");
                send(target, ChatColor.GREEN + "Auto-accepted teleport request from " + from.getName() + ".");
            }
            return;
        }
        incomingTpa.put(target.getUniqueId(), new TpaRequest(
                from.getUniqueId(), target.getUniqueId(), here, System.currentTimeMillis() + TPA_EXPIRY_MILLIS));
        send(from, ChatColor.GREEN + "Teleport request sent to " + target.getName() + ".");
        send(target, ChatColor.GOLD + from.getName() + (here
                ? " wants you to teleport to them. Use /tpaccept or /tpdeny."
                : " wants to teleport to you. Use /tpaccept or /tpdeny."));
    }

    Homes homesFor(Player player) {
        return homes(player, true);
    }

    List<String> homeSuggestions(Player player, String prefix) {
        return homes(player, false).suggestions(prefix);
    }

    Location getWarp(String name) {
        return name == null ? null : warps.get(name.toLowerCase(Locale.ROOT));
    }

    List<String> warpSuggestions(String prefix) {
        List<String> out = new ArrayList<>();
        for (String w : warps.keySet()) {
            if (w.startsWith(prefix)) out.add(w);
        }
        Collections.sort(out);
        return out;
    }

    private boolean handleTpAccept(CommandSender sender) {
        Player target = requirePlayer(sender);
        if (target == null) return true;
        TpaRequest req = incomingTpa.remove(target.getUniqueId());
        if (req == null || req.expiresAt < System.currentTimeMillis()) {
            send(target, ChatColor.RED + "You have no pending teleport requests.");
            return true;
        }
        Player from = plugin.getServer().getPlayer(req.sender);
        if (from == null || !from.isOnline()) {
            send(target, ChatColor.RED + "That player is no longer online.");
            return true;
        }
        if (req.here) {
            target.teleport(from.getLocation());
            send(target, ChatColor.GREEN + "Teleported to " + from.getName() + ".");
            send(from, ChatColor.GREEN + target.getName() + " accepted your teleport request.");
        } else {
            from.teleport(target.getLocation());
            send(from, ChatColor.GREEN + "Teleported to " + target.getName() + ".");
            send(target, ChatColor.GREEN + "Accepted teleport request from " + from.getName() + ".");
        }
        return true;
    }

    private boolean handleTpDeny(CommandSender sender) {
        Player target = requirePlayer(sender);
        if (target == null) return true;
        TpaRequest req = incomingTpa.remove(target.getUniqueId());
        if (req == null || req.expiresAt < System.currentTimeMillis()) {
            send(target, ChatColor.RED + "You have no pending teleport requests.");
            return true;
        }
        send(target, ChatColor.YELLOW + "Teleport request denied.");
        Player from = plugin.getServer().getPlayer(req.sender);
        if (from != null && from.isOnline()) {
            send(from, ChatColor.RED + target.getName() + " denied your teleport request.");
        }
        return true;
    }

    private boolean handleTpCancel(CommandSender sender) {
        Player from = requirePlayer(sender);
        if (from == null) return true;
        int removed = 0;
        for (UUID key : new ArrayList<>(incomingTpa.keySet())) {
            TpaRequest req = incomingTpa.get(key);
            if (req != null && req.sender.equals(from.getUniqueId())) {
                incomingTpa.remove(key);
                removed++;
            }
        }
        send(from, removed > 0
                ? ChatColor.YELLOW + "Cancelled your pending teleport request(s)."
                : ChatColor.RED + "You have no outgoing teleport requests.");
        return true;
    }

    private boolean handleTp(CommandSender sender, String[] args) {
        if (args.length == 1) {
            Player self = requirePlayer(sender);
            if (self == null) return true;
            Player dest = findTeleportPlayer(sender, args[0]);
            if (dest == null) {
                send(sender, ChatColor.RED + "Player '" + args[0] + "' is not online.");
                return true;
            }
            self.teleport(dest.getLocation());
            send(sender, ChatColor.GREEN + "Teleported to " + dest.getName() + ".");
            return true;
        }
        if (args.length == 2) {
            if (!sender.hasPermission("allium.tp.others")) {
                send(sender, ChatColor.RED + "You do not have permission to teleport other players.");
                return true;
            }
            Player subject = findTeleportPlayer(sender, args[0]);
            Player dest = findTeleportPlayer(sender, args[1]);
            if (subject == null || dest == null) {
                send(sender, ChatColor.RED + "Both players must be online.");
                return true;
            }
            subject.teleport(dest.getLocation());
            send(sender, ChatColor.GREEN + "Teleported " + subject.getName() + " to " + dest.getName() + ".");
            return true;
        }
        send(sender, ChatColor.RED + "Usage: /tp <player> [target]");
        return true;
    }

    private boolean handleTpHere(CommandSender sender, String[] args) {
        Player self = requirePlayer(sender);
        if (self == null) return true;
        if (args.length == 0 && companions.move(self, self.getLocation())) {
            return true;
        }
        if (args.length != 1) {
            send(sender, ChatColor.RED + "Usage: /tphere <player>");
            return true;
        }
        Player target = findTeleportPlayer(sender, args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }
        target.teleport(self.getLocation());
        send(sender, ChatColor.GREEN + "Teleported " + target.getName() + " to you.");
        return true;
    }

    boolean moveCompanions(Player player, Location destination) {
        return companions.move(player, destination);
    }

    void send(CommandSender sender, String message) {
        messageManager.sendPrefixed(sender, message);
    }

    private boolean handleSpectate(CommandSender sender, String[] args) {
        if (args.length != 1) return false;
        Player staff = requirePlayer(sender);
        if (staff == null) return true;
        if (!staff.hasPermission("allium.spectate")) {
            send(staff, ChatColor.RED + "You do not have permission to spectate players.");
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !canSeeTeleportTarget(staff, target)) {
            send(staff, ChatColor.RED + "That player is not online.");
            return true;
        }
        if (staff.getUniqueId().equals(target.getUniqueId())) {
            messageManager.send(staff, "chat.spectate-self", "{prefix} &cYou cannot spectate yourself.");
            return true;
        }
        String actorName = staff.getName();
        String targetName = target.getName();
        if (!actorName.matches("[A-Za-z0-9_.-]{1,32}") || !targetName.matches("[A-Za-z0-9_.-]{1,32}")) {
            send(staff, ChatColor.RED + "A player name cannot be used in the configured commands.");
            return true;
        }
        boolean dispatched = false;
        for (SpectateCommands.Invocation invocation : SpectateCommands.expand(
                configManager.raw().getStringList("spectate-commands"), actorName, targetName)) {
            boolean accepted = invocation.asPlayer ? staff.performCommand(invocation.command)
                    : plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), invocation.command);
            dispatched |= accepted;
        }
        if (dispatched) {
            plugin.getLogger().info("Spectate audit actor=" + staff.getUniqueId() + " target=" + target.getUniqueId());
            messageManager.send(staff, "chat.spectate-started", "{prefix} &aNow spectating &f{player}&a.", "{player}", targetName);
        } else {
            send(staff, ChatColor.RED + "None of the configured spectate commands ran.");
        }
        return true;
    }

    private boolean handleBack(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        Location loc = backLocations.get(player.getUniqueId());
        if (loc == null || loc.getWorld() == null) {
            send(player, ChatColor.RED + "You have no previous location to return to.");
            return true;
        }
        player.teleport(loc);
        send(player, ChatColor.GREEN + "Returned to your previous location.");
        return true;
    }

    // ==================== Messaging ====================

    private boolean handleMsg(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, ChatColor.RED + "Usage: /msg <player> <message>");
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }
        if (!settings.enabled(target.getUniqueId(), Setting.MSG) && !sender.hasPermission("allium.msg.bypass.toggle")) {
            send(sender, ChatColor.RED + target.getName() + " is not accepting private messages.");
            return true;
        }
        String text = joinArgs(args, 1);
        deliverPrivateMessage(sender, target, text);
        return true;
    }

    private boolean handleReply(CommandSender sender, String[] args) {
        Player self = requirePlayer(sender);
        if (self == null) return true;
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /reply <message>");
            return true;
        }
        UUID playerId = self.getUniqueId();
        Optional<Boolean> mode = settings.replyToLastRecipient(playerId);
        UUID targetId = (mode.isPresent() ? replyTargets.target(playerId, mode.get()) : replyTargets.target(playerId))
                .orElse(null);
        Player target = targetId == null ? null : plugin.getServer().getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            send(sender, ChatColor.RED + "No one to reply to.");
            return true;
        }
        if (!settings.enabled(target.getUniqueId(), Setting.MSG) && !sender.hasPermission("allium.msg.bypass.toggle")) {
            send(sender, ChatColor.RED + target.getName() + " is not accepting private messages.");
            return true;
        }
        deliverPrivateMessage(self, target, joinArgs(args, 0));
        return true;
    }

    private boolean handleReplyToggle(CommandSender sender, String[] args) {
        if (args.length != 0) return false;
        Player player = requirePlayer(sender);
        if (player == null) return true;
        if (!player.hasPermission("allium.rtoggle")) {
            send(player, ChatColor.RED + "You cannot change that setting.");
            return true;
        }
        UUID id = player.getUniqueId();
        boolean lastRecipient = !settings.replyToLastRecipient(id).orElse(false);
        if (!settings.setReplyToLastRecipient(id, lastRecipient)) {
            send(player, ChatColor.RED + "Your setting could not be saved.");
        } else if (lastRecipient) {
            messageManager.send(player, "utility.reply-last-recipient",
                    "{prefix} &aReply now targets the last player you messaged.");
        } else {
            messageManager.send(player, "utility.reply-last-sender",
                    "{prefix} &aReply now targets the last player who messaged you.");
        }
        return true;
    }

    private void deliverPrivateMessage(CommandSender sender, Player target, String text) {
        if (sender instanceof Player) {
            UUID fromId = ((Player) sender).getUniqueId();
            if (moderation.isMuted(fromId)) {
                send(sender, ChatColor.RED + "You cannot send private messages while muted.");
                return;
            }
            if (playerInfo.isIgnoring(target.getUniqueId(), fromId) && !sender.hasPermission("allium.ignore.bypass")) {
                send(sender, ChatColor.RED + target.getName() + " is ignoring you.");
                return;
            }
        }
        String fromName = sender instanceof Player ? sender.getName() : "Console";
        messageManager.sendPrivateMessageEntry(sender, "messaging.sent",
                "&7[&fme &7-> &f{recipient}&7] &f{message}", true,
                "{recipient}", target.getName(), "{message}", text);
        messageManager.sendPrivateMessageEntry(target, "messaging.received",
                "&7[&f{sender} &7-> &fme&7] &f{message}", false,
                "{sender}", fromName, "{message}", text);
        moderation.notifySocialSpy(sender, target, text);
        if (sender instanceof Player) {
            replyTargets.delivered(((Player) sender).getUniqueId(), target.getUniqueId());
        }
    }

    // ==================== Player & World Utilities ====================

    private boolean handleHeal(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 0, "allium.heal.others");
        if (target == null) return true;
        target.setHealth(LegacyCompat.maxHealth(target));
        target.setFoodLevel(20);
        target.setSaturation(20.0f);
        target.setFireTicks(0);
        messageManager.send(target, "player.heal", "{prefix} &aYou have been healed.");
        if (!target.equals(sender)) {
            messageManager.send(sender, "player.heal-other", "{prefix} &aHealed &f{player}&a.",
                    "{player}", target.getName());
        }
        return true;
    }

    private boolean handleFeed(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 0, "allium.feed.others");
        if (target == null) return true;
        target.setFoodLevel(20);
        target.setSaturation(20.0f);
        messageManager.send(target, "player.feed", "{prefix} &aYou have been fed.");
        if (!target.equals(sender)) {
            messageManager.send(sender, "player.feed-other", "{prefix} &aFed &f{player}&a.",
                    "{player}", target.getName());
        }
        return true;
    }

    private boolean handleGod(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 0, "allium.god.others");
        if (target == null) return true;
        boolean enable;
        if (godPlayers.contains(target.getUniqueId())) {
            godPlayers.remove(target.getUniqueId());
            enable = false;
        } else {
            godPlayers.add(target.getUniqueId());
            enable = true;
        }
        String state = enable ? "&aenabled" : "&cdisabled";
        messageManager.send(target, "player.god-toggle", "{prefix} &fGod mode {state} &ffor &d{player}&f.",
                "{state}", state, "{player}", target.getName());
        if (!target.equals(sender)) {
            messageManager.send(sender, "player.god-toggle", "{prefix} &fGod mode {state} &ffor &d{player}&f.",
                    "{state}", state, "{player}", target.getName());
        }
        return true;
    }

    private boolean handleGameMode(CommandSender sender, String label, String[] args) {
        GameMode fromLabel = parseGameModeLabel(label);
        GameMode mode;
        int targetIndex;
        if (fromLabel != null) {
            mode = fromLabel;
            targetIndex = 0;
        } else {
            if (args.length < 1) {
                send(sender, ChatColor.RED + "Usage: /gamemode <survival|creative|adventure|spectator> [player]");
                return true;
            }
            mode = parseGameMode(args[0]);
            if (mode == null) {
                send(sender, ChatColor.RED + "Unknown gamemode: " + args[0]);
                return true;
            }
            targetIndex = 1;
        }
        Player target = resolveTarget(sender, args, targetIndex, "allium.gamemode.others");
        if (target == null) return true;
        target.setGameMode(mode);
        String modeName = mode.name().toLowerCase(Locale.ROOT);
        messageManager.send(target, "player.gamemode-self", "{prefix} &aGamemode set to &f{mode}&a.",
                "{mode}", modeName);
        if (!target.equals(sender)) {
            messageManager.send(sender, "player.gamemode-other", "{prefix} &aSet &f{player}&a's gamemode to &f{mode}&a.",
                    "{player}", target.getName(), "{mode}", modeName);
        }
        return true;
    }

    private boolean handleSpeed(CommandSender sender, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            send(sender, ChatColor.RED + "Usage: /speed <0-10> [player]");
            return true;
        }
        float value;
        try {
            value = Float.parseFloat(args[0]);
        } catch (NumberFormatException e) {
            send(sender, ChatColor.RED + "Speed must be a number between 0 and 10.");
            return true;
        }
        if (!Float.isFinite(value) || value < 0.0f || value > 10.0f) {
            send(sender, ChatColor.RED + "Speed must be between 0 and 10.");
            return true;
        }
        Player target = resolveTarget(sender, args, 1, "allium.speed.others");
        if (target == null) return true;
        String lower = label.toLowerCase(Locale.ROOT);
        boolean fly = lower.contains("fly") || lower.equals("fspeed")
                || (!lower.contains("walk") && !lower.equals("wspeed") && target.isFlying());
        String type = fly ? "Fly" : "Walk";
        if (fly) {
            float real = value <= 1.0f ? 0.1f * value : 0.1f + ((value - 1.0f) / 9.0f) * 0.9f;
            target.setFlySpeed(Math.max(0.0f, Math.min(1.0f, real)));
        } else {
            float real = value <= 1.0f ? 0.2f * value : 0.2f + ((value - 1.0f) / 9.0f) * 0.8f;
            target.setWalkSpeed(Math.max(0.0f, Math.min(1.0f, real)));
        }
        String formattedSpeed = Float.toString(value);
        if (target.equals(sender)) {
            messageManager.send(target, "player.speed-set", "{prefix} &a{type} speed set to &f{speed}&a.",
                    "{type}", type, "{speed}", formattedSpeed);
        } else {
            messageManager.send(sender, "player.speed-set-other", "{prefix} &aSet &f{player}&a's &f{type} &aspeed to &f{speed}&a.",
                    "{player}", target.getName(), "{type}", type.toLowerCase(Locale.ROOT), "{speed}", formattedSpeed);
            messageManager.send(target, "player.speed-set", "{prefix} &a{type} speed set to &f{speed}&a.",
                    "{type}", type, "{speed}", formattedSpeed);
        }
        return true;
    }

    private boolean handleEnderChest(CommandSender sender, String[] args) {
        Player viewer = requirePlayer(sender);
        if (viewer == null) return true;
        Player target = resolveTarget(sender, args, 0, "allium.enderchest.others");
        if (target == null) return true;
        viewer.openInventory(target.getEnderChest());
        return true;
    }

    private boolean handleWorkbench(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        player.openWorkbench(null, true);
        return true;
    }

    private boolean handleHat(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            send(player, ChatColor.RED + "Hold an item in your main hand first.");
            return true;
        }
        ItemStack oldHelmet = player.getInventory().getHelmet();
        player.getInventory().setHelmet(hand);
        player.getInventory().setItemInMainHand(oldHelmet);
        send(player, ChatColor.GREEN + "Enjoy your new hat!");
        return true;
    }

    private boolean handleTime(CommandSender sender, String[] args) {
        if (args.length < 1 || args.length > 2) {
            send(sender, ChatColor.RED + "Usage: /time <day|noon|night|midnight|ticks> [world]");
            return true;
        }
        String token = args[0].toLowerCase(Locale.ROOT);
        long ticks;
        switch (token) {
            case "day": ticks = 1000L; break;
            case "noon": ticks = 6000L; break;
            case "night": ticks = 13000L; break;
            case "midnight": ticks = 18000L; break;
            default:
                try {
                    ticks = Long.parseLong(token);
                } catch (NumberFormatException e) {
                    send(sender, ChatColor.RED + "Invalid time: " + args[0]);
                    return true;
                }
        }
        return setWorldTime(sender, args.length == 2 ? args[1] : null, ticks, token);
    }

    private boolean setWorldTime(CommandSender sender, String worldName, long ticks, String label) {
        World world = resolveCommandWorld(sender, worldName);
        if (world == null) return true;
        world.setTime(ticks);
        send(sender, ChatColor.GREEN + "Set time in " + world.getName() + " to " + label + ".");
        return true;
    }

    private boolean handleWeather(CommandSender sender, String label, String[] args) {
        String lower = label.toLowerCase(Locale.ROOT);
        if ("sun".equals(lower) || "rain".equals(lower) || "storm".equals(lower)) {
            return setWorldWeather(sender, args.length > 0 ? args[0] : null, lower);
        }
        if (args.length < 1 || args.length > 2) {
            send(sender, ChatColor.RED + "Usage: /weather <sun|rain|storm> [world]");
            return true;
        }
        return setWorldWeather(sender, args.length == 2 ? args[1] : null, args[0].toLowerCase(Locale.ROOT));
    }

    private boolean setWorldWeather(CommandSender sender, String worldName, String mode) {
        World world = resolveCommandWorld(sender, worldName);
        if (world == null) return true;
        switch (mode) {
            case "sun":
            case "clear":
                world.setStorm(false);
                world.setThundering(false);
                send(sender, ChatColor.GREEN + "Cleared weather in " + world.getName() + ".");
                return true;
            case "rain":
                world.setStorm(true);
                world.setThundering(false);
                send(sender, ChatColor.GREEN + "Set weather to rain in " + world.getName() + ".");
                return true;
            case "storm":
            case "thunder":
                world.setStorm(true);
                world.setThundering(true);
                send(sender, ChatColor.GREEN + "Set weather to storm in " + world.getName() + ".");
                return true;
            default:
                send(sender, ChatColor.RED + "Usage: /weather <sun|rain|storm> [world]");
                return true;
        }
    }

    private boolean handleBroadcast(CommandSender sender, String[] args) {
        if (args.length < 1) {
            send(sender, ChatColor.RED + "Usage: /broadcast <message>");
            return true;
        }
        String text = LegacyCompat.color("&6[Broadcast] &f" + joinArgs(args, 0));
        Bukkit.broadcastMessage(text);
        return true;
    }

    LegacyPlayerSettings settings() { return settings; }
    LegacyConfigManager configManager() { return configManager; }
    LegacyLanguageManager languageManager() { return languageManager; }
    LegacyMessageManager messageManager() { return messageManager; }
    LegacyCommandManager commandManager() { return commandManager; }

    // ==================== YAML persistence (warps & spawn) ====================

    private void loadWarps() {
        warps.clear();
        File file = new File(plugin.getDataFolder(), "warps.yml");
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("warps");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Location loc = readLocation(section.getConfigurationSection(key));
            if (loc != null) warps.put(key.toLowerCase(Locale.ROOT), loc);
        }
    }

    private void saveWarps() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Location> entry : warps.entrySet()) {
            writeLocation(yml.createSection("warps." + entry.getKey()), entry.getValue());
        }
        try {
            yml.save(new File(plugin.getDataFolder(), "warps.yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save warps.yml: " + e.getMessage());
        }
    }

    private void loadSpawn() {
        spawnLocation = null;
        File file = new File(plugin.getDataFolder(), "spawn.yml");
        if (!file.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        spawnLocation = readLocation(yml.getConfigurationSection("spawn"));
    }

    private void saveSpawn() {
        if (spawnLocation == null || spawnLocation.getWorld() == null) return;
        YamlConfiguration yml = new YamlConfiguration();
        writeLocation(yml.createSection("spawn"), spawnLocation);
        try {
            yml.save(new File(plugin.getDataFolder(), "spawn.yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save spawn.yml: " + e.getMessage());
        }
    }

    private Location readLocation(ConfigurationSection section) {
        if (section == null) return null;
        String worldName = section.getString("world", "");
        World world = resolveWorld(worldName);
        if (world == null) return null;
        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        float yaw = (float) section.getDouble("yaw", 0.0);
        float pitch = (float) section.getDouble("pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private static void writeLocation(ConfigurationSection section, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        section.set("world", loc.getWorld().getName());
        section.set("x", loc.getX());
        section.set("y", loc.getY());
        section.set("z", loc.getZ());
        section.set("yaw", loc.getYaw());
        section.set("pitch", loc.getPitch());
    }

    // ==================== Small helpers ====================

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player) return (Player) sender;
        send(sender, ChatColor.RED + "Only players can use this command.");
        return null;
    }

    private Player resolveTarget(CommandSender sender, String[] args, int index, String othersPerm) {
        if (args.length <= index) {
            return requirePlayer(sender);
        }
        if (!sender.hasPermission(othersPerm) && !sender.isOp()) {
            send(sender, ChatColor.RED + "You do not have permission to target other players.");
            return null;
        }
        Player target = plugin.getServer().getPlayerExact(args[index]);
        if (target == null) {
            send(sender, ChatColor.RED + "Player '" + args[index] + "' is not online.");
            return null;
        }
        return target;
    }

    private World resolveCommandWorld(CommandSender sender, String explicitWorld) {
        if (explicitWorld != null && !explicitWorld.isEmpty()) {
            World w = plugin.getServer().getWorld(explicitWorld);
            if (w == null) {
                send(sender, ChatColor.RED + "World '" + explicitWorld + "' was not found.");
            }
            return w;
        }
        if (sender instanceof Player) return ((Player) sender).getWorld();
        List<World> worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) {
            send(sender, ChatColor.RED + "No worlds are loaded.");
            return null;
        }
        return worlds.get(0);
    }

    private static String joinArgs(String[] args, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static boolean isGenericGamemodeLabel(String alias) {
        return parseGameModeLabel(alias) == null;
    }

    private static GameMode parseGameModeLabel(String label) {
        if (label == null) return null;
        switch (label.toLowerCase(Locale.ROOT)) {
            case "gmc":
            case "creative":
            case "creativemode":
                return GameMode.CREATIVE;
            case "gms":
            case "survival":
            case "survivalmode":
                return GameMode.SURVIVAL;
            case "gma":
            case "adventure":
            case "adventuremode":
                return GameMode.ADVENTURE;
            case "gmsp":
            case "spectator":
            case "spectatormode":
                return GameMode.SPECTATOR;
            default:
                return null;
        }
    }

    private static GameMode parseGameMode(String raw) {
        if (raw == null) return null;
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "0":
            case "s":
            case "survival":
                return GameMode.SURVIVAL;
            case "1":
            case "c":
            case "creative":
                return GameMode.CREATIVE;
            case "2":
            case "a":
            case "adventure":
                return GameMode.ADVENTURE;
            case "3":
            case "sp":
            case "spectator":
                return GameMode.SPECTATOR;
            default:
                return null;
        }
    }

    Player findTeleportPlayer(CommandSender sender, String query) {
        if (query.startsWith("@")) return null;
        Player exact = plugin.getServer().getPlayerExact(query);
        if (exact != null && canSeeTeleportTarget(sender, exact)) return exact;
        List<Player> matches = rankedTeleportPlayers(sender, query);
        return matches.isEmpty() ? null : matches.get(0);
    }

    List<String> teleportPlayerSuggestions(CommandSender sender, String prefix) {
        if (prefix.startsWith("@")) return Collections.emptyList();
        if (prefix.isEmpty()) {
            return plugin.getServer().getOnlinePlayers().stream().filter(player -> canSeeTeleportTarget(sender, player))
                    .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).limit(64).collect(Collectors.toList());
        }
        return rankedTeleportPlayers(sender, prefix).stream().map(Player::getName).limit(64).collect(Collectors.toList());
    }

    private List<Player> rankedTeleportPlayers(CommandSender sender, String query) {
        return PlayerNameSearch.rankedMatches(plugin.getServer().getOnlinePlayers(), query,
                player -> canSeeTeleportTarget(sender, player), Player::getUniqueId, Player::getName,
                player -> ChatColor.stripColor(LegacyCompat.color(player.getDisplayName())).trim());
    }

    private boolean canSeeTeleportTarget(CommandSender sender, Player target) {
        if (!(sender instanceof Player) || sender.equals(target)) return true;
        if (!((Player) sender).canSee(target)) return false;
        if (sender.hasPermission("allium.vanish.see")) return true;
        if (moderation.isVanished(target.getUniqueId())) return false;
        for (MetadataValue value : target.getMetadata("vanished")) {
            if (value.asBoolean()) return false;
        }
        return true;
    }

    private List<String> onlinePlayerSuggestions(String prefix) {
        List<String> out = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(online.getName());
            }
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> filterPrefix(List<String> values, String prefix) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v.startsWith(prefix)) out.add(v);
        }
        return out;
    }
}
