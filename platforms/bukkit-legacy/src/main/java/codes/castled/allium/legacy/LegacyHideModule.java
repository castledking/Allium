package codes.castled.allium.legacy;

import codes.castled.allium.hide.HidePolicyEngine;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Java 8 / 1.12.2-1.19.4 command-hider module ({@code hide.yml}, {@code /allium hide}):
 * shares {@link HidePolicyEngine} with the modern runtime, enforces execution/tab-completion
 * rules on 1.12.2-1.19.4, dynamically hooks 1.13+ {@code PlayerCommandSendEvent} via reflection,
 * filters {@code /help}, and preserves double-slash commands such as WorldEdit's {@code //wand}.
 */
final class LegacyHideModule implements Listener {

    static final String BYPASS = "allium.hide.bypass";
    static final String ADMIN = "allium.hide.admin";
    private static final long MAX_HIDE_FILE_BYTES = 1_048_576L;
    private static final Method PLAYER_UPDATE_COMMANDS = findMethod(Player.class, "updateCommands");

    enum PermissionSource {
        BUKKIT,
        LUCKPERMS,
        REGISTERED,
        EFFECTIVE,
        OVERRIDE,
        UNKNOWN
    }

    static final class PermissionDecision {
        final boolean allowed;
        final PermissionSource source;
        final String permission;
        final String label;

        PermissionDecision(boolean allowed, PermissionSource source, String permission, String label) {
            this.allowed = allowed;
            this.source = source;
            this.permission = permission;
            this.label = label;
        }
    }

    static final class Settings {
        final boolean enabled;
        final boolean useResolver;
        final boolean blockNamespacedForBypass;
        final boolean hideNamespacedForBypass;
        final boolean removeUnsafe;
        final Set<String> unsafe;
        final boolean filterSuggestions;
        final boolean cancelBareSlash;
        final Set<String> namespacedAllow;
        final List<String> suggestionPrefixes;
        final HidePolicyEngine policy;
        final Map<String, String> permissionOverrides;

        Settings(
                boolean enabled, boolean useResolver, boolean blockNamespacedForBypass,
                boolean hideNamespacedForBypass, boolean removeUnsafe, Set<String> unsafe,
                boolean filterSuggestions, boolean cancelBareSlash, Set<String> namespacedAllow,
                List<String> suggestionPrefixes, HidePolicyEngine policy,
                Map<String, String> permissionOverrides
        ) {
            this.enabled = enabled;
            this.useResolver = useResolver;
            this.blockNamespacedForBypass = blockNamespacedForBypass;
            this.hideNamespacedForBypass = hideNamespacedForBypass;
            this.removeUnsafe = removeUnsafe;
            this.unsafe = Collections.unmodifiableSet(unsafe);
            this.filterSuggestions = filterSuggestions;
            this.cancelBareSlash = cancelBareSlash;
            this.namespacedAllow = Collections.unmodifiableSet(namespacedAllow);
            this.suggestionPrefixes = Collections.unmodifiableList(suggestionPrefixes);
            this.policy = policy;
            this.permissionOverrides = Collections.unmodifiableMap(permissionOverrides);
        }

        boolean namespacedVisible(String label, boolean bypass) {
            String root = HidePolicyEngine.rootOfPath(label);
            if (!root.contains(":")) return true;
            if (bypass) return !hideNamespacedForBypass;
            for (String rule : namespacedAllow) {
                if (rule.equalsIgnoreCase(root)
                        || (rule.endsWith(":*") && root.startsWith(rule.substring(0, rule.length() - 1)))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Viewer {
        final Settings settings;
        final List<HidePolicyEngine.Group> groups;
        final boolean bypass;

        Viewer(Settings settings, List<HidePolicyEngine.Group> groups, boolean bypass) {
            this.settings = settings;
            this.groups = groups;
            this.bypass = bypass;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyConfigManager configManager;
    private final LegacyMessageManager messages;
    private final LegacyCommandManager commandManager;
    private final BiPredicate<CommandSender, Player> canSeeTarget;
    private final File hideFile;
    private volatile Settings settings;
    private volatile YamlConfiguration hideConfig = new YamlConfiguration();
    private volatile Map<String, Command> commands = Collections.emptyMap();
    private volatile boolean commandSendHooked;
    private Set<String> registeredPermCache = Collections.emptySet();
    private long registeredPermCachedAt;

    LegacyHideModule(
            JavaPlugin plugin,
            LegacyConfigManager configManager,
            LegacyMessageManager messages,
            LegacyCommandManager commandManager,
            BiPredicate<CommandSender, Player> canSeeTarget
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messages = messages;
        this.commandManager = commandManager;
        this.canSeeTarget = canSeeTarget;
        this.hideFile = new File(plugin.getDataFolder(), "hide.yml");
        this.settings = defaultDisabledSettings();
    }

    void enable() {
        ensureHideFile();
        loadHideConfig();
        refreshCommands();
        registerCommandSendHook();
        if (isActive()) {
            plugin.getLogger().info("Hide execution/tab filtering enabled (1.13+ command tree hook="
                    + commandSendHooked + "). Native command permissions remain authoritative.");
            updateAllOnlinePlayers();
        }
    }

    void reload() {
        ensureHideFile();
        loadHideConfig();
        refreshCommands();
        updateAllOnlinePlayers();
    }

    void disable() {
        commands = Collections.emptyMap();
        registeredPermCache = Collections.emptySet();
        registeredPermCachedAt = 0L;
    }

    boolean isActive() {
        return configManager.isModuleEnabled("hide") && settings.enabled;
    }

    // ==================== Event Listeners ====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!isActive()) return;
        Player player = event.getPlayer();
        String line = HidePolicyEngine.normalize(event.getMessage());
        if (line.isEmpty()) return;
        String root = HidePolicyEngine.rootOfPath(line);
        Viewer viewer = viewerFor(player);

        if ((viewer.bypass && viewer.settings.blockNamespacedForBypass && root.contains(":"))
                || !groupAllows(viewer, line, false)) {
            event.setCancelled(true);
            sendUnknown(player, line);
            return;
        }
        if (viewer.bypass || !viewer.settings.useResolver) {
            return;
        }
        Map<String, Command> known = currentCommands();
        if (known.isEmpty()) return;
        PermissionDecision decision = resolvePermission(player, line, known, viewer.settings.permissionOverrides);
        if (!decision.allowed) {
            event.setCancelled(true);
            if (!known.containsKey(root)) {
                sendUnknown(player, line);
            } else {
                messages.send(player, "no-permission",
                        "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", root);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTabComplete(TabCompleteEvent event) {
        if (!isActive()) return;
        if (!(event.getSender() instanceof Player)) return;
        String buffer = event.getBuffer();
        if (buffer == null || !buffer.startsWith("/")) return;

        Player player = (Player) event.getSender();
        Viewer viewer = viewerFor(player);
        if (!viewer.bypass && viewer.settings.filterSuggestions && viewer.settings.cancelBareSlash
                && "/".equals(buffer.trim())) {
            event.setCompletions(Collections.<String>emptyList());
            event.setCancelled(true);
            return;
        }

        List<String> original = event.getCompletions();
        if (original == null || original.isEmpty()) return;
        List<String> kept = new ArrayList<String>(original.size());
        for (String value : original) {
            String path = HidePolicyEngine.suggestionPath(buffer, value, -1);
            if (isVisible(player, viewer, path)) {
                kept.add(value);
            }
        }
        event.setCompletions(kept);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (isActive()) {
            updatePlayerCommands(event.getPlayer());
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        refreshCommands();
    }

    /** Dynamically hooks Bukkit 1.13+ {@code PlayerCommandSendEvent} so 1.12.2 never fails classloading. */
    @SuppressWarnings("unchecked")
    private void registerCommandSendHook() {
        if (commandSendHooked) return;
        try {
            Class<? extends Event> eventClass = (Class<? extends Event>)
                    Class.forName("org.bukkit.event.player.PlayerCommandSendEvent");
            final Method getPlayer = eventClass.getMethod("getPlayer");
            final Method getCommands = eventClass.getMethod("getCommands");
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass,
                    this,
                    EventPriority.HIGHEST,
                    new EventExecutor() {
                        @Override
                        public void execute(Listener listener, Event rawEvent) throws EventException {
                            if (!isActive()) return;
                            try {
                                Player player = (Player) getPlayer.invoke(rawEvent);
                                Collection<String> rootLabels = (Collection<String>) getCommands.invoke(rawEvent);
                                if (player == null || rootLabels == null) return;
                                refreshCommands();
                                Viewer viewer = viewerFor(player);
                                Set<String> scoped = scopedRoots(viewer);
                                java.util.Iterator<String> it = rootLabels.iterator();
                                while (it.hasNext()) {
                                    String label = it.next();
                                    if (!isVisible(player, viewer, label)
                                            || scoped.contains(HidePolicyEngine.plainOfPath(label))) {
                                        it.remove();
                                    }
                                }
                            } catch (Throwable error) {
                                plugin.getLogger().warning("Hide PlayerCommandSendEvent filter failed: " + error.getMessage());
                            }
                        }
                    },
                    plugin,
                    true
            );
            commandSendHooked = true;
        } catch (ClassNotFoundException pre113) {
            commandSendHooked = false;
        } catch (Throwable unavailable) {
            commandSendHooked = false;
        }
    }

    // ==================== /help Integration ====================

    /** Returns whether {@code root} should appear in {@code /help} for {@code sender}. */
    boolean isVisibleInHelp(CommandSender sender, String root, Command command) {
        if (!isActive() || !(sender instanceof Player)) return true;
        Player player = (Player) sender;
        Viewer viewer = viewerFor(player);
        String normalized = HidePolicyEngine.normalizePath(root);
        if (!isVisible(player, viewer, normalized)) return false;
        if (!groupAllows(viewer, normalized, false)) return false;
        if (!viewer.settings.useResolver) return true;
        if (viewer.bypass) return command == null || command.testPermissionSilent(player);
        Map<String, Command> known = currentCommands();
        if (known.isEmpty()) return command == null || command.testPermissionSilent(player);
        return resolvePermission(player, normalized, known, viewer.settings.permissionOverrides).allowed;
    }

    // ==================== /allium hide Admin Command ====================

    boolean handleAdmin(CommandSender actor, String[] args) {
        if (!actor.hasPermission(ADMIN) && !actor.isOp()) {
            messages.send(actor, "no-permission",
                    "{prefix} &cYou don't have permission to use &f/{cmd}&c.", "{cmd}", "allium hide");
            return true;
        }
        try {
            if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
                Settings s = settings;
                messages.send(actor, "hide.info", "{prefix} &7{message}",
                        "{message}", "enabled=" + isActive()
                                + ", groups=" + joinStrings(new ArrayList<String>(s.policy.groupNames()), ", ")
                                + ", tree-filter=" + commandSendHooked);
                return true;
            }
            if ("reload".equalsIgnoreCase(args[0]) && args.length == 1) {
                if (applyConfigUpdate(actor, null)) {
                    messages.send(actor, "hide.reloaded", "{prefix} &aHide configuration reloaded.");
                }
                return true;
            }
            if ("enabled".equalsIgnoreCase(args[0]) && args.length == 2
                    && ("true".equalsIgnoreCase(args[1]) || "false".equalsIgnoreCase(args[1]))) {
                final boolean on = Boolean.parseBoolean(args[1]);
                if (applyConfigUpdate(actor, new Consumer<YamlConfiguration>() {
                    @Override
                    public void accept(YamlConfiguration c) {
                        c.set("settings.enabled", Boolean.valueOf(on));
                    }
                })) {
                    messages.send(actor, "hide.updated", "{prefix} &aHide configuration updated.");
                }
                return true;
            }
            if ("resolve".equalsIgnoreCase(args[0]) && args.length >= 3) {
                Player target = plugin.getServer().getPlayerExact(args[1]);
                if (target == null || !canSeeTarget.test(actor, target)) {
                    messages.send(actor, "chat.player-not-found", "{prefix} &cPlayer not found.");
                    return true;
                }
                String line = joinArgs(args, 2);
                PermissionDecision d = resolvePermission(target, line, currentCommands(), settings.permissionOverrides);
                messages.send(actor, "hide.permission",
                        "{prefix} &f{player}&7: &f{command} &7via &f{source}&7, permission=&f{permission}&7, allowed=&f{allowed}",
                        "{player}", target.getName(),
                        "{command}", d.label,
                        "{source}", d.source.name(),
                        "{permission}", d.permission == null ? "native/unknown" : d.permission,
                        "{allowed}", Boolean.toString(d.allowed));
                return true;
            }
            if ("fix".equalsIgnoreCase(args[0]) && args.length == 3) {
                final String label = HidePolicyEngine.root(args[1]);
                final String permission = args[2];
                if (applyConfigUpdate(actor, new Consumer<YamlConfiguration>() {
                    @Override
                    public void accept(YamlConfiguration c) {
                        List<Map<?, ?>> rows = new ArrayList<Map<?, ?>>(c.getMapList("permission-overrides"));
                        java.util.Iterator<Map<?, ?>> it = rows.iterator();
                        while (it.hasNext()) {
                            if (label.equals(it.next().get("command"))) it.remove();
                        }
                        Map<String, String> row = new LinkedHashMap<String, String>();
                        row.put("command", label);
                        row.put("permission", permission);
                        rows.add(row);
                        c.set("permission-overrides", rows);
                    }
                })) {
                    messages.send(actor, "hide.updated", "{prefix} &aHide configuration updated.");
                }
                return true;
            }
            if ("unfix".equalsIgnoreCase(args[0]) && args.length == 2) {
                final String label = HidePolicyEngine.root(args[1]);
                if (applyConfigUpdate(actor, new Consumer<YamlConfiguration>() {
                    @Override
                    public void accept(YamlConfiguration c) {
                        List<Map<?, ?>> rows = new ArrayList<Map<?, ?>>(c.getMapList("permission-overrides"));
                        java.util.Iterator<Map<?, ?>> it = rows.iterator();
                        while (it.hasNext()) {
                            if (label.equals(it.next().get("command"))) it.remove();
                        }
                        c.set("permission-overrides", rows);
                    }
                })) {
                    messages.send(actor, "hide.updated", "{prefix} &aHide configuration updated.");
                }
                return true;
            }
            if ("group".equalsIgnoreCase(args[0])) {
                return handleGroupAdmin(actor, Arrays.copyOfRange(args, 1, args.length));
            }
        } catch (IllegalArgumentException invalid) {
            messages.send(actor, "hide.failed", "{prefix} &cHide configuration was refused. Check console for details.");
            return true;
        }
        messages.sendPrefixed(actor, ChatColor.RED
                + "Usage: /allium hide <status|reload|enabled <true|false>|resolve <player> <cmd>|fix <cmd> <perm>|unfix <cmd>|group ...>");
        return true;
    }

    private boolean handleGroupAdmin(CommandSender actor, String[] args) {
        if (args.length < 2) {
            messages.sendPrefixed(actor, ChatColor.RED
                    + "Usage: /allium hide group <name> <create|add|remove|check|whitelist|inherit> ...");
            return true;
        }
        final String name = args[0].toLowerCase(Locale.ROOT);
        final String action = args[1].toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("group name");
        }
        if ("create".equals(action) && args.length == 3
                && ("whitelist".equalsIgnoreCase(args[2]) || "blacklist".equalsIgnoreCase(args[2]))) {
            final boolean white = "whitelist".equalsIgnoreCase(args[2]);
            if (applyConfigUpdate(actor, new Consumer<YamlConfiguration>() {
                @Override
                public void accept(YamlConfiguration c) {
                    if (c.contains("groups." + name)) throw new IllegalArgumentException("group exists");
                    c.set("groups." + name + ".whitelist", Boolean.valueOf(white));
                    c.set("groups." + name + ".commands", Collections.<String>emptyList());
                    c.set("groups." + name + ".tabcompletes", Collections.singletonList("^"));
                }
            })) {
                messages.send(actor, "hide.updated", "{prefix} &aHide configuration updated.");
            }
            return true;
        }
        if (!settings.policy.groupNames().contains(name)) {
            throw new IllegalArgumentException("unknown group");
        }
        if ("whitelist".equals(action) && args.length == 3
                && ("true".equalsIgnoreCase(args[2]) || "false".equalsIgnoreCase(args[2]))) {
            final boolean white = Boolean.parseBoolean(args[2]);
            if (applyConfigUpdate(actor, new Consumer<YamlConfiguration>() {
                @Override
                public void accept(YamlConfiguration c) {
                    c.set(groupPath(c, name) + ".whitelist", Boolean.valueOf(white));
                }
            })) {
                messages.send(actor, "hide.updated", "{prefix} &aHide configuration updated.");
            }
            return true;
        }
        if ("inherit".equals(action) && args.length == 4
                && ("commands".equalsIgnoreCase(args[2]) || "tabcompletes".equalsIgnoreCase(args[2]))) {
            final String scope = args[2].toLowerCase(Locale.ROOT);
            final List<String> parents = new ArrayList<String>();
            for (String part : args[3].split(",")) {
                if (part != null && !part.trim().isEmpty()) parents.add(part.trim());
            }
            if (applyConfigUpdate(actor, new Consumer<YamlConfiguration>() {
                @Override
                public void accept(YamlConfiguration c) {
                    c.set(groupPath(c, name) + ".inherits." + scope, parents);
                }
            })) {
                messages.send(actor, "hide.updated", "{prefix} &aHide configuration updated.");
            }
            return true;
        }
        if ((!"add".equals(action) && !"remove".equals(action) && !"check".equals(action)) || args.length < 3) {
            messages.sendPrefixed(actor, ChatColor.RED
                    + "Usage: /allium hide group <name> <add|remove|check> [-c|-t|-tc] <rules>");
            return true;
        }
        int start = 2;
        String flag = "-tc";
        if (args[start].startsWith("-")) {
            flag = args[start++].toLowerCase(Locale.ROOT);
            if (!"-c".equals(flag) && !"-t".equals(flag) && !"-tc".equals(flag) && !"-ct".equals(flag)) {
                throw new IllegalArgumentException("scope");
            }
        }
        if (start >= args.length) throw new IllegalArgumentException("rule missing");
        final List<String> rules = new ArrayList<String>();
        for (String part : joinArgs(args, start).split(",")) {
            String normalized = HidePolicyEngine.normalize(part);
            if (!normalized.isEmpty()) rules.add(normalized);
        }
        if (rules.isEmpty()) throw new IllegalArgumentException("rule missing");

        if ("check".equals(action)) {
            HidePolicyEngine.Group g = settings.policy.select(Collections.singletonList(name)).get(0);
            for (String rule : rules) {
                messages.send(actor, "hide.info", "{prefix} &7{message}",
                        "{message}", name + " " + rule + ": command=" + g.allowsNormalized(rule, false)
                                + ", tab=" + g.allowsNormalized(rule, true));
            }
            return true;
        }

        final List<String> yamlRules = new ArrayList<String>(rules.size());
        for (String r : rules) {
            yamlRules.add(r.startsWith("/") ? "/" + r : r);
        }
        final List<String> scopes = "-c".equals(flag)
                ? Collections.singletonList("commands")
                : ("-t".equals(flag) ? Collections.singletonList("tabcompletes")
                        : Arrays.asList("commands", "tabcompletes"));
        final boolean adding = "add".equals(action);
        if (applyConfigUpdate(actor, new Consumer<YamlConfiguration>() {
            @Override
            public void accept(YamlConfiguration c) {
                String base = groupPath(c, name);
                for (String scope : scopes) {
                    String path = base + "." + scope;
                    Set<String> values = new LinkedHashSet<String>(c.getStringList(path));
                    if (adding) {
                        values.addAll(yamlRules);
                    } else {
                        values.removeAll(yamlRules);
                        values.removeAll(rules);
                    }
                    c.set(path, new ArrayList<String>(values));
                }
            }
        })) {
            messages.send(actor, "hide.updated", "{prefix} &aHide configuration updated.");
        }
        return true;
    }

    List<String> completeAdmin(CommandSender actor, String[] args) {
        if (!actor.hasPermission(ADMIN) && !actor.isOp()) return Collections.emptyList();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        Collection<String> values = Collections.emptyList();
        if (args.length <= 1) {
            values = Arrays.asList("status", "reload", "enabled", "group", "resolve", "fix", "unfix");
        } else if ("group".equalsIgnoreCase(args[0])) {
            if (args.length == 2) values = settings.policy.groupNames();
            else if (args.length == 3) values = Arrays.asList("create", "add", "remove", "check", "whitelist", "inherit");
            else if (args.length == 4) values = Arrays.asList("-c", "-t", "-tc");
        } else if ("enabled".equalsIgnoreCase(args[0]) && args.length == 2) {
            values = Arrays.asList("true", "false");
        } else if ("resolve".equalsIgnoreCase(args[0]) && args.length == 2) {
            List<String> online = new ArrayList<String>();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (canSeeTarget.test(actor, p)) online.add(p.getName());
            }
            values = online;
        } else if (("fix".equalsIgnoreCase(args[0]) || "unfix".equalsIgnoreCase(args[0])) && args.length == 2) {
            values = currentCommands().keySet();
        }
        List<String> out = new ArrayList<String>();
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(v);
            }
        }
        Collections.sort(out);
        return out.size() > 128 ? out.subList(0, 128) : out;
    }

    // ==================== Core Policy Evaluation ====================

    private Viewer viewerFor(Player player) {
        Settings s = settings;
        Set<String> memberships = resolveMemberships(player, s.policy.groupNames());
        List<HidePolicyEngine.Group> selected = new ArrayList<HidePolicyEngine.Group>(s.policy.select(memberships));
        boolean bypass = player.hasPermission(BYPASS);
        if (bypass) {
            selected.add(new HidePolicyEngine.Group("bypass", true,
                    Collections.singletonList("*"),
                    Collections.<String>emptyList(),
                    Collections.singletonList("*")));
        }
        return new Viewer(s, Collections.unmodifiableList(selected), bypass);
    }

    private List<String> variants(String normalizedPath) {
        List<String> out = new ArrayList<String>(3);
        String p = HidePolicyEngine.normalizePath(normalizedPath);
        out.add(p);
        String label = HidePolicyEngine.rootOfPath(p);
        String args = p.substring(label.length());
        Command cmd = currentCommands().get(label);
        if (cmd instanceof PluginIdentifiableCommand) {
            String owner = ((PluginIdentifiableCommand) cmd).getPlugin().getName().toLowerCase(Locale.ROOT);
            out.add(owner + ":" + HidePolicyEngine.plainOfPath(label) + args);
            out.add(owner + ":" + cmd.getName().toLowerCase(Locale.ROOT) + args);
        }
        return out;
    }

    private boolean groupAllows(Viewer v, String normalizedPath, boolean completion) {
        List<String> paths = variants(normalizedPath);
        boolean anyWhitelist = false;
        for (HidePolicyEngine.Group g : v.groups) {
            if (!g.whitelist()) {
                for (String candidate : paths) {
                    if (!g.allowsNormalized(candidate, completion)) return false;
                }
            } else {
                anyWhitelist = true;
            }
        }
        if (!anyWhitelist) return true;
        for (String candidate : paths) {
            for (HidePolicyEngine.Group g : v.groups) {
                if (g.whitelist() && g.allowsNormalized(candidate, completion)) return true;
            }
        }
        return false;
    }

    private boolean isVisible(Player player, Viewer v, String path) {
        if (!v.settings.enabled) return true;
        String normalized = HidePolicyEngine.normalizePath(path);
        String root = HidePolicyEngine.rootOfPath(normalized);
        if (!v.settings.namespacedVisible(root, v.bypass)) return false;
        if (!v.bypass) {
            if (v.settings.removeUnsafe && v.settings.unsafe.contains(root)) return false;
            if (v.settings.filterSuggestions) {
                for (String prefix : v.settings.suggestionPrefixes) {
                    if (root.startsWith(prefix)) return false;
                }
            }
            if (!groupAllows(v, normalized, true)) return false;
        }
        if (!v.settings.useResolver) return true;
        Map<String, Command> known = currentCommands();
        if (known.isEmpty()) return true;
        if (v.bypass && !known.containsKey(root)) return true;
        return resolvePermission(player, normalized, known, v.settings.permissionOverrides).allowed;
    }

    private Set<String> scopedRoots(Viewer v) {
        if (v == null || v.bypass || !v.settings.enabled) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<String>();
        for (HidePolicyEngine.Group group : v.groups) {
            for (String path : group.completions()) {
                if (path.indexOf(' ') >= 0) {
                    String r = HidePolicyEngine.rootOfPath(path);
                    out.add(r);
                    out.add(HidePolicyEngine.plainOfPath(r));
                }
            }
        }
        return out;
    }

    private void sendUnknown(Player player, String normalizedLine) {
        String root = HidePolicyEngine.rootOfPath(normalizedLine);
        String suggestion = null;
        if (!root.contains(":")) {
            List<LegacyCommandManager.HelpEntry> entries = commandManager.helpEntries(player);
            List<String> paths = new ArrayList<String>(entries.size());
            for (LegacyCommandManager.HelpEntry entry : entries) {
                paths.add(entry.path);
            }
            suggestion = HidePolicyEngine.closest(root, paths);
        }
        if (suggestion == null) {
            messages.send(player, "unknown-command",
                    "{prefix} &cUnknown command: /{cmd}. Type &f/help &cfor help.",
                    "{cmd}", root);
        } else {
            messages.send(player, "unknown-command-suggestion",
                    "{prefix} &cUnknown command: /{cmd}. Did you mean &f/{suggestion}&c?",
                    "{cmd}", root, "{suggestion}", suggestion);
        }
    }

    // ==================== Permission Resolver ====================

    PermissionDecision resolvePermission(Player player, String line, Map<String, Command> known,
                                         Map<String, String> overrides) {
        String rawPath = HidePolicyEngine.normalizePath(line);
        String rawLabel = HidePolicyEngine.rootOfPath(rawPath);
        String path;
        String label;
        if (known.containsKey(rawLabel)) {
            path = rawPath;
            label = rawLabel;
        } else {
            path = HidePolicyEngine.normalize(line);
            label = HidePolicyEngine.rootOfPath(path);
        }
        Command command = known.get(label);
        if (command == null) {
            return new PermissionDecision(false, PermissionSource.UNKNOWN, null, label);
        }
        String explicitPerm = command.getPermission();
        if (explicitPerm != null && !explicitPerm.trim().isEmpty()) {
            return new PermissionDecision(command.testPermissionSilent(player),
                    PermissionSource.BUKKIT, explicitPerm, label);
        }

        String[] tokens = path.split(" ", 256);
        List<String> args = tokens.length > 1
                ? Arrays.asList(tokens).subList(1, tokens.length)
                : Collections.<String>emptyList();
        String owner = command instanceof PluginIdentifiableCommand
                ? ((PluginIdentifiableCommand) command).getPlugin().getName().toLowerCase(Locale.ROOT)
                : null;

        Set<String> registered = registeredPermissions();
        Set<String> effective = new HashSet<String>();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info != null && info.getPermission() != null) {
                effective.add(info.getPermission().toLowerCase(Locale.ROOT));
            }
        }
        List<String> candidates = permissionCandidates(owner, command, label, args, registered);
        PermissionDecision lp = checkLuckPerms(player, label, candidates, registered);
        if (lp != null) return lp;

        for (String candidate : candidates) {
            if (candidate.endsWith(".*") && !player.hasPermission(candidate)) continue;
            if (registered.contains(candidate) || effective.contains(candidate)) {
                return new PermissionDecision(player.hasPermission(candidate),
                        registered.contains(candidate) ? PermissionSource.REGISTERED : PermissionSource.EFFECTIVE,
                        candidate, label);
            }
        }

        String mapped = overrides.get(label);
        if (mapped == null && command.getName() != null) {
            mapped = overrides.get(command.getName().toLowerCase(Locale.ROOT));
        }
        if (mapped != null) {
            return new PermissionDecision(player.hasPermission(mapped), PermissionSource.OVERRIDE, mapped, label);
        }
        return new PermissionDecision(true, PermissionSource.UNKNOWN, null, label);
    }

    private Set<String> registeredPermissions() {
        long now = System.nanoTime();
        if (registeredPermCachedAt == 0L || now - registeredPermCachedAt > 1_000_000_000L) {
            Set<String> next = new HashSet<String>();
            PluginManager pm = plugin.getServer().getPluginManager();
            if (pm != null) {
                for (Permission perm : pm.getPermissions()) {
                    if (perm != null && perm.getName() != null) {
                        next.add(perm.getName().toLowerCase(Locale.ROOT));
                    }
                }
            }
            registeredPermCache = Collections.unmodifiableSet(next);
            registeredPermCachedAt = now;
        }
        return registeredPermCache;
    }

    private static List<String> permissionCandidates(String owner, Command command, String label,
                                                     List<String> args, Set<String> registered) {
        if (owner == null) return Collections.emptyList();
        String root = stripLeadingSlash(HidePolicyEngine.plainOfPath(label));
        Set<String> identities = new LinkedHashSet<String>();
        if (command.getAliases() != null) {
            for (String alias : command.getAliases()) {
                if (alias != null) identities.add(stripLeadingSlash(alias.toLowerCase(Locale.ROOT)));
            }
        }
        identities.add(root);
        if (command.getName() != null) {
            identities.add(stripLeadingSlash(command.getName().toLowerCase(Locale.ROOT)));
        }
        identities.remove("");

        Set<String> modules = new TreeSet<String>();
        String prefix = owner + ".";
        for (String key : registered) {
            if (!key.startsWith(prefix)) continue;
            int at = key.indexOf(".command.", prefix.length() - 1);
            if (at < prefix.length()) continue;
            String module = key.substring(prefix.length(), at);
            for (String id : identities) {
                if (module.equals(id) || module.endsWith("." + id)) {
                    modules.add(module);
                    break;
                }
            }
        }
        Set<String> actions = new LinkedHashSet<String>();
        if (!args.isEmpty()) {
            actions.add(args.get(0));
        } else {
            for (String m : modules) {
                if (registered.contains(prefix + m + ".command.open")) {
                    actions.add("open");
                    break;
                }
            }
            actions.addAll(identities);
        }
        Set<String> out = new LinkedHashSet<String>();
        for (String action : actions) {
            for (String m : modules) out.add(prefix + m + ".command." + action);
            out.add(prefix + "command." + action);
            for (String m : modules) out.add(prefix + m + "." + action);
            out.add(prefix + action);
        }
        for (String id : identities) out.add(prefix + id);
        for (String m : modules) out.add(prefix + m + ".command.*");
        out.add(prefix + "command.*");
        for (String m : modules) out.add(prefix + m + ".*");
        out.add(prefix + "*");
        return new ArrayList<String>(out);
    }

    private PermissionDecision checkLuckPerms(Player player, String label, List<String> candidates,
                                              Set<String> registered) {
        try {
            Plugin peer = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
            if (peer == null || !peer.isEnabled()) return null;
            ClassLoader loader = peer.getClass().getClassLoader();
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", false, loader);
            Object lp = provider.getMethod("get").invoke(null);
            Class<?> api = Class.forName("net.luckperms.api.LuckPerms", false, loader);
            Class<?> adapter = Class.forName("net.luckperms.api.platform.PlayerAdapter", false, loader);
            Class<?> data = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData", false, loader);
            Class<?> result = Class.forName("net.luckperms.api.cacheddata.Result", false, loader);
            Object a = api.getMethod("getPlayerAdapter", Class.class).invoke(lp, Player.class);
            Object cache = adapter.getMethod("getPermissionData", Object.class).invoke(a, player);
            Object rawMap = data.getMethod("getPermissionMap").invoke(cache);
            Map<?, ?> map = rawMap instanceof Map ? (Map<?, ?>) rawMap : Collections.emptyMap();
            Method queryPerm = data.getMethod("queryPermission", String.class);
            Method resultMethod = result.getMethod("result");
            for (String candidate : candidates) {
                if (candidate.endsWith(".*") || (!registered.contains(candidate) && !map.containsKey(candidate))) {
                    continue;
                }
                Object query = queryPerm.invoke(cache, candidate);
                String state = String.valueOf(resultMethod.invoke(query));
                if (!"UNDEFINED".equals(state)) {
                    return new PermissionDecision("TRUE".equals(state), PermissionSource.LUCKPERMS, candidate, label);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================== Group Membership & Inheritance (Vault / LuckPerms via Reflection) ====================

    private Set<String> resolveMemberships(Player player, Collection<String> configured) {
        Set<String> names = new LinkedHashSet<String>();
        for (String group : configured) {
            if (player.hasPermission("group." + group)) {
                names.add(group);
            }
        }
        try {
            PluginManager pm = plugin.getServer().getPluginManager();
            if (pm != null && pm.isPluginEnabled("Vault")) {
                ServicesManager sm = plugin.getServer().getServicesManager();
                if (sm != null) {
                    Class<?> permClass = Class.forName("net.milkbowl.vault.permission.Permission");
                    Object perm = sm.load(permClass);
                    if (perm != null && Boolean.TRUE.equals(permClass.getMethod("hasGroupSupport").invoke(perm))) {
                        Method inGroup = permClass.getMethod("playerInGroup", String.class, String.class, String.class);
                        for (String group : configured) {
                            if (Boolean.TRUE.equals(inGroup.invoke(perm, null, player.getName(), group))) {
                                names.add(group);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    private Map<String, Set<String>> resolveImplicitParents(Collection<String> configured) {
        Map<String, Set<String>> output = new LinkedHashMap<String, Set<String>>();
        try {
            PluginManager pm = plugin.getServer().getPluginManager();
            Plugin peer = pm == null ? null : pm.getPlugin("LuckPerms");
            if (peer == null || !peer.isEnabled()) return Collections.emptyMap();
            ClassLoader loader = peer.getClass().getClassLoader();
            Class<?> api = Class.forName("net.luckperms.api.LuckPerms", false, loader);
            Object lp = Class.forName("net.luckperms.api.LuckPermsProvider", false, loader).getMethod("get").invoke(null);
            Object manager = api.getMethod("getGroupManager").invoke(lp);
            Class<?> groupManager = Class.forName("net.luckperms.api.model.group.GroupManager", false, loader);
            Class<?> nodeType = Class.forName("net.luckperms.api.node.NodeType", false, loader);
            Class<?> holder = Class.forName("net.luckperms.api.model.PermissionHolder", false, loader);
            Class<?> inheritance = Class.forName("net.luckperms.api.node.types.InheritanceNode", false, loader);
            Class<?> nodeClass = Class.forName("net.luckperms.api.node.Node", false, loader);
            Object type = nodeType.getField("INHERITANCE").get(null);
            Method getGroup = groupManager.getMethod("getGroup", String.class);
            Method getNodes = holder.getMethod("getNodes", nodeType);
            Method getValue = nodeClass.getMethod("getValue");
            Method getGroupName = inheritance.getMethod("getGroupName");

            for (String child : configured) {
                Set<String> seen = new LinkedHashSet<String>();
                Queue<String> queue = new ArrayDeque<String>();
                queue.add(child);
                while (!queue.isEmpty() && seen.size() < 256) {
                    String next = queue.remove();
                    Object group = getGroup.invoke(manager, next);
                    if (group == null) continue;
                    Object rawNodes = getNodes.invoke(group, type);
                    if (!(rawNodes instanceof Collection)) continue;
                    for (Object node : (Collection<?>) rawNodes) {
                        if (!Boolean.TRUE.equals(getValue.invoke(node))) continue;
                        String parent = String.valueOf(getGroupName.invoke(node)).toLowerCase(Locale.ROOT);
                        if (!parent.equals(child) && seen.add(parent)) queue.add(parent);
                    }
                }
                int weight = vaultGroupWeight(child);
                java.util.Iterator<String> it = seen.iterator();
                while (it.hasNext()) {
                    String parent = it.next();
                    if (!configured.contains(parent) || vaultGroupWeight(parent) > weight) it.remove();
                }
                output.put(child, Collections.unmodifiableSet(seen));
            }
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
        return output;
    }

    private int vaultGroupWeight(String name) {
        try {
            ServicesManager sm = plugin.getServer().getServicesManager();
            if (sm == null) return 0;
            Class<?> chatClass = Class.forName("net.milkbowl.vault.chat.Chat");
            Object chat = sm.load(chatClass);
            if (chat == null) return 0;
            Method getInfo = chatClass.getMethod("getGroupInfoString", String.class, String.class, String.class, String.class);
            Object val = getInfo.invoke(chat, null, name, "weight", "0");
            return val instanceof String ? Integer.parseInt((String) val) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ==================== Configuration Loading & Saving ====================

    private void ensureHideFile() {
        if (hideFile.isFile()) return;
        InputStream bundled = plugin.getResource("hide.yml");
        if (bundled == null) return;
        try {
            File parent = hideFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.copy(bundled, hideFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            plugin.getLogger().warning("Could not write default hide.yml: " + error.getMessage());
        } finally {
            try { bundled.close(); } catch (IOException ignored) {}
        }
    }

    private void loadHideConfig() {
        try {
            YamlConfiguration loaded = readHideYaml();
            Settings prepared = parseSettings(loaded);
            hideConfig = loaded;
            settings = prepared;
        } catch (Exception invalid) {
            plugin.getLogger().warning("Invalid hide.yml (" + invalid.getMessage()
                    + "); non-bypass command access is blocked until a valid reload.");
            YamlConfiguration fallback = new YamlConfiguration();
            fallback.set("settings.enabled", Boolean.TRUE);
            fallback.set("groups.default.whitelist", Boolean.TRUE);
            hideConfig = fallback;
            settings = parseSettings(fallback);
        }
    }

    private boolean applyConfigUpdate(CommandSender actor, Consumer<YamlConfiguration> edit) {
        try {
            YamlConfiguration next = readHideYaml();
            if (edit != null) edit.accept(next);
            Settings prepared = parseSettings(next);
            if (edit != null) {
                if (hideFile.isFile()) {
                    try {
                        Files.copy(hideFile.toPath(),
                                new File(hideFile.getParentFile(), "hide.yml.before-edit").toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {
                    }
                }
                LegacyCompat.saveYamlAtomically(next, hideFile);
            }
            hideConfig = next;
            settings = prepared;
            refreshCommands();
            updateAllOnlinePlayers();
            plugin.getLogger().info("Hide settings updated by " + (actor == null ? "SYSTEM" : actor.getName()));
            return true;
        } catch (Exception invalid) {
            plugin.getLogger().warning("Hide update refused: " + invalid.getMessage());
            if (actor != null) {
                messages.send(actor, "hide.failed",
                        "{prefix} &cHide configuration was refused. Check console for details.");
            }
            return false;
        }
    }

    private YamlConfiguration readHideYaml() throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        if (!hideFile.isFile()) {
            return new YamlConfiguration();
        }
        if (hideFile.length() > MAX_HIDE_FILE_BYTES) {
            throw new IOException("hide.yml exceeds 1 MiB");
        }
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.load(hideFile);
        return loaded;
    }

    private Settings parseSettings(YamlConfiguration c) {
        for (String section : Arrays.asList("settings", "groups")) {
            if (c.contains(section) && !c.isConfigurationSection(section)) {
                throw new IllegalArgumentException("hide " + section + " must be a section");
            }
        }
        for (String flag : Arrays.asList(
                "enabled", "use-allium-permission-resolver", "block-namespaced-commands-for-ops",
                "hide-namespaced-commands-for-ops", "remove-unsafe-commands",
                "filter-command-suggestions", "cancel-bare-slash-request",
                "cancel-unknown-suggestion-responses")) {
            if (c.contains("settings." + flag) && !(c.get("settings." + flag) instanceof Boolean)) {
                throw new IllegalArgumentException("hide flag must be boolean: " + flag);
            }
        }
        if (c.contains("permission-overrides") && !(c.get("permission-overrides") instanceof List)) {
            throw new IllegalArgumentException("permission-overrides must be a list");
        }

        ConfigurationSection groupsSec = c.getConfigurationSection("groups");
        Set<String> groupKeysLower = new LinkedHashSet<String>();
        if (groupsSec != null) {
            for (String k : groupsSec.getKeys(false)) {
                groupKeysLower.add(k.toLowerCase(Locale.ROOT));
            }
        }
        Map<String, Set<String>> implicitParents = resolveImplicitParents(groupKeysLower);
        List<HidePolicyEngine.Definition> definitions = new ArrayList<HidePolicyEngine.Definition>();
        if (groupsSec != null) {
            for (String name : groupsSec.getKeys(false)) {
                ConfigurationSection g = groupsSec.getConfigurationSection(name);
                if (g == null) throw new IllegalArgumentException("hide group must be a section");
                if (g.contains("whitelist") && !(g.get("whitelist") instanceof Boolean)) {
                    throw new IllegalArgumentException("hide whitelist must be boolean");
                }
                Set<String> cmdParents = new LinkedHashSet<String>(readStringList(g, "inherits.commands"));
                Set<String> tabParents = new LinkedHashSet<String>(readStringList(g, "inherits.tabcompletes"));
                Set<String> implicit = implicitParents.get(name.toLowerCase(Locale.ROOT));
                if (implicit != null) {
                    for (String parent : implicit) {
                        if (groupKeysLower.contains(parent.toLowerCase(Locale.ROOT))) {
                            cmdParents.add(parent);
                            tabParents.add(parent);
                        }
                    }
                }
                definitions.add(new HidePolicyEngine.Definition(
                        name,
                        g.getBoolean("whitelist", true),
                        readStringList(g, "commands"),
                        readStringList(g, "tabcompletes"),
                        new ArrayList<String>(cmdParents),
                        new ArrayList<String>(tabParents)));
            }
        }

        Map<String, String> overrides = new LinkedHashMap<String, String>();
        for (Map<?, ?> row : c.getMapList("permission-overrides")) {
            Object rawCmd = row.get("command");
            Object rawPerm = row.get("permission");
            String cmd = rawCmd == null ? "" : String.valueOf(rawCmd).toLowerCase(Locale.ROOT);
            String perm = rawPerm == null ? "" : String.valueOf(rawPerm);
            if (!cmd.matches("[a-z0-9_/:?.-]{1,128}") || !perm.matches("[A-Za-z0-9_.*:-]{1,256}")) {
                throw new IllegalArgumentException("invalid hide permission override");
            }
            if (overrides.put(cmd, perm) != null) {
                throw new IllegalArgumentException("duplicate hide permission override");
            }
        }

        Set<String> rawUnsafe = readNormalizedSet(c, "settings.unsafe-commands-list");
        Set<String> keptUnsafe = new LinkedHashSet<String>();
        for (String label : rawUnsafe) {
            if (label.contains(":") || !ownsLabel(label)) {
                keptUnsafe.add(label);
            }
        }

        return new Settings(
                c.getBoolean("settings.enabled", false),
                c.getBoolean("settings.use-allium-permission-resolver", true),
                c.getBoolean("settings.block-namespaced-commands-for-ops", false),
                c.getBoolean("settings.hide-namespaced-commands-for-ops", false),
                c.getBoolean("settings.remove-unsafe-commands", true),
                keptUnsafe,
                c.getBoolean("settings.filter-command-suggestions", true),
                c.getBoolean("settings.cancel-bare-slash-request", true),
                readNormalizedSet(c, "settings.allow-namespaced-suggestions"),
                new ArrayList<String>(readNormalizedSet(c, "settings.command-suggestion-filters")),
                new HidePolicyEngine(definitions),
                overrides);
    }

    private static Settings defaultDisabledSettings() {
        return new Settings(false, true, false, false, true, Collections.<String>emptySet(),
                true, true, Collections.<String>emptySet(), Collections.<String>emptyList(),
                new HidePolicyEngine(Collections.<HidePolicyEngine.Definition>emptyList()),
                Collections.<String, String>emptyMap());
    }

    private boolean ownsLabel(String label) {
        try {
            PluginCommand cmd = plugin.getServer().getPluginCommand(label);
            return cmd != null && cmd.getPlugin() == plugin;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void refreshCommands() {
        Map<String, Command> known = LegacyCommandManager.inspectKnownCommands(plugin.getServer());
        if (known != null) {
            commands = known;
        }
    }

    private Map<String, Command> currentCommands() {
        Map<String, Command> current = commands;
        if (current == null || current.isEmpty()) {
            refreshCommands();
            current = commands;
        }
        return current == null ? Collections.<String, Command>emptyMap() : current;
    }

    private void updateAllOnlinePlayers() {
        if (PLAYER_UPDATE_COMMANDS == null) return;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            updatePlayerCommands(p);
        }
    }

    private static void updatePlayerCommands(Player player) {
        if (player == null || PLAYER_UPDATE_COMMANDS == null || !player.isOnline()) return;
        try {
            PLAYER_UPDATE_COMMANDS.invoke(player);
        } catch (Throwable ignored) {
        }
    }

    private static List<String> readStringList(ConfigurationSection c, String path) {
        Object raw = c.get(path);
        if (raw == null) return Collections.emptyList();
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("hide " + path + " must be a string list");
        }
        List<?> values = (List<?>) raw;
        List<String> out = new ArrayList<String>(values.size());
        for (Object item : values) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException("hide " + path + " must be a string list");
            }
            out.add((String) item);
        }
        return out;
    }

    private static Set<String> readNormalizedSet(ConfigurationSection c, String path) {
        Set<String> out = new LinkedHashSet<String>();
        for (String value : readStringList(c, path)) {
            String n = HidePolicyEngine.normalize(value);
            if (n.length() > 512) throw new IllegalArgumentException("hide rule too long");
            if (!n.isEmpty()) out.add(n);
        }
        if (out.size() > 4096) throw new IllegalArgumentException("too many hide filters");
        return out;
    }

    private static String groupPath(YamlConfiguration c, String name) {
        ConfigurationSection g = c.getConfigurationSection("groups");
        if (g == null) throw new IllegalArgumentException("missing groups");
        for (String key : g.getKeys(false)) {
            if (key.equalsIgnoreCase(name)) return "groups." + key;
        }
        throw new IllegalArgumentException("missing group");
    }

    private static String stripLeadingSlash(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static String joinArgs(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static String joinStrings(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
