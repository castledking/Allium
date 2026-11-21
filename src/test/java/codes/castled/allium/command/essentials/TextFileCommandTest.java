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
import static org.mockito.Mockito.doReturn;
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
        for (String name : List.of("info.txt", "motd.txt", "rules.txt", "custom.txt")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
                CustomTextDocument doc = CustomTextDocument.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
                assertFalse(doc.index().isEmpty(), name);
                if (name.equals("rules.txt")) assertEquals("griefing", doc.primaryName("grief").orElseThrow());
                if (name.equals("custom.txt")) assertTrue(doc.section("rules").isEmpty(), "the rules live in rules.txt now");
                if (name.equals("info.txt")) {
                    for (String chapter : List.of("colors", "formatting", "clicks", "tags", "files")) {
                        assertFalse(doc.section(chapter).orElse(List.of()).isEmpty(), chapter);
                    }
                }
            }
        }
    }

    private void bundled(String name) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            Files.write(dir.resolve(name), in.readAllBytes());
        }
    }

    @Test
    void theBundledInfoShowsTheOldOnlineLineAndItsChaptersShowCodesAsWritten() throws Exception {
        bundled("info.txt");
        TextFileCommand info = TextFileCommand.info(plugin);
        run(info);
        assertEquals("Paper \u2014 0 player(s) online.", lines.get(0));
        assertTrue(lines.get(2).startsWith("Chapters: colors, formatting, clicks, tags, files"), lines.toString());
        lines.clear();
        run(info, "colours");
        assertEquals("&0 &1 &2 &3 &4 &5 &6 &7", lines.get(1));
        assertTrue(lines.contains("A colour turns styles off, so put styles after it: &e&l bold yellow"), lines.toString());
        lines.clear();
        run(info, "formatting");
        assertTrue(lines.contains("<gradient:#ff5f6d:#ffc371>Sunset</gradient>"), lines.toString());
        assertTrue(lines.contains("&#ff7a00 orange  #33ccff sky blue  <#9b59b6> purple"), lines.toString());
        lines.clear();
        run(info, "tags");
        assertEquals("{player} CONSOLE  {displayname} CONSOLE  {nickname} CONSOLE", lines.get(1));
        lines.clear();
        run(info, "nope");
        verify(messages).send(console, Messages.INFO_MISSING, "{section}", "nope");
    }

    private Player player(String name, List<String> shown) {
        Player player = mock(Player.class);
        Player.Spigot spigot = mock(Player.Spigot.class);
        when(player.spigot()).thenReturn(spigot);
        when(player.getName()).thenReturn(name);
        when(player.getDisplayName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        doAnswer(inv -> {
            Object[] raw = inv.getArguments();
            BaseComponent[] parts = raw.length == 1 && raw[0] instanceof BaseComponent[] array ? array
                    : java.util.Arrays.copyOf(raw, raw.length, BaseComponent[].class);
            shown.add(BaseComponent.toPlainText(parts));
            return null;
        }).when(spigot).sendMessage(any(BaseComponent[].class));
        return player;
    }

    @Test
    void aPlayersOwnFileThenTheirGroupsFileWinOverTheSharedOne() throws Exception {
        write("info.txt", "Shared for {player}\n");
        write("info_steve.txt", "Steve's own page\n");
        write("info_vip.txt", "For the {group} group\n");
        PluginManager plugins = plugin.getServer().getPluginManager();
        when(plugins.isPluginEnabled("Vault")).thenReturn(true);
        org.bukkit.plugin.ServicesManager services = mock(org.bukkit.plugin.ServicesManager.class);
        when(plugin.getServer().getServicesManager()).thenReturn(services);
        net.milkbowl.vault.permission.Permission permissions = mock(net.milkbowl.vault.permission.Permission.class);
        when(services.load(net.milkbowl.vault.permission.Permission.class)).thenReturn(permissions);
        when(permissions.hasGroupSupport()).thenReturn(true);
        TextFileCommand info = TextFileCommand.info(plugin);

        List<String> shown = new ArrayList<>();
        Player steve = player("Steve", shown);
        when(permissions.getPrimaryGroup(steve)).thenReturn("VIP");
        Player alex = player("Alex", shown);
        when(permissions.getPrimaryGroup(alex)).thenReturn("VIP");
        Player sam = player("Sam", shown);
        when(permissions.getPrimaryGroup(sam)).thenReturn("default");
        for (Player reader : List.of(steve, alex, sam)) {
            info.execute(new BukkitCommandContext(plugin, info.spec(), reader, "info", new String[0]));
        }
        assertEquals(List.of("Steve's own page", "For the VIP group", "Shared for Sam"), shown);
        run(info);
        assertEquals(List.of("Shared for CONSOLE"), lines);
    }

    @Test
    void informShowsTheTargetTheirOwnPagesAndReportsToTheSender() throws Exception {
        write("info.txt", "Shared for {player}\n#vote\nVote, {player}!\n");
        write("info_steve.txt", "Steve's own page\n#vote\nSteve votes\n");
        TextFileCommand info = TextFileCommand.info(plugin);
        InformCommand inform = new InformCommand(plugin, info);
        List<String> shown = new ArrayList<>();
        Player steve = player("Steve", shown);
        Player alex = player("Alex", shown);
        when(plugin.getServer().getPlayer("Steve")).thenReturn(steve);
        when(plugin.getServer().getPlayer("Alex")).thenReturn(alex);
        Server server = plugin.getServer();
        doReturn(List.of(steve, alex)).when(server).getOnlinePlayers();
        when(plugin.getServer().getPlayerExact("Steve")).thenReturn(steve);

        inform.execute(new BukkitCommandContext(plugin, inform.spec(), console, "inform", new String[]{"Steve"}));
        inform.execute(new BukkitCommandContext(plugin, inform.spec(), console, "inform", new String[]{"Alex", "vote"}));
        assertEquals(List.of("Steve's own page", "Vote, Alex!"), shown);
        verify(messages).send(console, Messages.INFORM_SENT, "{player}", "Steve", "{section}", "");
        verify(messages).send(console, Messages.INFORM_SENT_CHAPTER, "{player}", "Alex", "{section}", "vote");

        inform.execute(new BukkitCommandContext(plugin, inform.spec(), console, "inform", new String[]{"Alex", "nope"}));
        verify(messages).send(console, Messages.INFO_MISSING, "{section}", "nope");
        inform.execute(new BukkitCommandContext(plugin, inform.spec(), console, "inform", new String[]{"Nobody"}));
        verify(messages).send(console, Messages.PLAYER_NOT_FOUND, "{player}", "Nobody");
        assertEquals(2, shown.size(), "nothing more reached a player");

        inform.execute(new BukkitCommandContext(plugin, inform.spec(), steve, "inform", new String[0]));
        assertEquals("Steve's own page", shown.get(2));
        assertEquals(List.of("Steve"), inform.complete(console, new String[]{"st"}));
        assertEquals(List.of("vote"), inform.complete(console, new String[]{"Steve", "v"}));
    }

    @Test
    void anOldCustomtextFileIsRenamedOnceAndNeverOverAnExistingCustomFile() throws Exception {
        write("customtext.txt", "old text\n");
        TextFileCommand custom = TextFileCommand.customText(plugin);
        custom.installDefault();
        assertFalse(Files.exists(dir.resolve("customtext.txt")));
        assertEquals("old text\n", Files.readString(dir.resolve("custom.txt")));
        run(custom);
        assertEquals(List.of("old text"), lines);

        write("customtext.txt", "stale copy\n");
        TextFileCommand.customText(plugin).installDefault();
        assertEquals("old text\n", Files.readString(dir.resolve("custom.txt")));
        assertTrue(Files.exists(dir.resolve("customtext.txt")), "a second file is left for the admin");
    }
}
