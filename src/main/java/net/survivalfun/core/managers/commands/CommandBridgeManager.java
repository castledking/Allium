package net.survivalfun.core.managers.commands;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.lang.Lang;
import net.survivalfun.core.managers.core.Text;
import org.bukkit.Bukkit;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Consumer;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and executes admin-defined command bridges from plugins/Allium/commands.yml.
 * Each bridge is triggered by an ID and executes a list of commands as console or player
 * with optional cooldown, permissions, Vault money cost, XP levels, and PlaceholderAPI support.
 */
public class CommandBridgeManager {
    private final PluginStart plugin;
    private final File file;
    private YamlConfiguration cfg;

    public static class BridgeDef {
        public String id;
        public String runAs = "console"; // console|player
        public List<String> execute = new ArrayList<>();
        public String permission; // optional
        public long cooldownMs = 0L; // parsed
        // If true, resolve placeholders in execute commands
        public boolean placeholders = true;
        // PAPI conditions: list of entries like "%statistic_hourse_played%>=10;have at least 10 hours of playtime"
        public List<String> papiConditions = new ArrayList<>();
        public boolean allowBaseCmd = false; // Paper-only true; placeholder for now
        public double money = 0.0; // Vault
        public int xpLevels = 0;
        public Set<String> worldsBlocklist = Collections.emptySet();
    }

    private final Map<String, BridgeDef> bridges = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastUse = new ConcurrentHashMap<>();
    // Track dynamically registered commands for AllowBaseCmd
    private final Map<String, Command> registeredBase = new HashMap<>();

    public CommandBridgeManager(PluginStart plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "commands.yml");
    }

    public void load() {
        try {
            if (!file.exists()) {
                // Write a simple default
                file.getParentFile().mkdirs();
                org.bukkit.configuration.file.YamlConfiguration def = new org.bukkit.configuration.file.YamlConfiguration();
                def.set("commands.closedialog.run_as", "console");
                def.set("commands.closedialog.execute", java.util.List.of("msg %player_name% Thanks for visiting!"));
                def.set("commands.closedialog.cooldown", 0);
                def.set("commands.closedialog.papi-conditions", new java.util.ArrayList<>()); // conditions list default empty
                def.set("commands.closedialog.allow-base-cmd", false);
                def.save(file);
            }
            cfg = YamlConfiguration.loadConfiguration(file);
            bridges.clear();
            // Unregister any previously registered base commands before reloading
            unregisterBaseCommands();
            if (cfg.isConfigurationSection("commands")) {
                for (String id : cfg.getConfigurationSection("commands").getKeys(false)) {
                    String base = "commands." + id + ".";
                    BridgeDef d = new BridgeDef();
                    d.id = id;
                    d.runAs = cfg.getString(base + "run_as", "console");
                    d.execute = cfg.getStringList(base + "execute");
                    d.permission = cfg.getString(base + "permission", null);
                    Object cd = cfg.get(base + "cooldown");
                    if (cd instanceof Number) {
                        d.cooldownMs = ((Number) cd).longValue() * 1000L; // seconds -> ms
                    } else {
                        d.cooldownMs = parseDuration(cfg.getString(base + "cooldown", "0"));
                    }
                    // Prefer new key: 'papi-conditions' (list). If absent, support legacy 'papi' boolean or list.
                    List<String> newPapiList = cfg.getStringList(base + "papi-conditions");
                    if (newPapiList != null && !newPapiList.isEmpty()) {
                        d.placeholders = false; // conditions present do not imply execute placeholder expansion
                        d.papiConditions = newPapiList;
                    } else {
                        // Back-compat: if papi is a boolean, treat as 'placeholders' flag for execute
                        Object papiNode = cfg.get(base + "papi");
                        if (papiNode instanceof Boolean) {
                            // Legacy: papi: true/false controlled execute placeholder expansion only
                            d.placeholders = (Boolean) papiNode;
                            d.papiConditions = new ArrayList<>();
                        } else {
                            // Legacy: 'papi' as list of conditions
                            d.placeholders = false;
                            d.papiConditions = cfg.getStringList(base + "papi");
                            if (d.papiConditions == null) d.papiConditions = new ArrayList<>();
                        }
                    }
                    // New key: allow-base-cmd (fallback to legacy AllowBaseCmd)
                    d.allowBaseCmd = cfg.getBoolean(base + "allow-base-cmd",
                            cfg.getBoolean(base + "AllowBaseCmd", false));
                    d.money = cfg.getDouble(base + "cost.money", 0.0);
                    d.xpLevels = cfg.getInt(base + "cost.xp_levels", 0);
                    // New key: worlds-blocklist (fallback to legacy worlds_blocklist)
                    List<String> wl = cfg.getStringList(base + "worlds-blocklist");
                    if (wl == null || wl.isEmpty()) {
                        wl = cfg.getStringList(base + "worlds_blocklist");
                    }
                    d.worldsBlocklist = new HashSet<>(wl == null ? Collections.emptyList() : wl);
                    bridges.put(id.toLowerCase(Locale.ROOT), d);
                }
            }
            Text.sendDebugLog(INFO, "Loaded " + bridges.size() + " command bridge(s) from commands.yml");
            // After loading, register base commands for bridges that request it
            registerBaseCommands();
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed loading commands.yml: " + e.getMessage());
        }
    }

    public Set<String> getIds() {
        return new TreeSet<>(bridges.keySet());
    }

    public boolean execute(Player player, String id) {
        if (player == null || id == null) return false;
        BridgeDef d = bridges.get(id.toLowerCase(Locale.ROOT));
        if (d == null) return false;
        Lang lang = plugin.getLangManager();
        // world blocklist
        if (d.worldsBlocklist.contains(player.getWorld().getName())) {
            Text.sendErrorMessage(player, "world-not-allowed", lang, "/{cmd}", "this command", "{world}", player.getWorld().getName(), true);
            return false;
        }
        // PAPI Conditions
        if (!d.papiConditions.isEmpty()) {
            if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Text.sendDebugLog(ERROR, "PlaceholderAPI is required to evaluate 'papi' conditions for command id '" + d.id + "' but is not loaded.");
                Text.sendErrorMessage(player, "condition-not-met", lang, "{condition}", "meet required conditions", "/{cmd}", "this command", true);
                return false;
            }
            for (String entry : d.papiConditions) {
                if (entry == null || entry.isEmpty()) continue;
                String[] parts = entry.split(";", 2);
                String expr = parts[0].trim();
                String msg = parts.length > 1 ? parts[1].trim() : "a required condition";
                try {
                    if (!evaluatePapiCondition(player, expr)) {
                        Text.sendErrorMessage(player, "condition-not-met", lang, "{condition}", msg, "/{cmd}", "this command", true);
                        return false;
                    }
                } catch (Throwable t) {
                    Text.sendDebugLog(ERROR, "Error evaluating PAPI condition '" + expr + "' for id '" + d.id + "': " + t.getMessage());
                    Text.sendErrorMessage(player, "condition-not-met", lang, "{condition}", "meet required conditions", "/{cmd}", "this command", true);
                    return false;
                }
            }
        }
        // permission
        if (d.permission != null && !d.permission.isEmpty() && !player.hasPermission(d.permission)) {
            Text.sendErrorMessage(player, "no-permission", lang, "/{cmd}", "this command", true);
            return false;
        }
        // cooldown
        long now = System.currentTimeMillis();
        Map<String, Long> map = lastUse.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        Long last = map.get(d.id);
        if (last != null && d.cooldownMs > 0 && (now - last) < d.cooldownMs && !player.hasPermission("allium.nocooldown")) {
            long remain = d.cooldownMs - (now - last);
            int secondsLeft = (int) Math.ceil(remain / 1000.0);
            String timeStr = Text.formatTime(secondsLeft);
            Text.sendErrorMessage(player, "cooldown", lang, "{time}", timeStr, "{cmd}", "this command", true);
            return false;
        }
        // costs: money
        if (d.money > 0.0) {
            net.milkbowl.vault.economy.Economy econ = plugin.getEconomy();
            if (econ == null) {
                Text.sendErrorMessage(player, "condition-not-met", lang, "{condition}", "have an economy available", "/{cmd}", "this command", true);
                return false;
            }
            if (!econ.has(player, d.money)) {
                String needed = econ.format(d.money);
                Text.sendErrorMessage(player, "condition-not-met", lang, "{condition}", "have " + needed, "/{cmd}", "this command", true);
                return false;
            }
        }
        // costs: xp levels
        if (d.xpLevels > 0 && player.getLevel() < d.xpLevels) {
            Text.sendErrorMessage(player, "condition-not-met", lang, "{condition}", "have " + d.xpLevels + " amount of xp", "/{cmd}", "this command", true);
            return false;
        }

        // All checks passed -> deduct costs, set cooldown, execute
        if (d.money > 0.0) {
            net.milkbowl.vault.economy.Economy econ = plugin.getEconomy();
            if (econ != null) econ.withdrawPlayer(player, d.money);
        }
        if (d.xpLevels > 0) player.setLevel(player.getLevel() - d.xpLevels);
        map.put(d.id, now);

        for (String raw : d.execute) {
            String cmd = resolve(player, raw, d.placeholders);
            if (cmd == null || cmd.isEmpty()) continue;
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if ("player".equalsIgnoreCase(d.runAs)) {
                runPlayerCommandFoliaSafe(player, cmd);
            } else {
                runConsoleCommandFoliaSafe(cmd);
            }
        }
        return true;
    }

    private String resolve(Player player, String input, boolean doPlaceholders) {
        if (input == null) return null;
        String s = input.replace("%player_name%", player.getName());
        if (doPlaceholders && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                s = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, s);
            } catch (Throwable ignored) {}
        }
        return s;
    }

    private boolean evaluatePapiCondition(Player player, String expr) {
        // Supported operators: >=, <=, ==, !=, >, <
        String[] ops = new String[]{">=","<=","==","!=",">","<"};
        String opFound = null;
        for (String op : ops) {
            int idx = expr.indexOf(op);
            if (idx > 0) { opFound = op; break; }
        }
        if (opFound == null) return true; // cannot parse -> don't block
        String left = expr.substring(0, expr.indexOf(opFound)).trim();
        String right = expr.substring(expr.indexOf(opFound) + opFound.length()).trim();
        String leftVal = left;
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try { leftVal = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, left); } catch (Throwable ignored) {}
        }
        // Try numeric compare
        Double lNum = tryParseDouble(leftVal);
        Double rNum = tryParseDouble(right);
        if (lNum != null && rNum != null) {
            int cmp = Double.compare(lNum, rNum);
            switch (opFound) {
                case ">=": return cmp >= 0;
                case "<=": return cmp <= 0;
                case ">": return cmp > 0;
                case "<": return cmp < 0;
                case "==": return cmp == 0;
                case "!=": return cmp != 0;
            }
        } else {
            // String compare (case sensitive)
            switch (opFound) {
                case "==": return Objects.equals(leftVal, right);
                case "!=": return !Objects.equals(leftVal, right);
                default: return false; // ordering operators not supported for strings
            }
        }
        return true;
    }

    private Double tryParseDouble(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }

    // ===== Dynamic base command registration (Spigot fallback via reflection) =====
    private void registerBaseCommands() {
        for (BridgeDef d : bridges.values()) {
            if (!d.allowBaseCmd) continue;
            String label = d.id.toLowerCase(Locale.ROOT);
            // Hard-protect commands that must not be overridden by bridges
            if ("dialog".equals(label)) {
                Text.sendDebugLog(WARN, "Skipping registration of base command '/dialog' because it conflicts with the native Minecraft command.");
                continue;
            }
            if ("ar".equals(label) || "autorestart".equals(label)) {
                Text.sendDebugLog(WARN, "Skipping registration of base command '/" + label + "' - reserved for AutoRestart.");
                continue;
            }
            try {
                CommandMap map = getCommandMap();
                if (map == null) continue;
                // If a command already exists, try to identify and remove it
                Command existing = map.getCommand(label);
                if (existing != null) {
                    // If the existing command is likely native (e.g., minecraft:label), do not override
                    if (isNativeMinecraftCommand(map, label, existing)) {
                        Text.sendDebugLog(WARN, "Skipping registration of '/" + label + "' because an existing native Minecraft command was detected.");
                        continue;
                    }
                    String owner = detectOwner(existing);
                    Text.sendDebugLog(WARN, "Remapping existing command '/" + label + "' owned by " + owner + " to Allium bridge");
                    forceUnregister(map, label, existing);
                }
                Command cmd = new BridgeBukkitCommand(label, d.permission);
                // Namespace register under our plugin name
                map.register(plugin.getName().toLowerCase(Locale.ROOT), cmd);
                registeredBase.put(label, cmd);
            } catch (Throwable t) {
                Text.sendDebugLog(WARN, "Failed to register base command '/" + d.id + "': " + t.getMessage());
            }
        }
    }

    private void unregisterBaseCommands() {
        if (registeredBase.isEmpty()) return;
        try {
            CommandMap map = getCommandMap();
            if (map == null) return;
            for (Map.Entry<String, Command> e : new ArrayList<>(registeredBase.entrySet())) {
                forceUnregister(map, e.getKey(), e.getValue());
            }
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "Failed to unregister base commands: " + t.getMessage());
        } finally {
            registeredBase.clear();
        }
    }

    private CommandMap getCommandMap() throws Exception {
        Object server = Bukkit.getServer();
        try {
            java.lang.reflect.Method m = server.getClass().getMethod("getCommandMap");
            Object cm = m.invoke(server);
            if (cm instanceof CommandMap) return (CommandMap) cm;
        } catch (NoSuchMethodException ignored) {}
        // Fallback: CraftServer field
        try {
            java.lang.reflect.Field f = server.getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            Object cm = f.get(server);
            if (cm instanceof CommandMap) return (CommandMap) cm;
        } catch (NoSuchFieldException ignored) {}
        Text.sendDebugLog(ERROR, "Unable to access CommandMap for dynamic command registration.");
        return null;
    }

    @SuppressWarnings("unchecked")
    private void forceUnregister(CommandMap map, String label, Command command) {
        try {
            java.lang.reflect.Field f = map.getClass().getDeclaredField("knownCommands");
            f.setAccessible(true);
            Map<String, Command> known = (Map<String, Command>) f.get(map);
            // Remove by label and namespaced key
            known.remove(label);
            known.remove(plugin.getName().toLowerCase(Locale.ROOT) + ":" + label);
            // If it's a PluginCommand, call unregister
            try { command.unregister(map); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Text.sendDebugLog(WARN, "Unable to unregister existing command '/" + label + "': " + t.getMessage());
        }
    }

    private String detectOwner(Command cmd) {
        try {
            if (cmd instanceof PluginCommand) {
                PluginCommand pc = (PluginCommand) cmd;
                org.bukkit.plugin.Plugin p = pc.getPlugin();
                if (p != null) return p.getName();
            }
        } catch (Throwable ignored) {}
        // Try to infer from toString()
        try { return String.valueOf(cmd); } catch (Throwable ignored) {}
        return "unknown";
    }

    private boolean isNativeMinecraftCommand(CommandMap map, String label, Command existing) {
        try {
            // If a command exists under the minecraft namespace for this label, treat it as native
            Command mc = map.getCommand("minecraft:" + label);
            if (mc != null) {
                // Same class or clearly non-plugin indicates native/vanilla
                if (mc.getClass().equals(existing.getClass())) return true;
                // If neither is a PluginCommand, conservatively assume native
                boolean existingIsPlugin = (existing instanceof PluginCommand);
                boolean mcIsPlugin = (mc instanceof PluginCommand);
                if (!existingIsPlugin && !mcIsPlugin) return true;
            }
            // Heuristic: class name suggests vanilla/brigadier wrappers
            String cn = existing.getClass().getName().toLowerCase(Locale.ROOT);
            if (cn.contains("minecraft") || cn.contains("vanilla") || cn.contains("brigadier")) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private final class BridgeBukkitCommand extends Command {
        private final String perm;
        private BridgeBukkitCommand(String label, String perm) { super(label); this.perm = perm; setPermission(perm); setDescription("Allium bridged command"); }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            if (!(sender instanceof Player)) {
                Text.sendErrorMessage(sender, "not-a-player", plugin.getLangManager());
                return true;
            }
            Player player = (Player) sender;
            // Permission handled by our execute(), but also guard here to let Bukkit short-circuit
            if (perm != null && !perm.isEmpty() && !player.hasPermission(perm)) {
                Text.sendErrorMessage(player, "no-permission", plugin.getLangManager(), "/{cmd}", "this command", true);
                return true;
            }
            CommandBridgeManager.this.execute(player, commandLabel); // delegate to bridge by id
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    // ===== Folia-safe command execution helpers =====
    private void runConsoleCommandFoliaSafe(String cmd) {
        boolean scheduled = false;
        try {
            // Try GlobalRegionScheduler first
            Method getGRS = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
            Object grs = getGRS.invoke(Bukkit.getServer());
            Method execute = grs.getClass().getMethod("execute", Plugin.class, Runnable.class);
            execute.invoke(grs, plugin, (Runnable) () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            scheduled = true;
        } catch (Throwable ignored) {}

        if (!scheduled) {
            // Legacy fallback
            try {
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            } catch (Throwable t) {
                Text.sendDebugLog(WARN, "Failed to schedule console command: /" + cmd + " -> " + t.getMessage());
            }
        }
    }

    private void runPlayerCommandFoliaSafe(Player player, String cmd) {
        boolean scheduled = false;
        try {
            // Prefer entity scheduler on Folia
            Method getScheduler = player.getClass().getMethod("getScheduler");
            Object entityScheduler = getScheduler.invoke(player);
            try {
                // Newer Folia: execute(Plugin, Runnable)
                Method execute = entityScheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
                execute.invoke(entityScheduler, plugin, (Runnable) () -> player.performCommand(cmd));
                scheduled = true;
            } catch (NoSuchMethodException ignored) {
                // Older Folia: run(Plugin, Consumer, Object)
                try {
                    Method run = entityScheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Object.class);
                    Consumer<Object> task = (ignore) -> player.performCommand(cmd);
                    run.invoke(entityScheduler, plugin, task, null);
                    scheduled = true;
                } catch (NoSuchMethodException ignored2) {}
            }
        } catch (Throwable ignored) {}

        if (!scheduled) {
            // Fallback: main thread
            try {
                Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(cmd));
            } catch (Throwable t) {
                Text.sendDebugLog(WARN, "Failed to schedule player command for " + player.getName() + ": /" + cmd + " -> " + t.getMessage());
            }
        }
    }

    private long parseDuration(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {
            s = s.trim().toLowerCase(Locale.ROOT);
            long mul = 1000L;
            if (s.endsWith("ms")) { mul = 1L; s = s.substring(0, s.length()-2); }
            else if (s.endsWith("s")) { mul = 1000L; s = s.substring(0, s.length()-1); }
            else if (s.endsWith("m")) { mul = 60_000L; s = s.substring(0, s.length()-1); }
            else if (s.endsWith("h")) { mul = 3_600_000L; s = s.substring(0, s.length()-1); }
            else if (s.endsWith("d")) { mul = 86_400_000L; s = s.substring(0, s.length()-1); }
            double val = Double.parseDouble(s);
            return (long) (val * mul);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
