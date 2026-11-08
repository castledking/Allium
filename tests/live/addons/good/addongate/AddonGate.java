package addongate;

import codes.castled.allium.api.AddonCommands;
import codes.castled.allium.api.AddonRefusedException;
import codes.castled.allium.api.AlliumAddon;
import codes.castled.allium.api.AlliumApi;
import codes.castled.allium.api.ItemDefinition;
import codes.castled.allium.api.ItemIdentity;
import codes.castled.allium.api.ItemResult;
import codes.castled.allium.command.spec.CommandArgSpec;
import codes.castled.allium.command.spec.CommandMessageKeys;
import codes.castled.allium.command.spec.CommandNodeSpec;
import codes.castled.allium.command.spec.CommandSpec;
import codes.castled.allium.command.spec.SuggestionSpec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** S09 test addon: compiled against Allium-API-<version>.jar only. */
public final class AddonGate extends JavaPlugin {

    private AlliumAddon allium;
    private final AtomicInteger serverRuns = new AtomicInteger();
    private volatile boolean ranOnServerThread = true;
    private volatile String asyncThread;

    @Override
    public void onEnable() {
        try {
            allium = AlliumApi.get().connect(this);
        } catch (AddonRefusedException | IllegalStateException e) {
            getLogger().severe("ADDONG-REFUSED " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        allium.items().define(new ItemDefinition("gate_wand", Material.STICK, "<gradient:#55ffff:#5555ff>Gate Wand</gradient>",
                7_700_001, Map.of("uses", "3"), true));
        CommandSpec spec = new CommandSpec("addongate", "addongate", "addongate", List.of(), "S09 test addon",
                "/addongate <status|mint|identify|plugins> [player]", "/addongate <status|mint|identify|plugins> [player]",
                "addongate.use", null, List.of(),
                List.of(new CommandNodeSpec("status", List.of(), null, "/addongate status", List.of(), List.of()),
                        new CommandNodeSpec("mint", List.of(), null, "/addongate mint <player>",
                                List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null))), List.of()),
                        new CommandNodeSpec("identify", List.of(), null, "/addongate identify <player>",
                                List.of(CommandArgSpec.required("player", null, SuggestionSpec.onlinePlayers(null))), List.of()),
                        new CommandNodeSpec("plugins", List.of(), null, "/addongate plugins", List.of(), List.of())),
                CommandMessageKeys.ofOutcomes(Map.of()));
        allium.commands().register(spec, this::handle);
        // Five runs on the server thread, then the step stops itself; and one delayed async task.
        allium.scheduler().repeatOnServer(() -> {
            ranOnServerThread &= Bukkit.isPrimaryThread() && allium.scheduler().isServerThread();
            return serverRuns.incrementAndGet() < 5;
        }, 50L, 50L);
        allium.scheduler().runAsyncLater(() -> asyncThread = Thread.currentThread().getName() + (Bukkit.isPrimaryThread() ? " (server)" : ""), 100L);
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("wire-ids.json"), WireIds.json());
        } catch (Throwable e) {
            getLogger().info("ADDONG wire catalog unavailable on this version (protocol library client is used): " + e);
        }
        getLogger().info("ADDONG-READY api=" + AlliumApi.get().version());
    }

    private void handle(AddonCommands.Context context) {
        String[] args = context.args();
        String action = args.length == 0 ? "status" : args[0];
        Map<String, Object> out = new LinkedHashMap<>();
        switch (action) {
            case "status" -> {
                out.put("serverRuns", serverRuns.get());
                out.put("serverThread", ranOnServerThread);
                out.put("asyncThread", asyncThread);
                out.put("api", AlliumApi.get().version().toString());
                allium.text().send(context.sender(), "&aAddonGate &7runs=&f{runs} &7async=&f{async}",
                        Map.of("runs", String.valueOf(serverRuns.get()), "async", String.valueOf(asyncThread)));
            }
            case "mint" -> {
                Player target = Bukkit.getPlayerExact(args[1]);
                ItemResult result = allium.items().create("allium:gate_wand", 1);
                out.put("status", result.status().name());
                if (target != null && result.created()) {
                    target.getInventory().addItem(result.stack().get());
                    out.put("given", target.getName());
                }
            }
            case "identify" -> {
                Player target = Bukkit.getPlayerExact(args[1]);
                java.util.List<Map<String, Object>> found = new java.util.ArrayList<>();
                for (ItemStack stack : target.getInventory().getContents()) {
                    if (stack == null || stack.getType() != Material.STICK) continue;
                    ItemIdentity identity = allium.items().identify(stack).orElse(null);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("appearance", identity == null ? null : identity.appearance());
                    row.put("behavior", identity == null ? null : identity.behavior().orElse(null));
                    row.put("state", identity == null ? null : identity.state());
                    row.put("name", stack.getItemMeta() != null && stack.getItemMeta().hasDisplayName() ? stack.getItemMeta().getDisplayName() : null);
                    row.put("modelData", stack.getItemMeta() != null && stack.getItemMeta().hasCustomModelData() ? stack.getItemMeta().getCustomModelData() : null);
                    found.add(row);
                }
                out.put("sticks", found);
                ItemIdentity plain = allium.items().identify(new ItemStack(Material.STICK)).orElse(null);
                out.put("plainStickBehavior", plain == null ? "none" : plain.behavior().orElse(null));
                out.put("missing", allium.items().create("allium:not_defined_anywhere", 1).status().name());
            }
            case "plugins" -> {
                for (Plugin p : Bukkit.getPluginManager().getPlugins()) out.put(p.getName(), p.isEnabled());
            }
            default -> out.put("error", "unknown action " + action);
        }
        getLogger().info("ADDONG-" + action.toUpperCase() + " " + Json.of(out));
    }

    @Override
    public void onDisable() {
        getLogger().info("ADDONG-DISABLED");
    }

    /** Minimal JSON for the harness (no Gson dependency in the addon). */
    static final class Json {
        static String of(Object value) {
            if (value == null) return "null";
            if (value instanceof Boolean || value instanceof Number) return value.toString();
            if (value instanceof Map<?, ?> map) {
                StringBuilder b = new StringBuilder("{");
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (b.length() > 1) b.append(',');
                    b.append(of(String.valueOf(e.getKey()))).append(':').append(of(e.getValue()));
                }
                return b.append('}').toString();
            }
            if (value instanceof java.util.Collection<?> list) {
                StringBuilder b = new StringBuilder("[");
                for (Object o : list) {
                    if (b.length() > 1) b.append(',');
                    b.append(of(o));
                }
                return b.append(']').toString();
            }
            String s = value.toString();
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                else b.append(c);
            }
            return b.append('"').toString();
        }
    }

    /** Packet IDs for the raw 26.3 client, from the running server's protocol catalog. */
    static final class WireIds {
        static String json() throws Exception {
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
                    Map<String, Object> ids = new LinkedHashMap<>();
                    Object callback = java.lang.reflect.Proxy.newProxyInstance(visitor.getClassLoader(), new Class[]{visitor},
                            (proxy, method, a) -> {
                                if (method.getName().equals("accept")) ids.put(packetType.getMethod("id").invoke(a[0]).toString(), a[1]);
                                return null;
                            });
                    detailsType.getMethod("listPackets", visitor).invoke(details, callback);
                    result.put(phase + "_" + flow, ids);
                }
            }
            return Json.of(Map.of("wirePackets", result));
        }
    }
}
