package codes.castled.allium.economy;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EconomyMessagesAndSelfPayTest {

    @Test
    void paySelfSendsDedicatedMessageAndRefusalsMapToLangMessages() {
        // 1. Payment sent messages omit Operation ID and "to Tax".
        assertFalse(Messages.ECO_PAYMENT_SENT.getDefault().contains("Operation"));
        assertFalse(Messages.ECO_PAYMENT_SENT_TAXED.getDefault().contains("Operation"));
        assertFalse(Messages.ECO_PAYMENT_SENT_TAXED.getDefault().contains("to {account}"));
        assertTrue(Messages.ECO_PAYMENT_SENT_TAXED.getDefault().contains("({tax} tax)"));
        assertFalse(Messages.ECO_CHEQUE_CREATED_TAXED.getDefault().contains("to {account}"));
        assertTrue(Messages.ECO_CHEQUE_CREATED_TAXED.getDefault().contains("({tax} tax)"));

        // 2. Engine rejection codes map to human-friendly lang messages.
        assertEquals(Messages.ECO_REFUSED_INSUFFICIENT_FUNDS, EconomyRuntime.refusalMessage("INSUFFICIENT_FUNDS"));
        assertEquals(Messages.ECO_REFUSED_BALANCE_CAP, EconomyRuntime.refusalMessage("CAP"));
        assertEquals(Messages.ECO_REFUSED_MISSING_ACCOUNT, EconomyRuntime.refusalMessage("MISSING_ACCOUNT"));
        assertEquals(Messages.ECO_REFUSED, EconomyRuntime.refusalMessage("REFUSED"));
        assertEquals(Messages.ECO_REFUSED_SETTINGS_CHANGED, EconomyRuntime.refusalMessage("SETTINGS_CHANGED"));
        assertEquals(Messages.ECO_REFUSED_DUPLICATE_REQUEST, EconomyRuntime.refusalMessage("IDEMPOTENCY_CONFLICT"));
        assertEquals(Messages.ECO_REFUSED_TRANSACTION_BUSY, EconomyRuntime.refusalMessage("WORKFLOW_REFUSED"));
        assertEquals(Messages.ECO_REFUSED_TRANSACTION_BUSY, EconomyRuntime.refusalMessage("NOT_HELD"));
        assertEquals(Messages.ECO_OPERATION_REFUSED, EconomyRuntime.refusalMessage("SOME_FUTURE_CODE"));

        // 3. /pay <self> sends Messages.ECO_PAY_SELF instead of generic payment-refused.
        PluginStart plugin = mock(PluginStart.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        MessageManager messages = mock(MessageManager.class);
        when(plugin.getMessageManager()).thenReturn(messages);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return TaskHandle.NONE; })
                .when(scheduler).runOnServer(any(Runnable.class));
        when(plugin.getScheduler()).thenReturn(scheduler);

        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        ModuleManager modules = mock(ModuleManager.class);
        when(plugin.getModuleManager()).thenReturn(modules);

        UUID selfId = UUID.randomUUID();
        Player self = mock(Player.class);
        when(self.getUniqueId()).thenReturn(selfId);
        when(self.getName()).thenReturn("Alice");
        when(self.isOnline()).thenReturn(true);
        when(self.canSee(self)).thenReturn(true);
        when(self.hasPermission(anyString())).thenReturn(true);
        when(server.getPlayerExact("Alice")).thenReturn(self);
        when(server.getPlayer(selfId)).thenReturn(self);

        PlayerNameService names = mock(PlayerNameService.class);
        when(names.resolve("Alice")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new codes.castled.allium.naming.NameCache.Entry(selfId, "Alice", 0L))));
        when(plugin.getPlayerNames()).thenReturn(names);

        EconomySettings settings = EconomyRuntime.readSettings(plugin);
        EconomyEngine engine = mock(EconomyEngine.class);
        when(engine.settings()).thenReturn(settings);
        EconomyRuntime runtime = new EconomyRuntime(plugin, engine, Runnable::run);

        runtime.dispatch(self, "pay", new String[]{"Alice", "10"});
        verify(messages).send(self, Messages.ECO_PAY_SELF);
    }
}
