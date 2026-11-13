package codes.castled.allium.command.mail;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.mail.MailService;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailCommandTest {
    private final PluginStart plugin = mock(PluginStart.class);
    private final MessageManager messages = mock(MessageManager.class);
    private final MailService mail = mock(MailService.class);
    private final MailCommand command = new MailCommand(plugin, mail);

    MailCommandTest() {
        when(plugin.getMessageManager()).thenReturn(messages);
    }

    @Test
    void invalidAmountsNeverBecomeNotesOrFullStackSends() {
        Player player = sender();
        String[] invalid = {"0", "-1", "-64", "+0", "65", "9999", "10000", "2147483647", "2147483648",
                "99999999999999999999999999999999", "1.5", "1e9", ".5", "-0.5"};
        for (String amount : invalid) {
            execute(player, "gift", "Alice", amount, "a note");
        }
        verifyNoInteractions(mail);
        verify(messages, times(invalid.length)).sendByKey(eq(player), anyString(), eq("{usage}"),
                eq("/mail gift <player> [amount] [note]"));
    }

    @Test
    void giftAmountsAndOptionalMultiwordNotesArePassedWithoutLoss() {
        Player player = sender();
        execute(player, "gift", "Alice", "8", "a", "small", "gift");
        verify(mail).sendGift(player, "Alice", 8, "a small gift");
        execute(player, "GIFT", "Alice", "64");
        verify(mail).sendGift(player, "Alice", 64, null);
        execute(player, "gift", "Alice");
        verify(mail).sendGift(player, "Alice", 0, null);
        execute(player, "gift", "Alice", "happy", "birthday");
        verify(mail).sendGift(player, "Alice", 0, "happy birthday");
        verifyNoMoreInteractions(mail);
    }

    @Test
    void baseAndActionPermissionsAreEnforcedBeforeDispatch() {
        Player player = sender();
        when(player.hasPermission("allium.mail")).thenReturn(false);
        execute(player, "send", "Alice", "hello");
        execute(player, "gift", "Alice", "8");
        execute(player, "claim", "all");
        execute(player, "delete", "abc12345");
        execute(player, "clear");
        verifyNoInteractions(mail);
        when(player.hasPermission("allium.mail")).thenReturn(true);
        when(player.hasPermission("allium.mail.send")).thenReturn(false);
        when(player.hasPermission("allium.mail.gift")).thenReturn(false);
        execute(player, "send", "Alice", "hello");
        execute(player, "gift", "Alice", "8");
        verifyNoInteractions(mail);
        execute(player); // Bare mail is read and needs only the base permission.
        execute(player, "read");
        execute(player, "claim", "all");
        execute(player, "delete", "abc12345");
        execute(player, "clear");
        verify(mail, times(2)).read(player);
        verify(mail).claim(player, "all");
        verify(mail).delete(player, "abc12345");
        verify(mail).clear(player);
        verifyNoMoreInteractions(mail);
    }

    @Test
    void consoleCanSendTextButCannotMutateOrReadAPlayerMailbox() {
        CommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        execute(console, "send", "Alice", "hello", "offline", "friend");
        verify(mail).sendText(console, "Alice", "hello offline friend");
        for (String[] args : new String[][]{{}, {"read"}, {"gift", "Alice"}, {"claim", "all"}, {"delete", "abc12345"}, {"clear"}}) {
            execute(console, args);
        }
        verify(messages, times(6)).send(console, Messages.PLAYER_ONLY);
        verifyNoMoreInteractions(mail);
    }

    @Test
    void missingOrExtraArgumentsFailBeforeCallingTheService() {
        Player player = sender();
        for (String[] args : new String[][]{{"gift"}, {"gift", ""}, {"send", "Alice"}, {"send", "Alice", ""},
                {"send", "", "hello"}, {"claim"}, {"claim", ""}, {"claim", "all", "extra"},
                {"delete"}, {"delete", ""}, {"delete", "id", "extra"}, {"read", "extra"}, {"clear", "extra"}, {"unknown"}}) {
            execute(player, args);
        }
        verifyNoInteractions(mail);
        verify(messages, times(14)).sendByKey(eq(player), anyString(), eq("{usage}"), anyString());
    }

    private Player sender() {
        Player player = mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack held = mock(ItemStack.class);
        when(held.getAmount()).thenReturn(64);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getHeldItemSlot()).thenReturn(0);
        when(inventory.getItem(0)).thenReturn(held);
        return player;
    }

    private void execute(CommandSender sender, String... args) {
        command.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.MAIL, sender, "mail", args));
    }
}
