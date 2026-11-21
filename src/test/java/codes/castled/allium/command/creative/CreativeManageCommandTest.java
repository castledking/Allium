package codes.castled.allium.command.creative;

import codes.castled.allium.PluginStart;
import codes.castled.allium.creative.manage.CreativeAccess;
import codes.castled.allium.creative.manage.CreativeFlag;
import codes.castled.allium.creative.manage.CreativeManageMenus;
import codes.castled.allium.creative.manage.FlagValue;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreativeManageCommandTest {

    private PluginStart plugin;
    private Server server;
    private MessageManager messages;
    private CreativeManageMenus menus;
    private CreativeManageCommand command;
    private ConsoleCommandSender console;
    private final List<String> usages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        server = mock(Server.class);
        messages = mock(MessageManager.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(scheduler.runOnServer(any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return TaskHandle.NONE;
        });
        menus = mock(CreativeManageMenus.class);
        command = new CreativeManageCommand(plugin, new CreativeAccess(plugin), menus);
        console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        when(console.getName()).thenReturn("CONSOLE");
    }

    private void run(String... args) {
        command.run(console, args, "/creativemanager", usages::add);
    }

    @Test
    void malformedFormsShowTheNarrowestUsageCard() {
        run("bogus");
        run("player");
        run("player", "Steve", "add");
        run("group", "builder", "list", "extra");
        run("player", "Steve", "fly");
        run("gui", "extra");
        assertEquals(List.of("/creativemanager", "/creativemanager player", "/creativemanager player <player> add <flag>",
                "/creativemanager group <group> list", "/creativemanager player", "/creativemanager gui"), usages);
    }

    @Test
    void consoleCannotOpenMenusAndUnknownFlagsAreNamed() {
        run("gui");
        run("active");
        run("player", "Steve", "edit");
        verify(messages, org.mockito.Mockito.times(3)).send(console, Messages.PLAYER_ONLY);
        run("player", "Steve", "add", "nope");
        verify(messages).send(console, Messages.CREATIVE_MANAGE_UNKNOWN_FLAG, "{flag}", "nope", "{flags}", String.join(", ", CreativeFlag.keys()));
        verifyNoInteractions(menus);
    }

    @Test
    void withoutAPermissionsPluginChangesAreRefusedAndGroupsAreUnavailable() {
        Player steve = mock(Player.class);
        when(steve.getUniqueId()).thenReturn(UUID.randomUUID());
        when(steve.getName()).thenReturn("Steve");
        when(server.getPlayerExact("Steve")).thenReturn(steve);
        run("player", "Steve", "add", "place");
        verify(messages).send(console, Messages.CREATIVE_MANAGE_UNAVAILABLE);
        run("group", "builder", "list");
        verify(messages, org.mockito.Mockito.times(2)).send(console, Messages.CREATIVE_MANAGE_UNAVAILABLE);
    }

    @Test
    void noPermissionIsRefusedBeforeAnything() {
        ConsoleCommandSender stranger = mock(ConsoleCommandSender.class);
        command.run(stranger, new String[]{"player", "Steve", "add", "place"}, "/creativemanager", usages::add);
        verify(messages).send(stranger, Messages.NO_PERMISSION);
        assertTrue(usages.isEmpty());
        verify(server, never()).getPlayerExact(anyString());
    }

    @Test
    void listShowsOwnFlagsWithDenialsMarkedOrWhatAppliesUnderVault() {
        Map<CreativeFlag, FlagValue> luckPerms = new EnumMap<>(CreativeFlag.class);
        Map<CreativeFlag, FlagValue> vault = new EnumMap<>(CreativeFlag.class);
        for (CreativeFlag flag : CreativeFlag.values()) {
            luckPerms.put(flag, new FlagValue(null, flag == CreativeFlag.BREAK, true, "inherited"));
            vault.put(flag, new FlagValue(null, flag == CreativeFlag.BREAK, false, ""));
        }
        luckPerms.put(CreativeFlag.PLACE, new FlagValue(true, true, true, "set here"));
        luckPerms.put(CreativeFlag.DROP, new FlagValue(false, false, true, "set here"));
        assertEquals(List.of("place", "-drop"), CreativeManageCommand.listed(luckPerms));
        assertEquals(List.of("break"), CreativeManageCommand.listed(vault));
    }

    @Test
    void completionWalksSubcommandsHoldersActionsAndFlags() {
        assertEquals(List.of("player"), command.complete(console, new String[]{"pl"}));
        assertEquals(List.of("edit", "list", "check", "add", "remove"), command.complete(console, new String[]{"group", "builder", ""}));
        assertEquals(List.of("pickup", "pickblock"), command.complete(console, new String[]{"player", "Steve", "add", "pi"}));
        assertEquals(List.of(), command.complete(console, new String[]{"player", "Steve", "list", ""}));
    }
}
