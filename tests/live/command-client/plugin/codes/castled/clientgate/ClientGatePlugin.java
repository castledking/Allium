package codes.castled.clientgate;

import codes.castled.allium.command.CommandMapBridge;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Validation-only plugin. Installs a competitor gamemode/gm family through the real server CommandMap
 * (resolved by Allium's CommandMapBridge.inspectPlatformAccess), then calls the real
 * CommandMapBridge.yieldCommandFamily(server, Allium, CoreCommandSpecs.GAMEMODE). The network client is the
 * primary observer; this plugin only records server-side diagnostics.
 */
public final class ClientGatePlugin extends JavaPlugin {
    private static final List<String> WATCH = List.of(
            "gamemode", "gm", "allium:gamemode", "allium:gm", "clientgate:gamemode", "clientgate:gm",
            "minecraft:gamemode");
    private CompetitorGamemodeCommand competitor;
    private final List<String> diagnostics = new ArrayList<>();

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        getLogger().info("CLIENTGATE_READY");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "state";
        String phase = args.length > 1 ? args[1] : sub;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", phase);
        out.put("action", sub);
        out.put("serverVersion", Bukkit.getVersion());
        out.put("bukkitVersion", Bukkit.getBukkitVersion());
        out.put("tsMillis", System.currentTimeMillis());
        try {
            switch (sub) {
                case "install" -> out.put("install", install());
                case "yield" -> {
                    Plugin allium = Bukkit.getPluginManager().getPlugin("Allium");
                    out.put("alliumPluginFound", allium != null);
                    diagnostics.clear();
                    long t0 = System.nanoTime();
                    boolean ok = CommandMapBridge.yieldCommandFamily(getServer(), allium, CoreCommandSpecs.GAMEMODE,
                            msg -> { diagnostics.add(msg); getLogger().warning("bridge: " + msg); });
                    out.put("yieldReturned", ok);
                    out.put("yieldNanos", System.nanoTime() - t0);
                    out.put("bridgeDiagnostics", new ArrayList<>(diagnostics));
                    out.put("specAllOwnedLabels", new ArrayList<>(CoreCommandSpecs.GAMEMODE.allOwnedLabels()));
                    out.put("specBareLabels", new ArrayList<>(CoreCommandSpecs.GAMEMODE.bareLabels()));
                }
                case "refresh" -> {
                    List<String> refreshed = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) { p.updateCommands(); refreshed.add(p.getName()); }
                    out.put("updateCommandsCalledFor", refreshed);
                }
                case "state" -> { }
                case "wireids" -> out.put("wirePackets", wirePacketIds());
                default -> out.put("error", "unknown subcommand " + sub);
            }
        } catch (Throwable t) {
            out.put("error", t.toString());
            out.put("errorStack", stack(t));
        }
        out.put("access", accessReport());
        out.put("knownCommands", knownSnapshot());
        out.put("brigadier", brigadierSnapshot());
        List<String> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getName() + (p.isOp() ? "(op)" : ""));
        out.put("onlinePlayers", online);
        String json = Json.write(out);
        try {
            Files.writeString(new File(getDataFolder(), "state-" + phase + ".json").toPath(), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            getLogger().warning("write failed " + e);
        }
        getLogger().info("CLIENTGATE_JSON " + json);
        getLogger().info("CLIENTGATE_DONE " + phase);
        return true;
    }

    private Map<String, Object> wirePacketIds() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        Class<?> provider = Class.forName("net.minecraft.network.ProtocolInfo$DetailsProvider");
        Class<?> detailsType = Class.forName("net.minecraft.network.ProtocolInfo$Details");
        Class<?> visitor = Class.forName("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
        Class<?> packetType = Class.forName("net.minecraft.network.protocol.PacketType");
        for (String phase : List.of("game", "configuration", "login")) {
            String capital = phase.substring(0, 1).toUpperCase() + phase.substring(1);
            Class<?> protocols = Class.forName("net.minecraft.network.protocol." + phase + "." + capital + "Protocols");
            for (String flow : List.of("CLIENTBOUND", "SERVERBOUND")) {
                Object template = protocols.getField(flow + "_TEMPLATE").get(null);
                Object details = provider.getMethod("details").invoke(template);
                Map<String, Integer> ids = new LinkedHashMap<>();
                Object callback = java.lang.reflect.Proxy.newProxyInstance(visitor.getClassLoader(), new Class[]{visitor},
                    (proxy, method, args) -> {
                        if (method.getName().equals("accept")) {
                            String id = packetType.getMethod("id").invoke(args[0]).toString();
                            ids.put(id, (Integer) args[1]);
                        }
                        return null;
                    });
                detailsType.getMethod("listPackets", visitor).invoke(details, callback);
                result.put(phase + "_" + flow, ids);
            }
        }
        return result;
    }

    private Map<String, Object> install() {
        Map<String, Object> r = new LinkedHashMap<>();
        CommandMapBridge.AccessReport access = CommandMapBridge.inspectPlatformAccess(getServer(), diagnostics::add);
        if (access == null) { r.put("error", "inspectPlatformAccess returned null"); r.put("diag", diagnostics); return r; }
        competitor = new CompetitorGamemodeCommand(this);
        Map<String, Command> kc = access.knownCommands();
        Command bareGmBefore = kc.get("gm"), bareGamemodeBefore = kc.get("gamemode");
        // Real competitor registration path: CommandMap#register with fallback prefix. Bare labels are held by Allium,
        // so Bukkit only installs clientgate:gamemode / clientgate:gm.
        boolean bareRegistered = access.commandMap().register("clientgate", competitor);
        r.put("commandMapRegisterReturned", bareRegistered);
        r.put("bareGmStillAllium", kc.get("gm") == bareGmBefore);
        r.put("bareGamemodeStillAllium", kc.get("gamemode") == bareGamemodeBefore);
        r.put("namespacedInstalled", kc.get("clientgate:gamemode") == competitor && kc.get("clientgate:gm") == competitor);
        return r;
    }

    private Map<String, Object> accessReport() {
        Map<String, Object> r = new LinkedHashMap<>();
        List<String> d = new ArrayList<>();
        CommandMapBridge.AccessReport a = CommandMapBridge.inspectPlatformAccess(getServer(), d::add);
        if (a == null) { r.put("null", true); r.put("diag", d); return r; }
        r.put("serverClass", a.serverClass());
        r.put("commandMapClass", a.commandMapClass());
        r.put("commandMapResolution", a.commandMapResolution());
        r.put("knownCommandsResolution", a.knownCommandsResolution());
        r.put("syncCommandsSupported", a.syncCommandsSupported());
        r.put("knownCommandsMapClass", a.knownCommands().getClass().getName());
        r.put("knownCommandsSize", a.knownCommands().size());
        r.put("diag", d);
        return r;
    }

    private Map<String, Object> knownSnapshot() {
        Map<String, Object> r = new LinkedHashMap<>();
        CommandMapBridge.AccessReport a = CommandMapBridge.inspectPlatformAccess(getServer(), s -> {});
        if (a == null) return r;
        for (String k : WATCH) r.put(k, describe(a.knownCommands().get(k)));
        return r;
    }

    private Object describe(Command c) {
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", c.getClass().getName());
        m.put("name", c.getName());
        m.put("label", c.getLabel());
        m.put("owner", c instanceof PluginIdentifiableCommand pic ? pic.getPlugin().getName() : "(non-plugin)");
        m.put("isCompetitor", c == competitor);
        m.put("identity", Integer.toHexString(System.identityHashCode(c)));
        return m;
    }

    // ---- Brigadier diagnostics (reflective, read-only) ----
    private Map<String, Object> brigadierSnapshot() {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            Object mc = getServer().getClass().getMethod("getServer").invoke(getServer());
            r.put("minecraftServerClass", mc.getClass().getName());
            List<Object[]> found = new ArrayList<>();
            findDispatchers(mc, "MinecraftServer", 0, Collections.newSetFromMap(new IdentityHashMap<>()), found, new int[]{0});
            List<Object> ds = new ArrayList<>();
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object[] f : found) {
                Object disp = f[1];
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("path", f[0]);
                dm.put("identity", Integer.toHexString(System.identityHashCode(disp)));
                dm.put("duplicateOfEarlierPath", !seen.add(disp));
                Object root = disp.getClass().getMethod("getRoot").invoke(disp);
                Collection<?> children = (Collection<?>) root.getClass().getMethod("getChildren").invoke(root);
                dm.put("rootChildCount", children.size());
                Map<String, Object> nodes = new LinkedHashMap<>();
                for (String k : WATCH) nodes.put(k, describeNode(root, k));
                dm.put("nodes", nodes);
                ds.add(dm);
            }
            r.put("dispatchers", ds);
        } catch (Throwable t) {
            r.put("error", t.toString());
        }
        return r;
    }

    private Object describeNode(Object root, String name) throws Exception {
        Method getChild = findMethod(root.getClass(), "getChild", String.class);
        Object node = getChild.invoke(root, name);
        if (node == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeClass", node.getClass().getName());
        Object cmd = findMethod(node.getClass(), "getCommand").invoke(node);
        m.put("commandClass", cmd == null ? null : cmd.getClass().getName());
        Object redirect = findMethod(node.getClass(), "getRedirect").invoke(node);
        m.put("redirectClass", redirect == null ? null : redirect.getClass().getName());
        List<String> bukkit = new ArrayList<>();
        for (Object holder : new Object[]{node, cmd}) {
            if (holder == null) continue;
            for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (!Command.class.isAssignableFrom(f.getType()) && f.getType() != Object.class) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(holder);
                        if (v instanceof Command bc) {
                            bukkit.add(holder.getClass().getSimpleName() + "." + f.getName() + " -> " + bc.getClass().getName()
                                    + " owner=" + (bc instanceof PluginIdentifiableCommand pic ? pic.getPlugin().getName() : "(non-plugin)")
                                    + (bc == competitor ? " [COMPETITOR]" : ""));
                        }
                    } catch (Throwable ignored) { }
                }
            }
        }
        m.put("wrappedBukkitCommands", bukkit);
        return m;
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Method m = k.getDeclaredMethod(name, params); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) { }
        }
        Method m = c.getMethod(name, params); m.setAccessible(true); return m;
    }

    private void findDispatchers(Object o, String path, int depth, Set<Object> visited, List<Object[]> found, int[] budget) {
        if (o == null || depth > 5 || budget[0]++ > 20000 || !visited.add(o)) return;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isArray()) continue;
                Object v;
                try { f.setAccessible(true); v = f.get(o); } catch (Throwable t) { continue; }
                if (v == null) continue;
                String cn = v.getClass().getName();
                String p = path + "." + f.getName();
                if (isDispatcher(v.getClass())) { found.add(new Object[]{p + " (" + cn + ")", v}); continue; }
                if (cn.startsWith("net.minecraft.") || cn.startsWith("io.papermc.") || cn.startsWith("org.bukkit.craftbukkit.")) {
                    if (v instanceof Collection || v instanceof Map || v instanceof Thread) continue;
                    findDispatchers(v, p, depth + 1, visited, found, budget);
                }
            }
        }
    }

    private static boolean isDispatcher(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) if (k.getName().equals("com.mojang.brigadier.CommandDispatcher")) return true;
        return false;
    }

    private static String stack(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : t.getStackTrace()) sb.append(e).append('\n');
        return sb.toString();
    }
}
