package codes.castled.allium.legacy;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyPowerToolsTest {
    @TempDir
    Path folder;

    private static final String VOUCHER = "schema: 1\n"
            + "id: allium:voucher_fly\n"
            + "name: Fly voucher\n"
            + "behavior: allium:powertool\n"
            + "item:\n  material: feather\n  name: '&bFly voucher'\n"
            + "uses:\n  max: 1\n  consume-on-empty: true\n  bypassable: false\n"
            + "bindings:\n  right:\n    actions:\n"
            + "      - type: console\n        value: 'allium:fly {player} add 10m'\n"
            + "      - type: message\n        value: '&aEnjoy'\n";

    @Test
    void theModernSecurityRulesCarryOver() {
        assertEquals("say hi", LegacyPowerTools.cleanCommand("//say hi"));
        assertTrue(LegacyPowerTools.consoleAllowed("say hi", Collections.emptyList()), "an empty list allows all");
        assertTrue(LegacyPowerTools.consoleAllowed("allium:fly Bob", Collections.singletonList("allium:")));
        assertTrue(LegacyPowerTools.consoleAllowed("/give Bob 1", Arrays.asList("say", "/give")));
        assertFalse(LegacyPowerTools.consoleAllowed("op Bob", Arrays.asList("say", "allium:")));
        assertTrue(LegacyPowerTools.consoleAllowed("op Bob", Collections.singletonList("*")));
        assertNull(LegacyPowerTools.firstUnknownPlaceholder("give {player} {x}"));
        assertEquals("secret", LegacyPowerTools.firstUnknownPlaceholder("say {player} {secret}"));
        assertEquals(LegacyPowerTools.Trigger.SHIFT_RIGHT, LegacyPowerTools.Trigger.parse("SR"));
        assertEquals(LegacyPowerTools.Trigger.ON_HIT, LegacyPowerTools.Trigger.parse("attack"));
    }

    @Test
    void theBundledToolsNeedTheModernPlatform() throws Exception {
        for (String name : Arrays.asList("handcuffs", "claim_handcuffs", "lazy_axe", "mob_disarmer", "phantom_obliterator")) {
            File file = Paths.get("src/main/resources/tools", name + ".yml").toFile();
            LegacyPowerTools.Tool tool = LegacyPowerTools.parse(name, YamlConfiguration.loadConfiguration(file));
            if (name.endsWith("handcuffs")) assertNotNull(tool.unsupported, name + " is listed as needing 1.20+");
            else assertNull(tool, name + " is another behavior, not a powertool");
        }
        LegacyPowerTools.Tool voucher = LegacyPowerTools.parse("voucher_fly", yaml(VOUCHER));
        assertNull(voucher.unsupported);
        assertEquals(1, voucher.uses);
        assertTrue(voucher.consumeOnEmpty);
        assertFalse(voucher.bypassable, "even an operator spends a voucher");
        assertEquals("allium.tool.voucher_fly.bypass", voucher.bypassPermission);
        LegacyPowerTools.Binding right = voucher.bindings.get(LegacyPowerTools.Trigger.RIGHT);
        assertTrue(right.cancel, "right clicks cancel by default, as on the modern platform");
        assertTrue(right.steps.get(0).console);
    }

    @Test
    void aVoucherRunsItsConsoleCommandOnceAndIsUsedUp() throws Exception {
        Path tools = Files.createDirectories(folder.resolve("tools"));
        Files.write(tools.resolve("voucher_fly.yml"), VOUCHER.getBytes(StandardCharsets.UTF_8));
        Files.write(tools.resolve("selector.yml"), ("behavior: allium:powertool\nitem:\n  material: stick\n"
                + "bindings:\n  left:\n    actions:\n      - type: console\n        value: 'kill @e'\n").getBytes(StandardCharsets.UTF_8));

        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(server.getConsoleSender()).thenReturn(console);
        when(server.dispatchCommand(any(), anyString())).thenReturn(true);
        LegacyConfigManager config = mock(LegacyConfigManager.class);
        when(config.raw()).thenReturn(new YamlConfiguration());
        LegacyMessageManager messages = mock(LegacyMessageManager.class);
        LegacyPowerTools powerTools = new LegacyPowerTools(plugin, config, messages);
        powerTools.load();
        assertEquals(Arrays.asList("selector", "voucher_fly"), Arrays.asList(powerTools.loadedNames().toArray()));

        Player player = player();
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack voucher = stack(Material.FEATHER, "§7Uses left: §f1", "§8powertool:voucher_fly");
        assertEquals("voucher_fly", LegacyPowerTools.toolOf(voucher));

        assertTrue(powerTools.fire(player, voucher, false, LegacyPowerTools.Trigger.RIGHT, null, null, null));
        verify(server).dispatchCommand(console, "allium:fly Bob add 10m");
        verify(player).sendMessage("§aEnjoy");
        verify(inventory).setItemInMainHand(null);

        // The off hand does not fire a main-hand binding, and a selector is refused.
        assertFalse(powerTools.fire(player, voucher, true, LegacyPowerTools.Trigger.RIGHT, null, null, null));
        ItemStack stick = stack(Material.STICK, "§8powertool:selector");
        assertFalse(powerTools.fire(player, stick, false, LegacyPowerTools.Trigger.LEFT, null, null, null));
        verify(server, never()).dispatchCommand(console, "kill @e");

        // Without allium.powertool.use the tool refuses.
        when(player.hasPermission("allium.powertool.use")).thenReturn(false);
        assertFalse(powerTools.fire(player, voucher, false, LegacyPowerTools.Trigger.RIGHT, null, null, null));
        verify(messages).send(eq(player), eq("error.no-permission"), anyString());
    }

    @Test
    void aCooldownAndUsesHoldForPlayersWithoutTheBypass() throws Exception {
        Path tools = Files.createDirectories(folder.resolve("tools"));
        Files.write(tools.resolve("wand.yml"), ("behavior: allium:powertool\nitem:\n  material: blaze_rod\nuses:\n  max: 3\n"
                + "bindings:\n  right:\n    cooldown: 5s\n    actions:\n      - type: message\n        value: zoom\n").getBytes(StandardCharsets.UTF_8));
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        LegacyConfigManager config = mock(LegacyConfigManager.class);
        when(config.raw()).thenReturn(new YamlConfiguration());
        LegacyMessageManager messages = mock(LegacyMessageManager.class);
        LegacyPowerTools powerTools = new LegacyPowerTools(plugin, config, messages);
        powerTools.load();
        Player player = player();
        when(player.hasPermission("allium.powertool.bypass")).thenReturn(false);
        when(player.hasPermission("allium.tool.wand.bypass")).thenReturn(false);
        ItemStack wand = stack(Material.BLAZE_ROD, "§7Uses left: §f3", "§8powertool:wand");
        assertTrue(powerTools.fire(player, wand, false, LegacyPowerTools.Trigger.RIGHT, null, null, null));
        verify(wand.getItemMeta()).setLore(Arrays.asList("§7Uses left: §f2", "§8powertool:wand"));
        Thread.sleep(150);
        assertFalse(powerTools.fire(player, wand, false, LegacyPowerTools.Trigger.RIGHT, null, null, null), "on cooldown");
        verify(messages).send(eq(player), eq("tool.powertool.cooldown"), anyString(), eq("{time}"), anyString());

        ItemStack empty = stack(Material.BLAZE_ROD, "§7Uses left: §f0", "§8powertool:wand");
        Thread.sleep(150);
        assertFalse(powerTools.fire(player, empty, false, LegacyPowerTools.Trigger.RIGHT, null, null, null), "no uses left");
        verify(messages).send(eq(player), eq("tool.powertool.no-uses"), anyString());
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    private static Player player() {
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getName()).thenReturn("Bob");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 1, 64, 2));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.hasPermission("allium.powertool.bypass.cooldown")).thenReturn(false);
        return player;
    }

    private static ItemStack stack(Material material, String... lore) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        List<String> lines = Arrays.asList(lore);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(1);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.hasLore()).thenReturn(true);
        when(meta.getLore()).thenReturn(lines);
        return stack;
    }
}
