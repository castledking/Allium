package codes.castled.allium.managers.chat;

import github.scarsz.discordsrv.dependencies.jda.api.utils.data.DataObject;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordBanContextMenuTest {

    private static final Set<String> KNOWN = Set.of("Steve_123", "Alex");

    private static String lookup(String candidate) {
        if ("✦ Coolguy".equals(candidate)) {
            return "Alex";
        }
        return KNOWN.stream().filter(name -> name.equalsIgnoreCase(candidate)).findFirst().orElse(null);
    }

    @Test
    void joinEmbedAuthorResolvesEscapedName() {
        List<String> texts = List.of("Steve\\_123 joined the server", "", "");
        assertEquals("Steve_123", DiscordBanContextMenu.findPlayerName(texts, DiscordBanContextMenuTest::lookup));
    }

    @Test
    void webhookNicknameResolvesWholeText() {
        assertEquals("Alex", DiscordBanContextMenu.findPlayerName(List.of("✦ Coolguy"), DiscordBanContextMenuTest::lookup));
    }

    @Test
    void unknownTextResolvesToEmpty() {
        assertEquals("", DiscordBanContextMenu.findPlayerName(List.of("Server restarting soon"), DiscordBanContextMenuTest::lookup));
    }

    @Test
    void messageTextsIncludeWebhookNameOnlyForWebhooks() {
        DataObject webhook = DataObject.fromJson("{\"id\":\"1\",\"webhook_id\":\"9\",\"author\":{\"username\":\"Alex\"},\"content\":\"hi\",\"embeds\":[]}");
        DataObject bot = DataObject.fromJson("{\"id\":\"2\",\"author\":{\"username\":\"Survival Fun\"},\"content\":\"\","
                + "\"embeds\":[{\"author\":{\"name\":\"Steve joined the server\"}}]}");

        assertEquals(List.of("Alex", "hi"), DiscordBanContextMenu.messageTexts(webhook));
        assertEquals(List.of("Steve joined the server", "", "", ""), DiscordBanContextMenu.messageTexts(bot));
    }

    @Test
    void modalValuesAreReadFromRows() {
        DataObject data = DataObject.fromJson("{\"custom_id\":\"allium_ban\",\"components\":["
                + "{\"type\":1,\"components\":[{\"type\":4,\"custom_id\":\"player\",\"value\":\"Steve\"}]},"
                + "{\"type\":1,\"components\":[{\"type\":4,\"custom_id\":\"duration\",\"value\":\"7d\"}]}]}");
        assertEquals(Map.of("player", "Steve", "duration", "7d"), DiscordBanContextMenu.modalValues(data));
    }

    @Test
    void permanentBanWithoutReasonCollapsesSpaces() {
        assertEquals("ban Steve", DiscordBanContextMenu.buildCommand("/ban {player} {reason}", "Steve", "", "", "mod"));
    }

    @Test
    void tempbanFillsAllPlaceholders() {
        assertEquals("tempban Steve 7d Griefing (by mod)",
                DiscordBanContextMenu.buildCommand("tempban {player} {duration} {reason}", "Steve", "7d", "Griefing (by {staff})", "mod"));
    }

    @Test
    void reasonNewlinesAreFlattened() {
        assertEquals("line one line two", DiscordBanContextMenu.cleanReason("line one\n\nline two\t"));
    }

    @Test
    void exemptGroupMatchesCaseInsensitively() {
        assertEquals("Owner", DiscordBanContextMenu.matchesExemptGroup(new String[]{"member", "Owner"}, List.of("owner", "admin")));
    }

    @Test
    void exemptGroupReturnsNullWhenNotListed() {
        assertEquals(null, DiscordBanContextMenu.matchesExemptGroup(new String[]{"member", "vip"}, List.of("owner", "admin")));
    }

    @Test
    void exemptGroupReturnsNullForEmptyOrNullGroups() {
        assertEquals(null, DiscordBanContextMenu.matchesExemptGroup(new String[0], List.of("owner")));
        assertEquals(null, DiscordBanContextMenu.matchesExemptGroup(null, List.of("owner")));
        assertEquals(null, DiscordBanContextMenu.matchesExemptGroup(new String[]{"owner"}, List.of()));
    }
}
