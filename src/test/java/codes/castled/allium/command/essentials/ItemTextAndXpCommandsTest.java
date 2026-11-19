package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemTextAndXpCommandsTest {

    private PluginStart plugin;
    private MessageManager messages;
    private Server server;
    private Player player;
    private ItemStack held;
    private ItemMeta meta;
    private final Set<String> grants = new HashSet<>();
    private List<String> lore;
    private String name;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        messages = mock(MessageManager.class);
        server = mock(Server.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        player = mock(Player.class);
        when(player.getName()).thenReturn("Steve");
        when(player.hasPermission(anyString())).thenAnswer(call -> grants.contains(call.<String>getArgument(0)));
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        held = mock(ItemStack.class);
        meta = mock(ItemMeta.class);
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(held.getType()).thenReturn(Material.DIAMOND_SWORD);
        when(held.getAmount()).thenReturn(1);
        when(held.getItemMeta()).thenReturn(meta);
        lore = null;
        name = null;
        when(meta.hasLore()).thenAnswer(call -> lore != null && !lore.isEmpty());
        when(meta.getLore()).thenAnswer(call -> lore == null ? null : new ArrayList<>(lore));
        org.mockito.Mockito.doAnswer(call -> { List<String> next = call.getArgument(0); lore = next == null ? null : new ArrayList<>(next); return null; })
                .when(meta).setLore(any());
        when(meta.hasDisplayName()).thenAnswer(call -> name != null);
        when(meta.getDisplayName()).thenAnswer(call -> name);
        org.mockito.Mockito.doAnswer(call -> { name = call.getArgument(0); return null; }).when(meta).setDisplayName(any());
    }

    private void run(codes.castled.allium.command.SpecCommandHandler handler, org.bukkit.command.CommandSender sender, String... args) {
        handler.execute(new BukkitCommandContext(plugin, handler.spec(), sender, handler.spec().canonicalName(), args));
    }

    @Test
    void renameUsesColoursOnlyWithTheColourNodeAndClearRestoresTheMaterialName() {
        ItemTextCommand rename = new ItemTextCommand(plugin, CoreCommandSpecs.RENAME);
        run(rename, player, "&aCool", "Sword");
        assertEquals("&aCool Sword", name, "without allium.rename.color the codes stay literal");
        verify(messages).send(player, Messages.UTILITY_RENAME_SET, "{item}", "Diamond Sword", "{name}", "&aCool Sword");

        grants.add("allium.rename.color");
        run(rename, player, "&aCool", "Sword");
        assertEquals("§aCool Sword", name);
        verify(messages).send(player, Messages.UTILITY_RENAME_SET, "{item}", "&aCool Sword", "{name}", "Cool Sword");

        run(rename, player, "clear");
        assertNull(name);
        verify(messages).send(player, Messages.UTILITY_RENAME_CLEARED, "{item}", "Diamond Sword");
        run(rename, player, "clear");
        verify(messages).send(player, Messages.UTILITY_RENAME_NOTHING, "{item}", "Diamond Sword");

        run(rename, player, "x".repeat(ItemTextCommand.MAX_NAME_LENGTH + 1));
        verify(messages).send(player, Messages.UTILITY_RENAME_TOO_LONG, "{max}", Integer.toString(ItemTextCommand.MAX_NAME_LENGTH));
        assertNull(name);
    }

    @Test
    void loreLinesAreAddedSetInsertedRemovedAndClearedByNumber() {
        ItemTextCommand loreCommand = new ItemTextCommand(plugin, CoreCommandSpecs.LORE);
        grants.add("allium.lore.color");
        run(loreCommand, player, "add", "&7First");
        run(loreCommand, player, "add", "Third");
        run(loreCommand, player, "insert", "2", "Second");
        assertEquals(List.of("§7First", "Second", "Third"), lore);
        run(loreCommand, player, "set", "3", "Last");
        run(loreCommand, player, "remove", "1");
        assertEquals(List.of("Second", "Last"), lore);

        run(loreCommand, player, "remove", "9");
        verify(messages).send(player, Messages.UTILITY_LORE_NO_LINE, "{line}", "9", "{count}", "2");
        run(loreCommand, player, "set", "x", "text");
        verify(messages).send(player, Messages.UTILITY_LORE_NO_LINE, "{line}", "x", "{count}", "2");
        assertEquals(List.of("Second", "Last"), lore);

        run(loreCommand, player, "clear");
        assertNull(lore);
        verify(messages).send(player, Messages.UTILITY_LORE_CLEARED, "{item}", "Diamond Sword");
        run(loreCommand, player, "clear");
        verify(messages).send(player, Messages.UTILITY_LORE_NONE, "{item}", "Diamond Sword");
    }

    @Test
    void loreCopyKeepsThePlayersLinesAndPasteReplacesTheHeldItemsLore() {
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        LoreClipboard clipboard = new LoreClipboard();
        ItemTextCommand loreCommand = new ItemTextCommand(plugin, CoreCommandSpecs.LORE, clipboard);

        run(loreCommand, player, "paste");
        verify(messages).send(player, Messages.UTILITY_LORE_CLIPBOARD_EMPTY);
        run(loreCommand, player, "copy");
        verify(messages).send(player, Messages.UTILITY_LORE_NONE, "{item}", "Diamond Sword");

        lore = new ArrayList<>(List.of("§7Forged in fire", "§8Second"));
        run(loreCommand, player, "copy");
        verify(messages).send(player, Messages.UTILITY_LORE_COPIED, "{count}", "2", "{item}", "Diamond Sword");

        lore = new ArrayList<>(List.of("old"));
        grants.add("allium.lore.color");
        run(loreCommand, player, "paste");
        assertEquals(List.of("§7Forged in fire", "§8Second"), lore, "paste replaces the lore");
        verify(messages).send(player, Messages.UTILITY_LORE_PASTED, "{count}", "2", "{item}", "Diamond Sword");

        grants.remove("allium.lore.color");
        lore = null;
        run(loreCommand, player, "paste");
        assertEquals(List.of("Forged in fire", "Second"), lore, "without allium.lore.color the formatting is dropped");

        PlayerQuitEventHolder.quit(clipboard, player);
        lore = null;
        run(loreCommand, player, "paste");
        assertNull(lore, "leaving the server empties the clipboard");
    }

    private static final class PlayerQuitEventHolder {
        static void quit(LoreClipboard clipboard, Player player) {
            org.bukkit.event.player.PlayerQuitEvent event = mock(org.bukkit.event.player.PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);
            clipboard.onQuit(event);
        }
    }

    @Test
    void anEmptyHandIsRefusedBeforeAnyEdit() {
        when(held.getType()).thenReturn(Material.AIR);
        run(new ItemTextCommand(plugin, CoreCommandSpecs.RENAME), player, "Name");
        verify(messages).send(player, Messages.UTILITY_HOLD_ITEM);
        verify(meta, never()).setDisplayName(any());
    }

    @Test
    void xpChangesUseLevelsOrPointsAndNeedTheirNode() {
        XpCommand xp = new XpCommand(plugin);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        Set<String> consoleGrants = new HashSet<>(Set.of("allium.xp.give", "allium.xp.others"));
        when(console.hasPermission(anyString())).thenAnswer(call -> consoleGrants.contains(call.<String>getArgument(0)));
        when(server.getPlayer("Steve")).thenReturn(player);
        when(player.getLevel()).thenReturn(10);
        when(player.getExp()).thenReturn(0.5f);

        run(xp, console, "add", "Steve", "5", "levels");
        verify(player).setLevel(15);
        ArgumentCaptor<Float> bar = ArgumentCaptor.forClass(Float.class);
        verify(player).setExp(bar.capture());
        assertEquals(0.5f, bar.getValue(), 0.05f);
        verify(messages).send(eq(player), eq(Messages.UTILITY_XP_NOTIFY), any(String[].class));

        run(xp, console, "set", "Steve", "0");
        verify(messages).send(console, Messages.NO_PERMISSION, "{cmd}", "xp set");
        verify(player, never()).setLevel(0);

        run(xp, console, "give", "Steve", "lots");
        verify(messages).send(console, Messages.UTILITY_XP_INVALID, "{input}", "lots", "{max}", Integer.toString(codes.castled.allium.xp.Experience.MAX_LEVEL));

        run(xp, console, "Steve");
        verify(messages).send(console, Messages.UTILITY_XP_OTHER, "{player}", "Steve", "{points}",
                Long.toString(codes.castled.allium.xp.Experience.fromBar(10, 0.5f).total()), "{level}", "10", "{next}",
                Integer.toString(codes.castled.allium.xp.Experience.fromBar(10, 0.5f).toNextLevel()));

        consoleGrants.add("allium.xp.set");
        run(xp, console, "reset", "Steve");
        verify(player, atLeastOnce()).setLevel(0);
        verify(player).setTotalExperience(0);
        verify(player, never()).giveExp(anyInt());
    }
}
