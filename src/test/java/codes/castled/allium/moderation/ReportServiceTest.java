package codes.castled.allium.moderation;

import codes.castled.allium.PluginStart;
import codes.castled.allium.discord.DiscordFeeds;
import codes.castled.allium.discord.StaffFeeds;
import codes.castled.allium.message.MessageManager;
import codes.castled.allium.message.Messages;
import codes.castled.allium.naming.NameCache;
import codes.castled.allium.text.FormattedText;
import codes.castled.allium.util.AlliumLogger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    @Test
    void aKnownFirstWordIsTheReportedPlayerAndTheRestIsWhatHappened() {
        NameCache.Entry steve = new NameCache.Entry(UUID.randomUUID(), "Steve", 0L);
        ReportService.Parsed report = ReportService.parse(new String[]{"steve", "griefed", "my", " house"}, steve);
        assertEquals("Steve", report.player());
        assertEquals(steve.uuid(), report.playerId());
        assertEquals("griefed my house", report.text());
        assertNull(ReportService.parse(new String[]{"steve"}, steve), "a player report needs what happened");

        ReportService.Parsed general = ReportService.parse(new String[]{"the", "spawn", "§cshop", "is", "broken"}, null);
        assertNull(general.player());
        assertEquals("the spawn cshop is broken", general.text());
        assertNull(ReportService.parse(new String[0], null));
        assertEquals(ReportService.MAX_LENGTH, ReportService.parse(new String[]{"x".repeat(400)}, null).text().length());
    }

    @Test
    void helpopDeliversToHelpopRecipientsAuditsDistinctActionAndEnforcesCooldown() throws Exception {
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        MessageManager messages = mock(MessageManager.class);
        AlliumLogger logger = mock(AlliumLogger.class);
        ModerationRepository repo = mock(ModerationRepository.class);
        StaffFeeds feeds = mock(StaffFeeds.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.reports.cooldown-seconds", 60);

        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessageManager()).thenReturn(messages);
        when(plugin.logger()).thenReturn(logger);
        when(messages.resolve(eq(Messages.HELPOP_STAFF), any(String[].class)))
                .thenReturn("&e[HelpOp] &fAlice&7: &f{message}");

        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Player requester = mock(Player.class);
        UUID requesterId = UUID.randomUUID();
        when(requester.getUniqueId()).thenReturn(requesterId);
        when(requester.getName()).thenReturn("Alice");
        when(requester.getLocation()).thenReturn(new Location(world, 10, 64, -5));
        when(requester.hasPermission(anyString())).thenReturn(false);

        Player helpopStaff = mock(Player.class);
        Player.Spigot helpopSpigot = mock(Player.Spigot.class);
        when(helpopStaff.spigot()).thenReturn(helpopSpigot);
        when(helpopStaff.hasPermission(ReportService.PERM_HELPOP_RECEIVE)).thenReturn(true);

        Player reportOnlyStaff = mock(Player.class);
        Player.Spigot reportOnlySpigot = mock(Player.Spigot.class);
        when(reportOnlyStaff.spigot()).thenReturn(reportOnlySpigot);
        when(reportOnlyStaff.hasPermission(ReportService.PERM_HELPOP_RECEIVE)).thenReturn(false);
        when(reportOnlyStaff.hasPermission(ReportService.PERM_RECEIVE)).thenReturn(true);

        Collection<? extends Player> online = List.of(requester, helpopStaff, reportOnlyStaff);
        doReturn(online).when(server).getOnlinePlayers();

        AtomicLong clock = new AtomicLong(1_000L);
        ReportService service = new ReportService(plugin, repo, feeds, Runnable::run, clock::get);

        AtomicBoolean usageCalled = new AtomicBoolean(false);
        service.helpop(requester, new String[0], () -> usageCalled.set(true));
        assertTrue(usageCalled.get());

        usageCalled.set(false);
        service.helpop(requester, new String[]{"Steve", "stuck", "in", "portal"}, () -> usageCalled.set(true));
        assertFalse(usageCalled.get());

        verify(messages).send(requester, Messages.HELPOP_SENT);
        verify(helpopSpigot).sendMessage(any(net.md_5.bungee.api.chat.BaseComponent[].class));
        verify(reportOnlySpigot, never()).sendMessage(any(net.md_5.bungee.api.chat.BaseComponent[].class));
        verify(repo).recordAudit(eq("HELPOP"), eq("Alice"), eq(requesterId), eq("Alice"),
                eq("Steve stuck in portal @ world 10, 64, -5"));
        verify(feeds).post(eq(DiscordFeeds.Feed.REPORTS), any());

        // Second immediate call hits cooldown
        service.helpop(requester, new String[]{"again"}, () -> usageCalled.set(true));
        verify(messages).send(eq(requester), eq(Messages.HELPOP_COOLDOWN), eq("{time}"), anyString());
        verify(repo, times(1)).recordAudit(eq("HELPOP"), any(), any(), any(), any());
    }
}
