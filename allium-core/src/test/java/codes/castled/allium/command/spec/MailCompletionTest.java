package codes.castled.allium.command.spec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MailCompletionTest {
    @Test
    void baseAndEachActionPermissionGateCandidatesAndDynamicReads() {
        var none = context(Set.of("allium.mail.send", "allium.mail.gift"), List.of("abc12345"), List.of("def12345"));
        assertEquals(List.of(), complete(none, "send", ""));
        assertEquals(List.of(), complete(none, "gift", ""));
        assertEquals(List.of(), complete(none, "claim", ""));
        var reader = context(Set.of("allium.mail"), List.of("abc12345"), List.of("def12345"));
        assertEquals(List.of("read", "claim", "delete", "clear"), complete(reader, ""));
        assertEquals(List.of(), complete(reader, "send", "a"));
        assertEquals(List.of(), complete(reader, "gift", "a"));
        var sender = context(Set.of("allium.mail", "allium.mail.send"), List.of(), List.of());
        assertEquals(List.of("read", "send", "claim", "delete", "clear"), complete(sender, ""));
        assertEquals(List.of("Alice", "Alfred"), complete(sender, "send", "aL"));
        assertEquals(List.of(), complete(sender, "send", "Alice", ""));
        assertEquals(List.of(), complete(sender, "send", "Alice", "hello", ""));
    }

    @Test
    void giftTargetsAmountsAndNotesUseOnlyTheirOwnPosition() {
        var gift = context(Set.of("allium.mail", "allium.mail.gift"), List.of(), List.of());
        assertEquals(List.of("gift"), complete(gift, "g"));
        assertEquals(List.of("Alice", "Alfred", "Bob"), complete(gift, "GIFT", ""));
        assertEquals(List.of("Bob"), complete(gift, "gift", "b"));
        assertEquals(List.of("1", "8", "16", "32", "64"), complete(gift, "gift", "Alice", ""));
        assertEquals(List.of("1", "16"), complete(gift, "gift", "Alice", "1"));
        assertEquals(List.of(), complete(gift, "gift", "Alice", "16", "a"));
        assertEquals(List.of(), complete(gift, "gift", "Alice", "a note", ""));
        assertEquals(List.of(), complete(gift, "gift", "Alice", "16", "a", ""));
    }

    @Test
    void idsUseTheCorrectMailboxSnapshotAndAllRequiresClaimableMail() {
        var loaded = context(Set.of("allium.mail"), List.of("abc12345", "beef1234"), List.of("dead1234"));
        assertEquals(List.of("abc12345", "beef1234", "all"), complete(loaded, "claim", ""));
        assertEquals(List.of("abc12345", "all"), complete(loaded, "claim", "a"));
        assertEquals(List.of("beef1234"), complete(loaded, "claim", "B"));
        assertEquals(List.of("dead1234"), complete(loaded, "delete", ""));
        assertEquals(List.of(), complete(loaded, "delete", "a"));
        assertEquals(List.of(), complete(loaded, "claim", "all", ""));
        var empty = context(Set.of("allium.mail"), List.of(), List.of());
        assertEquals(List.of(), complete(empty, "claim", ""));
        assertEquals(List.of(), complete(empty, "delete", ""));
    }

    private static List<String> complete(CommandCompletionContext context, String... args) {
        return CoreCommandSpecs.MAIL.complete(context, args);
    }

    private static CommandCompletionContext context(Set<String> permissions, List<String> gifts, List<String> text) {
        return new CommandCompletionContext() {
            @Override
            public boolean hasPermission(String permissionNode) {
                return permissions.contains(permissionNode);
            }

            @Override
            public List<String> visibleOnlinePlayers() {
                fail("mail targets must use the memory-only known-player source");
                return List.of();
            }

            @Override
            public List<String> knownPlayers(String prefix) {
                assertTrue(permissions.contains("allium.mail"));
                assertTrue(permissions.contains("allium.mail.send") || permissions.contains("allium.mail.gift"));
                return List.of("Alice", "Alfred", "Bob");
            }

            @Override
            public List<String> mailIds(boolean claimableOnly) {
                assertTrue(permissions.contains("allium.mail"));
                return claimableOnly ? gifts : text;
            }
        };
    }
}
