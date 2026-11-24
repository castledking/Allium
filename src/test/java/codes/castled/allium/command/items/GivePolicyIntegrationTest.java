package codes.castled.allium.command.items;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.delivery.DeliveryOutcome;
import codes.castled.allium.item.delivery.ItemDeliveryService;
import codes.castled.allium.item.delivery.OverflowPolicy;
import codes.castled.allium.item.library.ItemLibrary;
import codes.castled.allium.item.spec.GiveItemPolicy;
import codes.castled.allium.item.spec.ItemReference;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.module.ItemsModule;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GivePolicyIntegrationTest {

    private PluginStart plugin;
    private MessageManager messages;
    private ItemLibrary library;
    private ItemDeliveryService delivery;
    private Player giver;
    private Player target;
    private Set<String> giverPerms;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        messages = mock(MessageManager.class);
        library = mock(ItemLibrary.class);
        delivery = mock(ItemDeliveryService.class);
        giver = mock(Player.class);
        target = mock(Player.class);
        giverPerms = new HashSet<>(Set.of("allium.give"));

        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getModuleManager()).thenReturn(mock(ModuleManager.class));
        when(giver.hasPermission(anyString())).thenAnswer(call -> giverPerms.contains(call.<String>getArgument(0)));
        when(target.getName()).thenReturn("Steve");
        when(library.canonicalKey(any(ItemReference.class))).thenAnswer(call -> {
            ItemReference ref = call.getArgument(0);
            // Simulate legacy alias "br" expanding to minecraft:bedrock
            return ref.key().id().equals("br") ? ProviderKey.minecraft("bedrock") : ref.key();
        });
        when(library.buildForGive(any(), anyBoolean(), anyBoolean()))
                .thenReturn(new ItemLibrary.Prepared(List.of(), List.of()));
        when(delivery.deliver(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new DeliveryOutcome(DeliveryOutcome.Status.DELIVERED, 1, 1, 0, 0, List.of())));
    }

    @Test
    void deniedItemAndAliasExpansionBlockEntireBatchBeforeBuildAndDelivery() {
        GiveItemPolicy policy = new GiveItemPolicy(
                GiveItemPolicy.Mode.DENYLIST,
                List.of("minecraft:bedrock", "allium:handcuffs"),
                List.of(),
                false
        );
        GiveCommand cmd = new GiveCommand(plugin, () -> new ItemSpecParser(false), library, delivery,
                () -> OverflowPolicy.DROP, () -> policy);

        // Comma batch containing a denied item refuses the entire batch before building anything
        cmd.give(giver, List.of(target), "diamond,bedrock 1");
        verify(library, never()).buildForGive(any(), anyBoolean(), anyBoolean());
        verify(delivery, never()).deliver(any(), any(), any(), anyBoolean(), any());
        verify(messages).sendByKey(eq(giver), eq("item.give.policy-denied"),
                eq("{item}"), eq("minecraft:bedrock"), eq("{reason}"), contains("minecraft:bedrock"));

        // Alias "br" expands to minecraft:bedrock and is blocked too
        clearInvocations(messages);
        cmd.give(giver, List.of(target), "br 1");
        verify(library, never()).buildForGive(any(), anyBoolean(), anyBoolean());
        verify(messages).sendByKey(eq(giver), eq("item.give.policy-denied"),
                eq("{item}"), eq("minecraft:bedrock"), eq("{reason}"), contains("minecraft:bedrock"));

        // Explicit bypass permission permits giving the denied item
        giverPerms.add(GiveItemPolicy.PERMISSION_BYPASS);
        cmd.give(giver, List.of(target), "bedrock 1");
        verify(library).buildForGive(any(), anyBoolean(), anyBoolean());
        verify(delivery).deliver(eq(giver), eq(target), any(), eq(false), eq(OverflowPolicy.DROP));

        // Console also bypasses player-facing policy
        clearInvocations(library, delivery);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        cmd.give(console, List.of(target), "allium:handcuffs 1");
        verify(library).buildForGive(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void permissionBasedSpawnAndConfigReloadValidationWorkCleanly() {
        YamlConfiguration valid = new YamlConfiguration();
        valid.set("items.give.policy.mode", "allowlist");
        valid.set("items.give.policy.allow", List.of("minecraft:*"));
        valid.set("items.give.policy.deny", List.of("bedrock"));
        valid.set("items.give.policy.permission-based-spawn", true);

        GiveItemPolicy parsed = ItemsModule.readItemPolicy(valid);
        assertEquals(GiveItemPolicy.Mode.ALLOWLIST, parsed.mode());
        assertTrue(parsed.permissionBasedSpawn());

        GiveCommand cmd = new GiveCommand(plugin, () -> new ItemSpecParser(false), library, delivery,
                () -> OverflowPolicy.DROP, () -> parsed);

        // Missing per-item permission blocks diamond
        cmd.give(giver, List.of(target), "diamond 1");
        verify(library, never()).buildForGive(any(), anyBoolean(), anyBoolean());
        verify(messages).sendByKey(eq(giver), eq("item.give.policy-denied"),
                eq("{item}"), eq("minecraft:diamond"), eq("{reason}"), contains("allium.give.item.minecraft.diamond"));

        // Granting provider wildcard permits diamond
        giverPerms.add("allium.give.item.minecraft.*");
        cmd.give(giver, List.of(target), "diamond 1");
        verify(library).buildForGive(any(), anyBoolean(), anyBoolean());

        // Malformed policy section/mode fails fast during reload validation
        YamlConfiguration badMode = new YamlConfiguration();
        badMode.set("items.give.policy.mode", "invalid");
        assertThrows(IllegalArgumentException.class, () -> ItemsModule.readItemPolicy(badMode));

        YamlConfiguration badShape = new YamlConfiguration();
        badShape.set("items.give.policy", "not-a-section");
        assertThrows(IllegalArgumentException.class, () -> ItemsModule.readItemPolicy(badShape));
    }
}
