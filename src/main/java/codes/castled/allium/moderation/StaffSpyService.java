package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.essentials.UtilitySupport;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.UntrustedText;
import codes.castled.allium.module.PreferencesModule;
import codes.castled.allium.naming.PlayerNameVisibility;
import codes.castled.allium.preference.EffectivePreferences;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.scheduler.BukkitServerThread;
import codes.castled.allium.text.SpigotComponents;
import codes.castled.allium.text.TextFormatter;
import codes.castled.allium.text.TextSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in command spy ({@code /commandspy}), per-player interactive spy panel ({@code /spy <player>}),
 * local-chat spy state ({@code allium.chat.spy}), and configurable {@code /spectate <player>}.
 */
public final class StaffSpyService implements Listener {

    public static final String PERM_COMMANDSPY = "allium.commandspy";
    public static final String PERM_COMMANDSPY_EXEMPT = "allium.commandspy.exempt";
    public static final String PERM_CHAT_SPY = "allium.chat.spy";
    public static final String PERM_CHAT_SPY_EXEMPT = "allium.chat.spy.exempt";
    public static final String PERM_SOCIALSPY = "allium.socialspy";
    public static final String PERM_SPY = "allium.spy";
    public static final String PERM_SPECTATE = "allium.spectate";

    private static final Set<String> SENSITIVE_COMMANDS = Set.of(
            "login", "l", "register", "reg", "changepassword", "changepass", "cp", "email", "2fa", "auth");

    private static final Set<String> SELF_GAMEMODE_COMMANDS = Set.of(
            "gmsp", "gmc", "gms", "gma", "spectator", "creative", "survival", "adventure",
            "spectatormode", "creativemode", "survivalmode", "adventuremode");

    private final PluginStart plugin;
    /** Opt-in global commandspy: never auto-enabled from permission alone. */
    private final Set<UUID> globalCommandSpy = ConcurrentHashMap.newKeySet();
    /** Opt-in global local-chat spy. */
    /**
     * Holders of {@code allium.chat.spy} see all local chat out of their range, as in Essentials,
     * unless they switched that off for this session ({@code /spy}).
     */
    private final Set<UUID> localSpyOff = ConcurrentHashMap.newKeySet();
    /** Per-target spy sets: observer UUID -> set of target UUIDs. */
    private final Map<UUID, Set<UUID>> messageTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> localTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> commandTargets = new ConcurrentHashMap<>();

    public StaffSpyService(PluginStart plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        globalCommandSpy.clear();
        localSpyOff.clear();
        messageTargets.clear();
        localTargets.clear();
        commandTargets.clear();
    }

    public boolean isGlobalCommandSpy(UUID observer) {
        return globalCommandSpy.contains(observer);
    }

    public boolean setGlobalCommandSpy(Player observer, Boolean desired) {
        UUID id = observer.getUniqueId();
        boolean next = desired == null ? !globalCommandSpy.contains(id) : desired;
        if (next) {
            globalCommandSpy.add(id);
        } else {
            globalCommandSpy.remove(id);
        }
        plugin.getMessageManager().send(observer, next ? Messages.COMMANDSPY_ON : Messages.COMMANDSPY_OFF);
        return next;
    }

    /** True when {@code observer} is spying on {@code participant}'s private messages via {@code /spy}. */
    public boolean isSpyingMessagesTarget(UUID observer, UUID participant) {
        Set<UUID> targets = messageTargets.get(observer);
        return targets != null && targets.contains(participant);
    }

    /** True when {@code observer} is spying on {@code sender}'s local chat (global or per-target). */
    public boolean isSpyingLocal(UUID observer, UUID sender) {
        if (!localSpyOff.contains(observer)) {
            return true;
        }
        Set<UUID> targets = localTargets.get(observer);
        return targets != null && targets.contains(sender);
    }

    /** True when {@code observer} is spying on {@code actor}'s commands (global or per-target). */
    public boolean isSpyingCommands(UUID observer, UUID actor) {
        if (globalCommandSpy.contains(observer)) {
            return true;
        }
        Set<UUID> targets = commandTargets.get(observer);
        return targets != null && targets.contains(actor);
    }

    /** Toggles spying on one channel for {@code target} and re-renders the interactive {@code /spy} panel. */
    public void toggleTarget(Player viewer, Player target, String category) {
        UUID viewerId = viewer.getUniqueId();
        UUID targetId = target.getUniqueId();
        switch (category.toLowerCase(Locale.ROOT)) {
            case "messages", "msg", "socialspy" -> {
                if (!viewer.hasPermission(PERM_SOCIALSPY)) {
                    plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION);
                    return;
                }
                if (isGlobalSocialSpy(viewerId)) {
                    toggleGlobalSocialSpy(viewer, false);
                } else {
                    toggleSet(messageTargets, viewerId, targetId);
                }
            }
            case "local", "chat", "localchat" -> {
                if (!viewer.hasPermission(PERM_CHAT_SPY)) {
                    plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION);
                    return;
                }
                if (!localSpyOff.contains(viewerId)) {
                    localSpyOff.add(viewerId);
                } else {
                    toggleSet(localTargets, viewerId, targetId);
                }
            }
            case "commands", "cmd", "commandspy" -> {
                if (!viewer.hasPermission(PERM_COMMANDSPY)) {
                    plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION);
                    return;
                }
                if (globalCommandSpy.contains(viewerId)) {
                    globalCommandSpy.remove(viewerId);
                } else {
                    toggleSet(commandTargets, viewerId, targetId);
                }
            }
            default -> {
                return;
            }
        }
        openPanel(viewer, target);
    }

    /** Opens the interactive {@code /spy <player>} panel showing spy status with clickable toggles. */
    public void openPanel(Player viewer, Player target) {
        MessageManager messages = plugin.getMessageManager();
        UUID viewerId = viewer.getUniqueId();
        UUID targetId = target.getUniqueId();

        messages.send(viewer, Messages.HELP_BORDER);
        messages.send(viewer, Messages.SPY_HEADER, "{player}", target.getName());
        viewer.sendMessage("");

        sendSpyRow(viewer, target, Messages.SPY_ROW_MESSAGES, "messages",
                viewer.hasPermission(PERM_SOCIALSPY),
                isGlobalSocialSpy(viewerId),
                isSpyingMessagesTarget(viewerId, targetId));

        sendSpyRow(viewer, target, Messages.SPY_ROW_LOCAL, "local",
                viewer.hasPermission(PERM_CHAT_SPY),
                !localSpyOff.contains(viewerId),
                containsTarget(localTargets, viewerId, targetId));

        sendSpyRow(viewer, target, Messages.SPY_ROW_COMMANDS, "commands",
                viewer.hasPermission(PERM_COMMANDSPY),
                globalCommandSpy.contains(viewerId),
                containsTarget(commandTargets, viewerId, targetId));

        messages.send(viewer, Messages.HELP_BORDER);
    }

    private void sendSpyRow(Player viewer, Player target, Messages rowKey, String category,
                            boolean permitted, boolean globalOn, boolean targetOn) {
        sendSpyRow(viewer, "/spy " + target.getName() + " toggle " + category, "&e" + target.getName(),
                rowKey, category, permitted, globalOn, targetOn);
    }

    private void sendSpyRow(Player viewer, String command, String subject, Messages rowKey, String category,
                            boolean permitted, boolean globalOn, boolean targetOn) {
        MessageManager messages = plugin.getMessageManager();
        Messages stateKey = !permitted ? Messages.SPY_STATE_DENIED
                : globalOn ? Messages.SPY_STATE_ON_GLOBAL
                : targetOn ? Messages.SPY_STATE_ON
                : Messages.SPY_STATE_OFF;
        String state = Objects.toString(messages.resolve(stateKey), stateKey.getDefault());
        String line = Objects.toString(messages.resolve(rowKey, "{state}", state), rowKey.getDefault().replace("{state}", state));
        if (!permitted) {
            TextSender.send(viewer, line);
            return;
        }
        String hoverText = (globalOn || targetOn)
                ? "&7Click to stop spying on " + subject + "&7 (" + category + ")"
                : "&7Click to spy on " + subject + "&7 (" + category + ")";
        BaseComponent[] parts = SpigotComponents.of(TextFormatter.parse(line));
        ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, SpigotComponents.of(TextFormatter.parse(hoverText)));
        for (BaseComponent part : parts) {
            part.setClickEvent(click);
            part.setHoverEvent(hover);
        }
        viewer.spigot().sendMessage(parts);
    }

    /** {@code /spy}: what the viewer spies on everywhere, each row a click to switch it. */
    public void openOwnPanel(Player viewer) {
        MessageManager messages = plugin.getMessageManager();
        UUID viewerId = viewer.getUniqueId();
        messages.send(viewer, Messages.HELP_BORDER);
        messages.send(viewer, Messages.SPY_HEADER_OWN);
        viewer.sendMessage("");
        sendSpyRow(viewer, "/spy toggle messages", "&eeveryone", Messages.SPY_ROW_MESSAGES, "messages",
                viewer.hasPermission(PERM_SOCIALSPY), isGlobalSocialSpy(viewerId), false);
        sendSpyRow(viewer, "/spy toggle local", "&eeveryone", Messages.SPY_ROW_LOCAL, "local",
                viewer.hasPermission(PERM_CHAT_SPY), !localSpyOff.contains(viewerId), false);
        sendSpyRow(viewer, "/spy toggle commands", "&eeveryone", Messages.SPY_ROW_COMMANDS, "commands",
                viewer.hasPermission(PERM_COMMANDSPY), globalCommandSpy.contains(viewerId), false);
        messages.send(viewer, Messages.HELP_BORDER);
    }

    /** {@code /spy toggle <messages|local|commands>}: switches spying on everyone for one kind. */
    public void toggleGlobal(Player viewer, String category) {
        UUID viewerId = viewer.getUniqueId();
        switch (category.toLowerCase(Locale.ROOT)) {
            case "messages", "msg", "socialspy" -> {
                if (!viewer.hasPermission(PERM_SOCIALSPY)) {
                    plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION);
                    return;
                }
                toggleGlobalSocialSpy(viewer, !isGlobalSocialSpy(viewerId));
            }
            case "local", "chat", "localchat" -> {
                if (!viewer.hasPermission(PERM_CHAT_SPY)) {
                    plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION);
                    return;
                }
                if (!localSpyOff.add(viewerId)) {
                    localSpyOff.remove(viewerId);
                }
            }
            case "commands", "cmd", "commandspy" -> {
                if (!viewer.hasPermission(PERM_COMMANDSPY)) {
                    plugin.getMessageManager().send(viewer, Messages.NO_PERMISSION);
                    return;
                }
                if (!globalCommandSpy.add(viewerId)) {
                    globalCommandSpy.remove(viewerId);
                }
            }
            default -> {
                return;
            }
        }
        openOwnPanel(viewer);
    }

    /** Executes configured {@code spectate-commands} in console for {@code staff} spectating {@code target}. */
    public void spectate(Player staff, Player target) {
        if (staff.getUniqueId().equals(target.getUniqueId())) {
            plugin.getMessageManager().send(staff, Messages.SPECTATE_SELF);
            return;
        }
        List<String> configured = plugin.getConfig().getStringList("spectate-commands");
        if (configured.isEmpty()) {
            configured = List.of("tp {player} {target}", "gmsp {player}");
        }
        ConsoleCommandSender console = plugin.getServer().getConsoleSender();
        for (String raw : configured) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String template = raw.trim();
            boolean asPlayer = false;
            boolean explicitConsole = false;
            if (template.regionMatches(true, 0, "player:", 0, 7)) {
                asPlayer = true;
                template = template.substring(7).trim();
            } else if (template.regionMatches(true, 0, "console:", 0, 8)) {
                explicitConsole = true;
                template = template.substring(8).trim();
            }
            if (template.startsWith("/")) {
                template = template.substring(1).trim();
            }
            if (template.isEmpty()) {
                continue;
            }
            boolean hadPlayerPlaceholder = template.contains("{player}");
            String expanded = template
                    .replace("{player}", staff.getName())
                    .replace("{target}", target.getName());
            if (asPlayer) {
                UtilitySupport.onOwner(plugin, staff, () -> staff.performCommand(expanded));
            } else {
                String consoleCommand = (!explicitConsole && !hadPlayerPlaceholder)
                        ? expandSelfShorthandForConsole(expanded, staff.getName())
                        : expanded;
                UtilitySupport.onGlobal(plugin, () -> plugin.getServer().dispatchCommand(console, consoleCommand));
            }
        }
        plugin.logger().info("Spectate audit actor=" + staff.getUniqueId() + " target=" + target.getUniqueId());
        UtilitySupport.send(plugin, staff, Messages.SPECTATE_STARTED, "{player}", target.getName());
    }

    /**
     * Allows concise config entries like {@code tp {target}} and {@code gmsp} (without {@code {player}})
     * to work cleanly when dispatched from console.
     */
    static String expandSelfShorthandForConsole(String command, String actorName) {
        String[] parts = command.split("\\s+");
        if (parts.length == 0) {
            return command;
        }
        String head = parts[0].toLowerCase(Locale.ROOT);
        int colon = head.lastIndexOf(':');
        if (colon >= 0) {
            head = head.substring(colon + 1);
        }
        if (parts.length == 1 && SELF_GAMEMODE_COMMANDS.contains(head)) {
            return command + " " + actorName;
        }
        if (parts.length == 2 && (head.equals("tp") || head.equals("tpo"))) {
            return parts[0] + " " + actorName + " " + parts[1];
        }
        return command;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player actor = event.getPlayer();
        if (actor.hasPermission(PERM_COMMANDSPY_EXEMPT)) {
            return;
        }
        String raw = event.getMessage();
        if (raw == null || raw.isBlank()) {
            return;
        }
        String withoutSlash = raw.startsWith("/") ? raw.substring(1).trim() : raw.trim();
        if (withoutSlash.isEmpty()) {
            return;
        }
        int space = withoutSlash.indexOf(' ');
        String label = (space >= 0 ? withoutSlash.substring(0, space) : withoutSlash).toLowerCase(Locale.ROOT);
        int colon = label.lastIndexOf(':');
        String baseLabel = colon >= 0 ? label.substring(colon + 1) : label;
        if (SENSITIVE_COMMANDS.contains(baseLabel)) {
            return;
        }
        List<String> ignored = plugin.getConfig() == null ? List.of()
                : plugin.getConfig().getStringList("moderation.commandspy.ignored-commands");
        for (String skip : ignored) {
            if (skip != null && skip.equalsIgnoreCase(baseLabel)) {
                return;
            }
        }

        UUID actorId = actor.getUniqueId();
        String template = plugin.getMessageManager().resolve(Messages.COMMANDSPY_FORMAT, "{player}", actor.getName());
        var literalCmd = UntrustedText.parse(raw, UntrustedText.Allow.NONE);
        var formatted = UntrustedText.insert(template.replace("{command}", "{message}"), literalCmd);
        for (Player observer : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (observer.getUniqueId().equals(actorId)) {
                continue;
            }
            if (!observer.hasPermission(PERM_COMMANDSPY) || !isSpyingCommands(observer.getUniqueId(), actorId)) {
                continue;
            }
            if (BukkitServerThread.isFolia()) {
                UtilitySupport.onOwner(plugin, observer, () -> {
                    if (observer.isOnline() && observer.canSee(actor) && PlayerNameVisibility.canSeeTarget(observer, actor)) {
                        TextSender.sendLiteral(observer, formatted);
                    }
                });
            } else {
                if (!PlayerNameVisibility.canSee(observer, actor)) {
                    continue;
                }
                TextSender.sendLiteral(observer, formatted);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        globalCommandSpy.remove(id);
        localSpyOff.remove(id);
        messageTargets.remove(id);
        localTargets.remove(id);
        commandTargets.remove(id);
        messageTargets.values().forEach(set -> set.remove(id));
        localTargets.values().forEach(set -> set.remove(id));
        commandTargets.values().forEach(set -> set.remove(id));
    }

    private boolean isGlobalSocialSpy(UUID observer) {
        if (plugin.getModuleManager() == null) {
            return false;
        }
        if (plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule prefs && prefs.isEnabled()) {
            return prefs.effective(observer).map(EffectivePreferences::socialSpy).orElse(false);
        }
        return false;
    }

    private void toggleGlobalSocialSpy(Player observer, boolean enabled) {
        if (plugin.getModuleManager() != null
                && plugin.getModuleManager().getModule("preferences") instanceof PreferencesModule prefs
                && prefs.isEnabled()) {
            prefs.sessions().change(observer.getUniqueId(), PreferenceChange.set(PreferenceKey.SOCIAL_SPY, enabled));
        }
    }

    private static boolean containsTarget(Map<UUID, Set<UUID>> map, UUID observer, UUID target) {
        Set<UUID> set = map.get(observer);
        return set != null && set.contains(target);
    }

    private static void toggleSet(Map<UUID, Set<UUID>> map, UUID observer, UUID target) {
        Set<UUID> set = map.computeIfAbsent(observer, ignored -> ConcurrentHashMap.newKeySet());
        if (!set.add(target)) {
            set.remove(target);
        }
    }
}
