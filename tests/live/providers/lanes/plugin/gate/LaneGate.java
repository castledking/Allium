package gate;

import codes.castled.allium.PluginStart;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionStatus;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Headless native-provider/YAML contract checks. No simulated player or inventory UI. */
public final class LaneGate extends JavaPlugin implements Listener {
    private static final String BEHAVIOR = "allium:lane_gate";
    private static final List<String> PROVIDERS = List.of("nexo", "craftengine", "oraxen");
    private final Map<String, Integer> events = new HashMap<>();
    private final Map<String, Integer> baseline = new HashMap<>();
    private ItemService items;
    private List<String> expected;
    private boolean failed;

    @Override public void onEnable() {
        items = PluginStart.getInstance().getItemService();
        items.registerPrivilegedBehavior(BEHAVIOR);
        expected = getConfig().getStringList("expected");
        for (String provider : expected) {
            String pluginName = switch (provider) {
                case "nexo" -> "Nexo";
                case "craftengine" -> "CraftEngine";
                case "oraxen" -> "Oraxen";
                default -> throw new IllegalArgumentException(provider);
            };
            Plugin plugin = getServer().getPluginManager().getPlugin(pluginName);
            if (plugin == null) throw new IllegalStateException(pluginName + " not installed");
            String eventName = switch (provider) {
                case "nexo" -> "com.nexomc.nexo.api.events.NexoPreItemsLoadEvent";
                case "craftengine" -> "net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent";
                default -> "io.th0rgal.oraxen.api.events.OraxenItemsLoadedEvent";
            };
            try {
                Class<? extends Event> type = Class.forName(eventName, true, plugin.getClass().getClassLoader()).asSubclass(Event.class);
                getServer().getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
                    try {
                        if (getConfig().getBoolean("test.force-event-failure", false)) {
                            throw new IllegalStateException("injected event assertion failure");
                        }
                        if (!provider.equals("oraxen")) {
                            require(items.create(ref(provider)).status() == ResolutionStatus.PROVIDER_LOADING,
                                    provider + " native event exposes PROVIDER_LOADING");
                        }
                        events.merge(provider, 1, Integer::sum);
                    } catch (Exception | LinkageError failure) {
                        fail(failure);
                    }
                }, this);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
        getLogger().info("S05-LANE-BOOT " + getServer().getVersion() + " expected=" + expected);
        await(false);
    }

    private ItemRef ref(String provider) {
        String id = provider.equals("craftengine") ? "sf_ores:silver_ingot" : "gate_wand";
        return new ItemRef(ProviderKey.of(provider, id), BEHAVIOR, 1, Map.of("charges", "2"));
    }

    private boolean ready() {
        for (String provider : PROVIDERS) {
            ProviderStatus status = items.getProvider(provider).orElseThrow().status();
            if (status != (expected.contains(provider) ? ProviderStatus.READY : ProviderStatus.ABSENT)) return false;
        }
        return true;
    }

    private void await(boolean reload) {
        new BukkitRunnable() {
            int attempts;
            @Override public void run() {
                if (failed) { cancel(); return; }
                try {
                    boolean observed = !reload || expected.stream().allMatch(p -> events.getOrDefault(p, 0) > baseline.getOrDefault(p, 0));
                    if (observed && ready()) {
                        if (reload) verify();
                        getLogger().info(reload ? "S05-LANE-RELOAD-PASS" : "S05-LANE-READY");
                        cancel();
                    } else if (++attempts >= 120) {
                        throw new IllegalStateException("readiness/event timeout; reload=" + reload);
                    }
                } catch (Exception | LinkageError failure) {
                    fail(failure);
                    cancel();
                }
            }
        }.runTaskTimer(this, 1, 20);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
        getLogger().info("S05-LANE-PASS " + message);
    }

    private void fail(Throwable failure) {
        failed = true;
        getLogger().severe("S05-LANE-FAIL " + failure);
    }

    private void probe() throws Exception {
        require(ready(), "expected providers ready; others absent");
        YamlConfiguration checkpoint = new YamlConfiguration();
        for (String provider : expected) {
            ItemStack stack = items.create(ref(provider)).itemStack().orElseThrow();
            checkpoint.set(provider + ".item", stack);
            checkpoint.set(provider + ".signature", tag(stack, ItemService.KEY_SIGNATURE));
            checkpoint.set(provider + ".uuid", tag(stack, ItemService.KEY_INSTANCE_UUID));
            baseline.put(provider, events.getOrDefault(provider, 0));
            require(items.identify(stack).orElseThrow().appearance().equals(ref(provider).appearance()), provider + " native identity");
            require(items.identifyBehavior(stack).orElseThrow().equals(BEHAVIOR), provider + " signature");
        }
        checkpoint.save(new File(getDataFolder(), "checkpoint.yml"));
        verify();
        getLogger().info("S05-LANE-PROBE-PASS");
    }

    private String tag(ItemStack stack, org.bukkit.NamespacedKey key) {
        return stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private void verify() {
        YamlConfiguration checkpoint = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "checkpoint.yml"));
        for (String provider : expected) {
            ItemStack stack = Objects.requireNonNull(checkpoint.getItemStack(provider + ".item"));
            require(Objects.equals(tag(stack, ItemService.KEY_SIGNATURE), checkpoint.getString(provider + ".signature")), provider + " serialized signature unchanged");
            require(Objects.equals(tag(stack, ItemService.KEY_INSTANCE_UUID), checkpoint.getString(provider + ".uuid")), provider + " serialized UUID unchanged");
            require(items.getState(stack, "charges").orElseThrow().equals("2"), provider + " spent state unchanged");
            require(items.identifyBehavior(stack).orElseThrow().equals(BEHAVIOR), provider + " serialized signature verifies");
            require(items.identify(stack).orElseThrow().appearance().equals(ref(provider).appearance()), provider + " serialized native identity");
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) return false;
        if (failed) { fail(new IllegalStateException("preceding assertion failed; refusing subsequent PASS")); return true; }
        try {
            switch (args[0]) {
                case "probe" -> probe();
                case "awaitreload" -> await(true);
                case "restore" -> { require(ready(), "providers ready after restart"); verify(); getLogger().info("S05-LANE-RESTORE-PASS"); }
                default -> throw new IllegalArgumentException(args[0]);
            }
        } catch (Exception | LinkageError failure) {
            fail(failure);
        }
        return true;
    }
}
