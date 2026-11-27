package codes.castled.allium.naming;

import codes.castled.allium.PluginStart;
import codes.castled.allium.teleport.TeleportTargets;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnlinePlayerMatcherTest {

    private static Player player(String name) {
        return player(name, name);
    }

    private static Player player(String name, String displayName) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getName()).thenReturn(name);
        when(p.getDisplayName()).thenReturn(displayName);
        when(p.isOnline()).thenReturn(true);
        return p;
    }

    @Test
    void substringAndNicknameMatchFindsMrNasty0627FromNasty() {
        Player nasty = player("MrNasty0627", "§aMrNasty");
        Player jack = player("retrogameman88", "§cJack");
        List<Player> online = List.of(nasty, jack);

        assertSame(nasty, OnlinePlayerMatcher.match(null, online, "nasty", p -> true));
        assertSame(nasty, OnlinePlayerMatcher.match(null, online, "MrNasty", p -> true));
        assertSame(jack, OnlinePlayerMatcher.match(null, online, "jack", p -> true));
        assertSame(jack, OnlinePlayerMatcher.match(null, online, "retro", p -> true));

        // Still finds MrNasty0627 by real-name substring even without any nickname.
        Player noNick = player("MrNasty0627", "MrNasty0627");
        assertSame(noNick, OnlinePlayerMatcher.match(null, List.of(noNick, jack), "nasty", p -> true));
    }

    @Test
    void damerauLevenshteinFindsCloseTyposInUsernamesAndNicknames() {
        Player nasty = player("MrNasty0627", "§aMrNasty");
        Player jack = player("retrogameman88", "§aJack");
        Player alex = player("AlexCraft");
        List<Player> online = List.of(nasty, jack, alex);

        // Full-string typos (transposition, deletion, substitution)
        assertSame(nasty, OnlinePlayerMatcher.match(null, online, "mrnsty", p -> true));
        assertSame(jack, OnlinePlayerMatcher.match(null, online, "jcak", p -> true));
        assertSame(jack, OnlinePlayerMatcher.match(null, online, "retrogamman", p -> true));
        assertSame(alex, OnlinePlayerMatcher.match(null, online, "AlexCrft", p -> true));

        // Substring-window typos inside longer usernames ("nastyy", "nsaty" -> "MrNasty0627")
        assertSame(nasty, OnlinePlayerMatcher.match(null, online, "nastyy", p -> true));
        assertSame(nasty, OnlinePlayerMatcher.match(null, online, "nsaty", p -> true));
    }

    @Test
    void unrelatedQueriesDoNotProduceFalsePositives() {
        Player nasty = player("MrNasty0627", "MrNasty");
        Player steve = player("Steve");
        Player bob = player("Bob");
        List<Player> online = List.of(nasty, steve, bob);

        assertNull(OnlinePlayerMatcher.match(null, online, "xyz", p -> true));
        assertNull(OnlinePlayerMatcher.match(null, online, "nobody", p -> true));
        assertNull(OnlinePlayerMatcher.match(null, online, "zzzzzz", p -> true));
        assertNull(OnlinePlayerMatcher.match(null, online, "q", p -> true));
        assertNull(OnlinePlayerMatcher.match(null, online, "", p -> true));
    }

    @Test
    void priorityOrderPrefersExactThenPrefixThenSubstringThenFuzzy() {
        Player joe = player("Joe");
        Player joey = player("Joey");
        Player mrJoe = player("MrJoe99");
        List<Player> online = List.of(mrJoe, joey, joe);

        assertSame(joe, OnlinePlayerMatcher.match(null, online, "Joe", p -> true));
        assertSame(joe, OnlinePlayerMatcher.match(null, online, "j", p -> true));
        assertSame(joey, OnlinePlayerMatcher.match(null, online, "joey", p -> true));
        assertSame(mrJoe, OnlinePlayerMatcher.match(null, List.of(mrJoe), "joe", p -> true));
    }

    @Test
    void vanishedPlayersAreNeverMatchedOrShadowVisibleMatches() {
        Player vanished = player("NastyHidden");
        Player visible = player("MrNasty0627");
        List<Player> online = List.of(vanished, visible);

        assertSame(visible, OnlinePlayerMatcher.match(null, online, "nasty", p -> p != vanished));
        assertNull(OnlinePlayerMatcher.match(null, List.of(vanished), "nasty", p -> false));
    }

    @Test
    void teleportTargetsUsesFuzzyResolutionForNamedArguments() {
        PlayerNameVisibility.setVanishHooks(null, null);
        PluginStart plugin = mock(PluginStart.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);

        Player sender = player("Jack");
        Player nasty = player("MrNasty0627", "§aMrNasty");
        Player hidden = player("NastyVanished");
        when(sender.canSee(nasty)).thenReturn(true);
        when(sender.canSee(hidden)).thenReturn(false);
        doReturn(List.of(sender, nasty, hidden)).when(server).getOnlinePlayers();

        TeleportTargets targets = new TeleportTargets(plugin);
        assertEquals(List.of(nasty), targets.resolve(sender, "nasty"));
        assertEquals(List.of(nasty), targets.resolve(sender, "nastyy"));
        assertEquals(List.of(), targets.resolve(sender, "nobody"));
    }

    @Test
    void damerauLevenshteinComputesExpectedEditDistances() {
        assertEquals(0, OnlinePlayerMatcher.damerauLevenshtein("nasty", "nasty"));
        assertEquals(1, OnlinePlayerMatcher.damerauLevenshtein("jcak", "jack"));
        assertEquals(1, OnlinePlayerMatcher.damerauLevenshtein("nsaty", "nasty"));
        assertEquals(1, OnlinePlayerMatcher.damerauLevenshtein("nastyy", "nasty"));
        assertEquals(1, OnlinePlayerMatcher.damerauLevenshtein("mrnsty", "mrnasty"));
        assertEquals(3, OnlinePlayerMatcher.damerauLevenshtein("kitten", "sitting"));
    }
}
