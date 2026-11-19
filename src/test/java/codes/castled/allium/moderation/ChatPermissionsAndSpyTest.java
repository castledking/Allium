package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.chat.ChatRepository;
import codes.castled.allium.chat.ChatService;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.messaging.IgnoreCommands;
import codes.castled.allium.command.moderation.CommandSpyCommand;
import codes.castled.allium.command.moderation.SpectateCommand;
import codes.castled.allium.command.moderation.SpyCommand;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.MessageSoundSpec;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.IgnoreService;
import codes.castled.allium.messaging.MessagingService;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatPermissionsAndSpyTest {

    @Test
    void commandSpyIsOptInSpyPanelTogglesAndSpectateRunsConsoleCommands() {
        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("spectate-commands", List.of("tp {target}", "gmsp", "dynmap hide {player}"));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SpyTest"));
        AlliumLogger log = new AlliumLogger(plugin);
        when(plugin.logger()).thenReturn(log);

        MessageManager messages = mock(MessageManager.class);
        when(messages.resolve(any(Messages.class), any(String[].class))).thenAnswer(inv -> {
            Messages m = inv.getArgument(0);
            String out = m.getDefault();
            Object[] raw = inv.getArguments();
            for (int i = 1; i + 1 < raw.length; i += 2) {
                out = out.replace(String.valueOf(raw[i]), String.valueOf(raw[i + 1]));
            }
            return out;
        });
        when(messages.resolve(any(Messages.class))).thenAnswer(inv -> ((Messages) inv.getArgument(0)).getDefault());
        when(plugin.getMessageManager()).thenReturn(messages);

        Server server = mock(Server.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(server.getConsoleSender()).thenReturn(console);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getServer()).thenReturn(server);

        UUID staffId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Player staff = mockPlayer(staffId, "Staff", Set.of(
                "allium.commandspy", "allium.socialspy", "allium.chat.spy", "allium.spy", "allium.spectate"));
        Player target = mockPlayer(targetId, "Target", Set.of());
        when(staff.canSee(target)).thenReturn(true);
        when(staff.getServer()).thenReturn(server);
        when(target.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(staff, target));
        when(server.getPlayerExact("Target")).thenReturn(target);

        StaffSpyService spy = new StaffSpyService(plugin);

        // 1. Having allium.commandspy does NOT auto-enable command spy.
        assertFalse(spy.isGlobalCommandSpy(staffId));
        spy.onCommand(new PlayerCommandPreprocessEvent(target, "/home base"));
        verify(staff.spigot(), never()).sendMessage(any(BaseComponent[].class));

        // 2. Enabling /commandspy opts in, skips /login, and reports /home base.
        new CommandSpyCommand(plugin, spy).execute(new BukkitCommandContext(plugin, CoreCommandSpecs.COMMANDSPY, staff, "commandspy", new String[0]));
        assertTrue(spy.isGlobalCommandSpy(staffId));
        verify(messages).send(staff, Messages.COMMANDSPY_ON);

        spy.onCommand(new PlayerCommandPreprocessEvent(target, "/login secret123"));
        verify(staff.spigot(), never()).sendMessage(any(BaseComponent[].class));

        spy.onCommand(new PlayerCommandPreprocessEvent(target, "/home base"));
        verify(staff.spigot()).sendMessage(any(BaseComponent[].class));

        // Turn off global commandspy -> no longer spying.
        new CommandSpyCommand(plugin, spy).execute(new BukkitCommandContext(plugin, CoreCommandSpecs.COMMANDSPY, staff, "commandspy", new String[]{"off"}));
        assertFalse(spy.isGlobalCommandSpy(staffId));

        // 3. /spy Target opens panel and clicking toggles per-target local/messages/commands spying.
        SpyCommand spyCmd = new SpyCommand(plugin, spy);
        spyCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SPY, staff, "spy", new String[]{"Target"}));
        verify(messages).send(staff, Messages.SPY_HEADER, "{player}", "Target");

        spyCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SPY, staff, "spy", new String[]{"Target", "toggle", "local"}));
        assertTrue(spy.isSpyingLocal(staffId, targetId));
        spyCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SPY, staff, "spy", new String[]{"Target", "toggle", "messages"}));
        assertTrue(spy.isSpyingMessagesTarget(staffId, targetId));
        spyCmd.execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SPY, staff, "spy", new String[]{"Target", "toggle", "commands"}));
        assertTrue(spy.isSpyingCommands(staffId, targetId));

        // 4. /spectate Target dispatches all configured commands in console (including tp {target} and gmsp shorthands!).
        new SpectateCommand(plugin, spy).execute(new BukkitCommandContext(plugin, CoreCommandSpecs.SPECTATE, staff, "spectate", new String[]{"Target"}));
        verify(server).dispatchCommand(console, "tp Staff Target");
        verify(server).dispatchCommand(console, "gmsp Staff");
        verify(server).dispatchCommand(console, "dynmap hide Staff");
        verify(messages).send(staff, Messages.SPECTATE_STARTED, "{player}", "Target");
    }

    @Test
    void chatUrlMentionQuestionLocalSpyAndIgnoreExemptWork() throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("modules.chat", true);
        config.set("chat.channels.enabled", true);
        config.set("chat.mentions.enabled", true);
        config.set("chat.mentions.color", "&e");
        config.set("chat.mentions.sound", "minecraft:entity.experience_orb.pickup");
        config.set("chat.question.enabled", true);
        config.set("chat.urls.enabled", true);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ChatPermsTest"));
        AlliumLogger log = new AlliumLogger(plugin);
        when(plugin.logger()).thenReturn(log);

        MessageManager messages = mock(MessageManager.class);
        when(messages.resolve(any(Messages.class), any(String[].class))).thenAnswer(inv -> {
            Messages m = inv.getArgument(0);
            String out = m.getDefault();
            Object[] raw = inv.getArguments();
            for (int i = 1; i + 1 < raw.length; i += 2) {
                out = out.replace(String.valueOf(raw[i]), String.valueOf(raw[i + 1]));
            }
            return out;
        });
        when(plugin.getMessageManager()).thenReturn(messages);

        Server server = mock(Server.class);
        when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.getServer()).thenReturn(server);

        TaskScheduler scheduler = mock(TaskScheduler.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return TaskHandle.NONE; }).when(scheduler).runOnServer(any(Runnable.class));
        when(scheduler.repeatOnServer(any(), anyLong(), anyLong())).thenReturn(TaskHandle.NONE);
        when(plugin.getScheduler()).thenReturn(scheduler);

        World world = mock(World.class);
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        UUID spyId = UUID.randomUUID();

        Set<String> alicePerms = new HashSet<>(Set.of(
                "allium.channel.join.default.read", "allium.channel.join.default.write",
                "allium.channel.join.local.read", "allium.channel.join.local.write",
                "allium.chat.mention", "allium.chat.question"));
        Set<String> bobPerms = new HashSet<>(Set.of(
                "allium.channel.join.default.read", "allium.channel.join.default.write",
                "allium.channel.join.local.read", "allium.channel.join.local.write"));
        Set<String> spyPerms = new HashSet<>(Set.of(
                "allium.channel.join.default.read", "allium.channel.join.default.write",
                "allium.chat.spy", "allium.chat.ignoreexempt"));

        Player alice = mockPlayer(aliceId, "Alice", alicePerms);
        Player bob = mockPlayer(bobId, "Bob", bobPerms);
        Player staffSpy = mockPlayer(spyId, "StaffSpy", spyPerms);
        when(alice.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(bob.getLocation()).thenReturn(new Location(world, 500, 64, 0));
        when(staffSpy.getLocation()).thenReturn(new Location(world, 500, 64, 0));
        when(alice.canSee(any(Player.class))).thenReturn(true);
        when(bob.canSee(any(Player.class))).thenReturn(true);
        when(staffSpy.canSee(any(Player.class))).thenReturn(true);

        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(alice, bob, staffSpy));
        when(server.getPlayer(aliceId)).thenReturn(alice);
        when(server.getPlayer(bobId)).thenReturn(bob);
        when(server.getPlayer(spyId)).thenReturn(staffSpy);

        StaffSpyService spyService = new StaffSpyService(plugin);
        ModerationModule modModule = mock(ModerationModule.class);
        when(modModule.isEnabled()).thenReturn(true);
        ModerationService modSvc = mock(ModerationService.class);
        when(modSvc.isMuteStateReady(any())).thenReturn(true);
        when(modSvc.isMuted(any())).thenReturn(false);
        when(modSvc.isVanished(any(Player.class))).thenReturn(false);
        when(modSvc.isVanished(any(UUID.class))).thenReturn(false);
        when(modModule.moderation()).thenReturn(modSvc);
        when(modModule.spy()).thenReturn(spyService);

        ModuleManager modules = mock(ModuleManager.class);
        when(modules.getModule("moderation")).thenReturn(modModule);
        when(plugin.getModuleManager()).thenReturn(modules);

        ChatRepository repo = mock(ChatRepository.class);
        when(repo.load(any(UUID.class))).thenAnswer(inv -> new ChatRepository.State(inv.getArgument(0), null, "default", Set.of("default"), 1));

        ChatService chat = new ChatService(plugin, repo, Runnable::run);
        chat.enable();

        // 1. URL blocked without allium.chat.url, allowed once granted.
        chat.send(alice, "default", "check https://example.com now", null);
        verify(messages).send(eq(alice), eq(Messages.CHAT_URL_DENIED));
        assertTrue(chat.history().visible(bobId, System.currentTimeMillis()).isEmpty());

        alicePerms.add("allium.chat.url");
        chat.send(alice, "default", "check https://example.com now", null);
        assertEquals(1, chat.history().visible(bobId, System.currentTimeMillis()).size());

        // 2. @Bob mention highlights @Bob and plays mention sound to Bob via MessageManager.playMessageSound.
        chat.send(alice, "default", "hey @bob welcome!", null);
        verify(messages).playMessageSound(eq(bob), any(MessageSoundSpec.class));
        String mentionJson = chat.history().visible(bobId, System.currentTimeMillis()).get(1).rendered().get(bobId);
        String mentionPlain = BaseComponent.toPlainText(ComponentSerializer.parse(mentionJson));
        assertTrue(mentionPlain.contains("@Bob") && mentionJson.contains("yellow"), mentionPlain + " / " + mentionJson);

        // 3. Local chat + allium.chat.spy: StaffSpy outside radius sees [LocalSpy] when spying on Alice,
        //    and does NOT see it when Alice holds allium.chat.spy.exempt.
        spyService.toggleTarget(staffSpy, alice, "local");
        clearInvocations(staffSpy.spigot());
        chat.send(alice, "local", "whisper near spawn", null);
        verify(staffSpy.spigot()).sendMessage(any(BaseComponent[].class));

        alicePerms.add("allium.chat.spy.exempt");
        clearInvocations(staffSpy.spigot());
        chat.send(alice, "local", "exempt local chat", null);
        verify(staffSpy.spigot(), never()).sendMessage(any(BaseComponent[].class));
        alicePerms.remove("allium.chat.spy.exempt");

        // 4. Question prefix (?...) in local chat escalates to global when player has allium.chat.question!
        when(repo.load(aliceId)).thenReturn(new ChatRepository.State(aliceId, null, "local", Set.of("default", "local"), 2));
        // Switch Alice's write channel to local via /channel join local
        chat.dispatch(new BukkitCommandContext(plugin, CoreCommandSpecs.CHAT_SPECS.get(1), alice, "channel", new String[]{"join", "local"}));
        int beforeBob = chat.history().visible(bobId, System.currentTimeMillis()).size();
        chat.send(alice, null, "?where is the shop", null);
        assertEquals(beforeBob + 1, chat.history().visible(bobId, System.currentTimeMillis()).size());
        String qJson = chat.history().visible(bobId, System.currentTimeMillis()).get(beforeBob).rendered().get(bobId);
        String qPlain = BaseComponent.toPlainText(ComponentSerializer.parse(qJson));
        assertTrue(qPlain.contains("[Question]") && qPlain.contains("where is the shop"), qPlain);

        alicePerms.remove("allium.chat.question");
        chat.send(alice, null, "?blocked question", null);
        verify(messages).send(eq(alice), eq(Messages.CHAT_QUESTION_DENIED));
        assertEquals(beforeBob + 1, chat.history().visible(bobId, System.currentTimeMillis()).size());

        // 5. allium.chat.ignoreexempt refuses /ignore StaffSpy.
        MessagingService msgSvc = mock(MessagingService.class);
        when(msgSvc.find(alice, "StaffSpy")).thenReturn(staffSpy);
        when(msgSvc.visibleTo(alice, staffSpy)).thenReturn(true);
        IgnoreService ignores = mock(IgnoreService.class);
        when(ignores.list(aliceId)).thenReturn(Set.of());
        new IgnoreCommands.Ignore(plugin, msgSvc, ignores).execute(
                new BukkitCommandContext(plugin, CoreCommandSpecs.IGNORE, alice, "ignore", new String[]{"StaffSpy"}));
        verify(messages).send(alice, Messages.IGNORE_EXEMPT, "{player}", "StaffSpy");
        verify(ignores, never()).add(any(), any(), any());

        chat.close();
    }

    private static Player mockPlayer(UUID id, String name, Set<String> perms) {
        Player player = mock(Player.class);
        Player.Spigot spigot = mock(Player.Spigot.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.spigot()).thenReturn(spigot);
        when(player.hasPermission(anyString())).thenAnswer(inv -> perms.contains(inv.getArgument(0)));
        return player;
    }
}
