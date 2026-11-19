package codes.castled.allium.command.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.CommandManager;
import codes.castled.allium.command.spec.CommandFamilyPolicy;
import codes.castled.allium.customtext.CustomTextDocument;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.scheduler.TaskScheduler;
import codes.castled.allium.util.AlliumLogger;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextFileCommandTest {

    @TempDir
    Path dir;
    private PluginStart plugin;
    private MessageManager messages;
    private TaskScheduler scheduler;
    private final YamlConfiguration config = new YamlConfiguration();
    private final List<String> lines = new ArrayList<>();
    private ConsoleCommandSender console;

    @BeforeEach
    void setUp() {
        plugin = mock(PluginStart.class);
        messages = mock(MessageManager.class);
        scheduler = mock(TaskScheduler.class);
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getName()).thenReturn("Paper");
        when(server.getMaxPlayers()).thenReturn(20);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(AlliumLogger.class));
        when(messages.resolve(any(Messages.class), any(String[].class))).thenAnswer(inv -> {
            Messages m = inv.getArgument(0);
            String out = m.getDefault().replace("{prefix}", "[Allium]");
            Object[] raw = inv.getArguments();
            for (int i = 1; i + 1 < raw.length; i += 2) out = out.replace(String.valueOf(raw[i]), String.valueOf(raw[i + 1]));
            return out;
        });
        when(messages.resolve(any(Messages.class))).thenAnswer(inv -> ((Messages) inv.getArgument(0)).getDefault());
        console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        when(console.hasPermission(anyString())).thenReturn(true);
        doAnswer(inv -> lines.add(ChatColor.stripColor(inv.<String>getArgument(0)))).when(console).sendMessage(anyString());
    }

    private void write(String name, String text) throws Exception {
        Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
    }

    private void run(TextFileCommand command, String... args) {
        command.execute(new BukkitCommandContext(plugin, command.spec(), console, command.spec().canonicalName(), args));
    }

    @Test
    void rulesShowTheIndexChaptersAndAliasesAndNameAMissingChapter() throws Exception {
        write("rules.txt", "&6Rules for {player} on {server}\n1. Be kind\n#griefing,grief\nNo griefing\n");
        TextFileCommand rules = TextFileCommand.rules(plugin);
        run(rules);
        assertEquals(List.of("Rules for CONSOLE on Paper", "1. Be kind"), lines);
        lines.clear();
        run(rules, "GRIEF");
        assertEquals(List.of("No griefing"), lines);
        run(rules, "nope");
        verify(messages).send(console, Messages.RULES_MISSING, "{section}", "nope");
        assertEquals(List.of("griefing", "grief"), rules.complete(console, new String[]{"g"}));
    }

    @Test
    void aFileWithOnlySectionsListsThemAndAnEmptyFileSaysSo() throws Exception {
        write("rules.txt", "## only chapters\n#building,build\nBuild nicely\n#pvp\nNo spawn kills\n");
        run(TextFileCommand.rules(plugin));
        assertEquals(List.of("[Allium] Sections of /rules: building, pvp"), lines);

        write("motd.txt", "## nothing yet\n");
        run(TextFileCommand.motd(plugin));
        verify(messages).send(console, Messages.CUSTOMTEXT_EMPTY, "{file}", "motd.txt");
    }

    @Test
    void longSectionsPageAndAnEditedFileIsReadAgain() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 12; i++) text.append("line ").append(i).append('\n');
        write("motd.txt", text.toString());
        TextFileCommand motd = TextFileCommand.motd(plugin);
        run(motd, "2");
        assertTrue(lines.containsAll(List.of("line 10", "line 12")), lines.toString());
        assertFalse(lines.contains("line 9"));

        lines.clear();
        write("motd.txt", "fresh\n");
        dir.resolve("motd.txt").toFile().setLastModified(System.currentTimeMillis() + 5_000);
        run(motd);
        assertEquals(List.of("fresh"), lines);
    }

    @Test
    void theJoinMotdFollowsItsSettingPermissionDelayAndDisabledFamily() throws Exception {
        write("motd.txt", "Welcome {player}\n#extra\nNot on join\n");
        TextFileCommand motd = TextFileCommand.motd(plugin);
        MotdOnJoin join = new MotdOnJoin(plugin, motd);
        Player player = mock(Player.class);
        Player.Spigot spigot = mock(Player.Spigot.class);
        when(player.spigot()).thenReturn(spigot);
        when(player.getName()).thenReturn("Steve");
        when(player.isOnline()).thenReturn(true);
        when(plugin.getServer().getPlayer(any(java.util.UUID.class))).thenReturn(player);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);
        List<Runnable> later = new ArrayList<>();
        when(scheduler.runOnServerLater(any(Runnable.class), anyLong())).thenAnswer(inv -> {
            later.add(inv.getArgument(0));
            return TaskHandle.NONE;
        });

        join.onJoin(event);
        verify(spigot, never()).sendMessage(any(BaseComponent[].class));
        when(player.hasPermission("allium.motd")).thenReturn(true);
        join.onJoin(event);
        verify(scheduler).runOnServerLater(any(Runnable.class), eq(500L));
        later.get(0).run();
        org.mockito.ArgumentCaptor<BaseComponent[]> sent = org.mockito.ArgumentCaptor.forClass(BaseComponent[].class);
        verify(spigot).sendMessage(sent.capture());
        assertEquals("Welcome Steve", BaseComponent.toPlainText(sent.getValue()));

        config.set("motd.show-on-join", false);
        join.onJoin(event);
        config.set("motd.show-on-join", true);
        CommandManager commands = mock(CommandManager.class);
        CommandFamilyPolicy policy = mock(CommandFamilyPolicy.class);
        when(plugin.getCommandManager()).thenReturn(commands);
        when(commands.getFamilyPolicy()).thenReturn(policy);
        when(policy.isDisabled("motd")).thenReturn(true);
        join.onJoin(event);
        verify(scheduler).runOnServerLater(any(Runnable.class), anyLong());
    }

    @Test
    void theBundledFilesHaveAnIndexAndTheRulesHaveTheGriefingChapter() throws Exception {
        for (String name : List.of("motd.txt", "rules.txt", "customtext.txt")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
                CustomTextDocument doc = CustomTextDocument.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
                assertFalse(doc.index().isEmpty(), name);
                if (name.equals("rules.txt")) assertEquals("griefing", doc.primaryName("grief").orElseThrow());
                if (name.equals("customtext.txt")) assertTrue(doc.section("rules").isEmpty(), "the rules live in rules.txt now");
            }
        }
    }
}
