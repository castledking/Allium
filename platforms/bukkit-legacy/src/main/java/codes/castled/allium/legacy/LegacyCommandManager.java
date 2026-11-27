package codes.castled.allium.legacy;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Java 8 / 1.12.2-1.19.4 command manager:
 * tracks Allium command families from {@code plugin.yml}, enforces {@code disabled-commands} and
 * {@code modules.<name>} toggles from {@code config.yml}, yields disabled commands from Bukkit's
 * {@link CommandMap} on startup, and provides permission-filtered command entries for {@code /help}.
 */
final class LegacyCommandManager {

    static final class HelpEntry {
        final String path;
        final String owner;
        final String description;
        final String usage;
        final String permission;

        HelpEntry(String path, String owner, String description, String usage, String permission) {
            this.path = path;
            this.owner = owner == null ? "" : owner;
            this.description = description == null ? "" : description;
            this.usage = usage == null || usage.isEmpty() ? "/" + path : usage;
            this.permission = permission == null ? "" : permission;
        }
    }

    private final JavaPlugin plugin;
    private final LegacyConfigManager config;
    /** Maps any lowercase label/alias -> canonical command name from plugin.yml. */
    private final Map<String, String> aliasToCanonical = new HashMap<String, String>();
    /** Maps canonical command name -> set of all labels (canonical + aliases). */
    private final Map<String, Set<String>> familyLabels = new LinkedHashMap<String, Set<String>>();
    /** Maps canonical command name -> owning module key in config.yml. */
    private final Map<String, String> commandModules = buildCommandModules();
    /** Canonical names currently disabled via config.yml disabled-commands. */
    private volatile Set<String> disabledFamilies = Collections.emptySet();
    private volatile LegacyHideModule hideModule;

    LegacyCommandManager(JavaPlugin plugin, LegacyConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    void setHideModule(LegacyHideModule hideModule) {
        this.hideModule = hideModule;
    }

    void enable() {
        indexDeclaredCommands();
        applyDisabledPolicy(true);
    }

    void reload() {
        applyDisabledPolicy(false);
    }

    int declaredCommandCount() {
        return familyLabels.size();
    }

    int activeCommandCount() {
        int count = 0;
        for (String canonical : familyLabels.keySet()) {
            if (!isDisabled(canonical) && !isModuleDisabled(canonical)) {
                count++;
            }
        }
        return count;
    }

    String canonicalOf(String rawLabel) {
        if (rawLabel == null) return "";
        String clean = rawLabel.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.startsWith("allium:")) clean = clean.substring("allium:".length());
        String mapped = aliasToCanonical.get(clean);
        return mapped != null ? mapped : clean;
    }

    boolean isDisabled(String rawLabel) {
        String canonical = canonicalOf(rawLabel);
        return disabledFamilies.contains(canonical);
    }

    boolean isModuleDisabled(String rawLabel) {
        String canonical = canonicalOf(rawLabel);
        String module = commandModules.get(canonical);
        return module != null && !config.isModuleEnabled(module);
    }

    String moduleOf(String rawLabel) {
        return commandModules.get(canonicalOf(rawLabel));
    }

    /** Returns a fresh, permission-filtered, case-insensitively sorted list of visible commands for {@code /help}. */
    List<HelpEntry> helpEntries(CommandSender sender) {
        Map<String, Command> known = inspectKnownCommands(plugin.getServer());
        Map<String, HelpEntry> dedup = new LinkedHashMap<String, HelpEntry>();

        if (known != null && !known.isEmpty()) {
            for (Map.Entry<String, Command> e : known.entrySet()) {
                String label = e.getKey();
                Command cmd = e.getValue();
                if (label == null || cmd == null || label.indexOf(':') >= 0) continue;
                String lower = label.toLowerCase(Locale.ROOT);
                // Only include primary name of each command to avoid duplicate alias spam in main /help
                if (!lower.equals(cmd.getName().toLowerCase(Locale.ROOT))) continue;
                if (isOwnedByAllium(cmd) && (isDisabled(lower) || isModuleDisabled(lower))) continue;
                LegacyHideModule hide = this.hideModule;
                if (hide != null && hide.isActive()) {
                    if (!hide.isVisibleInHelp(sender, lower, cmd)) continue;
                } else if (!cmd.testPermissionSilent(sender)) {
                    continue;
                }
                String owner = resolveOwner(cmd);
                String desc = cmd.getDescription() != null ? cmd.getDescription() : "";
                String usage = cmd.getUsage() != null && !cmd.getUsage().isEmpty()
                        ? cmd.getUsage().replace("<command>", lower)
                        : "/" + lower;
                String perm = cmd.getPermission() != null ? cmd.getPermission() : "";
                dedup.put(lower, new HelpEntry(lower, owner, desc, usage, perm));
            }
        } else {
            // Fallback when CommandMap reflection is unavailable (e.g. unit test mocks)
            PluginDescriptionFile descFile = plugin.getDescription();
            Map<String, Map<String, Object>> declared = descFile != null ? descFile.getCommands() : null;
            if (declared != null) {
                for (Map.Entry<String, Map<String, Object>> e : declared.entrySet()) {
                    String lower = e.getKey().toLowerCase(Locale.ROOT);
                    if (isDisabled(lower) || isModuleDisabled(lower)) continue;
                    Map<String, Object> meta = e.getValue();
                    String perm = meta != null && meta.get("permission") != null ? String.valueOf(meta.get("permission")) : "";
                    LegacyHideModule hide = this.hideModule;
                    if (hide != null && hide.isActive()) {
                        if (!hide.isVisibleInHelp(sender, lower, null)) continue;
                    }
                    if (!perm.isEmpty() && !sender.hasPermission(perm)) continue;
                    String description = meta != null && meta.get("description") != null ? String.valueOf(meta.get("description")) : "";
                    String usage = meta != null && meta.get("usage") != null
                            ? String.valueOf(meta.get("usage")).replace("<command>", lower)
                            : "/" + lower;
                    dedup.put(lower, new HelpEntry(lower, plugin.getName(), description, usage, perm));
                }
            }
        }

        List<HelpEntry> out = new ArrayList<HelpEntry>(dedup.values());
        Collections.sort(out, new Comparator<HelpEntry>() {
            @Override
            public int compare(HelpEntry a, HelpEntry b) {
                return a.path.compareToIgnoreCase(b.path);
            }
        });
        return out;
    }

    private void indexDeclaredCommands() {
        aliasToCanonical.clear();
        familyLabels.clear();
        PluginDescriptionFile desc = plugin.getDescription();
        Map<String, Map<String, Object>> declared = desc != null ? desc.getCommands() : null;
        if (declared == null) return;
        for (Map.Entry<String, Map<String, Object>> entry : declared.entrySet()) {
            String canonical = entry.getKey().toLowerCase(Locale.ROOT);
            Set<String> labels = new HashSet<String>();
            labels.add(canonical);
            aliasToCanonical.put(canonical, canonical);
            Map<String, Object> attrs = entry.getValue();
            if (attrs != null) {
                Object aliasesObj = attrs.get("aliases");
                if (aliasesObj instanceof List) {
                    for (Object a : (List<?>) aliasesObj) {
                        if (a != null) {
                            String al = String.valueOf(a).trim().toLowerCase(Locale.ROOT);
                            if (!al.isEmpty()) {
                                labels.add(al);
                                aliasToCanonical.put(al, canonical);
                            }
                        }
                    }
                } else if (aliasesObj instanceof String) {
                    String al = ((String) aliasesObj).trim().toLowerCase(Locale.ROOT);
                    if (!al.isEmpty()) {
                        labels.add(al);
                        aliasToCanonical.put(al, canonical);
                    }
                }
            }
            familyLabels.put(canonical, labels);
        }
    }

    private void applyDisabledPolicy(boolean yieldFromCommandMap) {
        Set<String> rawDisabled = config.disabledCommands();
        Set<String> resolved = new HashSet<String>();
        for (String token : rawDisabled) {
            String canonical = canonicalOf(token);
            if (!familyLabels.isEmpty() && !familyLabels.containsKey(canonical)) {
                plugin.getLogger().warning("Unknown command entry in disabled-commands: '" + token + "' (ignored).");
                continue;
            }
            resolved.add(canonical);
        }
        this.disabledFamilies = Collections.unmodifiableSet(resolved);

        if (yieldFromCommandMap && !resolved.isEmpty()) {
            Map<String, Command> known = inspectKnownCommands(plugin.getServer());
            if (known != null) {
                for (String canonical : resolved) {
                    Set<String> labels = familyLabels.get(canonical);
                    if (labels == null) labels = Collections.singleton(canonical);
                    for (String label : labels) {
                        yieldLabel(known, label);
                        yieldLabel(known, "allium:" + label);
                    }
                }
            }
        }
    }

    private void yieldLabel(Map<String, Command> known, String key) {
        Command existing = known.get(key);
        if (existing != null && isOwnedByAllium(existing)) {
            known.remove(key);
        }
    }

    private boolean isOwnedByAllium(Command cmd) {
        if (cmd instanceof PluginIdentifiableCommand) {
            return ((PluginIdentifiableCommand) cmd).getPlugin().getName().equalsIgnoreCase(plugin.getName());
        }
        if (cmd instanceof PluginCommand) {
            return ((PluginCommand) cmd).getPlugin().getName().equalsIgnoreCase(plugin.getName());
        }
        return false;
    }

    private String resolveOwner(Command cmd) {
        if (cmd instanceof PluginIdentifiableCommand) {
            return ((PluginIdentifiableCommand) cmd).getPlugin().getName();
        }
        if (cmd instanceof PluginCommand) {
            return ((PluginCommand) cmd).getPlugin().getName();
        }
        return "Minecraft";
    }

    @SuppressWarnings("unchecked")
    static Map<String, Command> inspectKnownCommands(Server server) {
        if (server == null) return null;
        try {
            CommandMap map = null;
            try {
                Method getCommandMap = server.getClass().getMethod("getCommandMap");
                Object res = getCommandMap.invoke(server);
                if (res instanceof CommandMap) map = (CommandMap) res;
            } catch (NoSuchMethodException ignored) {
                Field f = findField(server.getClass(), "commandMap");
                if (f != null) {
                    f.setAccessible(true);
                    Object res = f.get(server);
                    if (res instanceof CommandMap) map = (CommandMap) res;
                }
            }
            if (map == null) return null;
            try {
                Method getKnown = map.getClass().getMethod("getKnownCommands");
                Object res = getKnown.invoke(map);
                if (res instanceof Map) return (Map<String, Command>) res;
            } catch (NoSuchMethodException ignored) {
            }
            Field knownField = findField(map.getClass(), "knownCommands");
            if (knownField == null && map instanceof SimpleCommandMap) {
                knownField = SimpleCommandMap.class.getDeclaredField("knownCommands");
            }
            if (knownField == null) return null;
            knownField.setAccessible(true);
            Object val = knownField.get(map);
            return val instanceof Map ? (Map<String, Command>) val : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cur = type;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static Map<String, String> buildCommandModules() {
        Map<String, String> m = new HashMap<String, String>();
        // Economy & Worth
        for (String c : new String[]{"balance", "pay", "paytoggle", "payconfirmtoggle", "eco", "baltop"}) m.put(c, "economy");
        for (String c : new String[]{"worth", "setworth", "sell"}) m.put(c, "worth");
        // Kits
        for (String c : new String[]{"kit", "kits", "createkit", "delkit", "showkit", "kitreset"}) m.put(c, "kits");
        // Messaging & Mail
        for (String c : new String[]{"msg", "reply", "rtoggle", "msgtoggle", "ignore", "unignore"}) m.put(c, "messaging");
        m.put("messagesounds", "preferences");
        m.put("toggle", "preferences");
        m.put("toggles", "preferences");
        m.put("mail", "mail");
        // Moderation
        for (String c : new String[]{"vanish", "mute", "unmute", "mutes", "kick", "kickall", "ban", "tempban",
                "unban", "banlist", "ipban", "tempbanip", "unbanip", "freeze", "setjail", "deljail", "jails",
                "jail", "unjail", "togglejail", "jailedplayers", "warn", "notes", "unnote", "invsee",
                "helpop", "report", "socialspy", "commandspy", "spy", "spectate"}) {
            m.put(c, "moderation");
        }
        // Info
        for (String c : new String[]{"list", "seen", "whois", "near", "playtime", "afk"}) m.put(c, "info");
        // Homes / Warps / Spawn / RTP / Teleport
        for (String c : new String[]{"sethome", "home", "delhome", "renamehome", "homes", "bed"}) m.put(c, "homes");
        for (String c : new String[]{"setwarp", "warp", "delwarp", "warps", "warpinfo"}) m.put(c, "warps");
        for (String c : new String[]{"setspawn", "spawn"}) m.put(c, "spawn");
        for (String c : new String[]{"rtp", "settpr"}) m.put(c, "rtp");
        for (String c : new String[]{"tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel", "tpaall",
                "tptoggle", "tpauto", "tp", "tphere", "tpo", "tpohere", "tpall", "tppos", "tpoffline", "back",
                "top", "bottom", "jump", "there", "world", "tppet", "tpent"}) {
            m.put(c, "teleport");
        }
        // Explosions
        for (String c : new String[]{"fireball", "antioch", "nuke", "explode", "kittycannon"}) m.put(c, "explosions");
        // Chat
        for (String c : new String[]{"nick", "realname", "action", "channel", "clearchat"}) m.put(c, "chat");
        return m;
    }
}
