package codes.castled.allium.command;

import codes.castled.allium.command.spec.CoreCommandSpecs;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class BukkitCommandRendererAndMapTest {

    @Test
    void yieldingDisabledFamilyRemovesAlliumLabelsAndPromotesOtherPluginCommand() {
        Plugin alliumPlugin = dummyPlugin("Allium");
        Command alliumGamemode = dummyCommand("gamemode", List.of("gm"));
        Command otherPluginGamemode = dummyCommand("gamemode", List.of("gm"));
        Command otherPluginHeal = dummyCommand("heal", List.of());

        Map<String, Command> knownCommands = new LinkedHashMap<>();
        // Simulate Allium having claimed bare labels during loadPlugins(), pushing OtherPlugin to fallback prefix
        knownCommands.put("gamemode", alliumGamemode);
        knownCommands.put("allium:gamemode", alliumGamemode);
        knownCommands.put("gm", alliumGamemode);
        knownCommands.put("allium:gm", alliumGamemode);
        knownCommands.put("otherplugin:gamemode", otherPluginGamemode);
        knownCommands.put("otherplugin:gm", otherPluginGamemode);

        // Simulate OtherPlugin already owning bare "heal", with Allium only owning "allium:heal"
        Command alliumHeal = dummyCommand("heal", List.of());
        knownCommands.put("heal", otherPluginHeal);
        knownCommands.put("otherplugin:heal", otherPluginHeal);
        knownCommands.put("allium:heal", alliumHeal);

        // 1. Yield gamemode from Allium: otherplugin:gamemode and otherplugin:gm are promoted to bare labels
        CommandMapBridge.yieldFromKnownCommands(
                knownCommands,
                null,
                alliumPlugin,
                alliumGamemode,
                CoreCommandSpecs.GAMEMODE
        );

        assertFalse(knownCommands.containsKey("allium:gamemode"));
        assertFalse(knownCommands.containsKey("allium:gm"));
        assertSame(otherPluginGamemode, knownCommands.get("gamemode"));
        assertSame(otherPluginGamemode, knownCommands.get("gm"));

        // 2. Yield heal from Allium: OtherPlugin's bare "heal" is untouched, "allium:heal" is removed
        CommandMapBridge.yieldFromKnownCommands(
                knownCommands,
                null,
                alliumPlugin,
                alliumHeal,
                CoreCommandSpecs.HEAL
        );

        assertFalse(knownCommands.containsKey("allium:heal"));
        assertSame(otherPluginHeal, knownCommands.get("heal"));
    }

    @Test
    void visibleOnlinePlayersFiltersOutHiddenAndVanishedPlayers() {
        Plugin plugin = dummyPlugin("Allium");
        Player visiblePlayer = dummyPlayer("Alice", false, plugin);
        Player canSeeHiddenPlayer = dummyPlayer("HiddenByCanSee", false, plugin);
        Player metadataVanishedPlayer = dummyPlayer("VanishedStaff", true, plugin);

        Player ordinarySender = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Sender";
                    case "hasPermission" -> false;
                    case "canSee" -> args[0] != canSeeHiddenPlayer;
                    default -> null;
                }
        );

        Player staffSenderWithVanishSee = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Staff";
                    case "hasPermission" -> "allium.vanish.see".equals(args[0]);
                    case "canSee" -> true;
                    default -> null;
                }
        );

        Collection<Player> online = List.of(visiblePlayer, canSeeHiddenPlayer, metadataVanishedPlayer);
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> "getOnlinePlayers".equals(method.getName()) ? online : null
        );

        assertEquals(
                List.of("Alice"),
                BukkitCommandRenderer.collectVisibleOnlinePlayers(server, ordinarySender)
        );
        assertEquals(
                List.of("Alice", "HiddenByCanSee", "VanishedStaff"),
                BukkitCommandRenderer.collectVisibleOnlinePlayers(server, staffSenderWithVanishSee)
        );
    }

    private static Plugin dummyPlugin(String name) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                }
        );
    }

    private static Player dummyPlayer(String name, boolean vanishedMeta, Plugin plugin) {
        List<MetadataValue> meta = vanishedMeta
                ? List.of(new FixedMetadataValue(plugin, true))
                : List.of();
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getMetadata" -> "vanished".equals(args[0]) ? meta : List.of();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                }
        );
    }

    private static Command dummyCommand(String name, List<String> aliases) {
        return new Command(name, "", "/" + name, aliases) {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
                return true;
            }
        };
    }
}
