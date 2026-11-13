package codes.castled.allium.mail;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class MailPolicyTest {

    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final Predicate<String> NONE = node -> false;
    private static final Predicate<String> BYPASS = Set.of(MailPolicy.BYPASS_IGNORE)::contains;

    @Test
    void theMatrix() {
        assertEquals(MailPolicy.Outcome.DELIVER, MailPolicy.decide(SENDER, NONE, false, RECIPIENT, MailPolicy.Ignore.NO));
        assertEquals(MailPolicy.Outcome.SENDER_MUTED, MailPolicy.decide(SENDER, BYPASS, true, RECIPIENT, MailPolicy.Ignore.NO),
                "mute comes first, even for staff");
        assertEquals(MailPolicy.Outcome.NOT_FOUND, MailPolicy.decide(SENDER, NONE, false, null, MailPolicy.Ignore.NO));
        assertEquals(MailPolicy.Outcome.SELF, MailPolicy.decide(SENDER, NONE, false, SENDER, MailPolicy.Ignore.NO));
        assertEquals(MailPolicy.Outcome.CANNOT_RECEIVE, MailPolicy.decide(SENDER, NONE, false, RECIPIENT, MailPolicy.Ignore.YES));
        assertEquals(MailPolicy.Outcome.DELIVER, MailPolicy.decide(SENDER, BYPASS, false, RECIPIENT, MailPolicy.Ignore.YES));
        assertEquals(MailPolicy.Outcome.UNAVAILABLE, MailPolicy.decide(SENDER, NONE, false, RECIPIENT, MailPolicy.Ignore.UNKNOWN),
                "an unreadable ignore list fails closed");
        assertEquals(MailPolicy.Outcome.DELIVER, MailPolicy.decide(SENDER, BYPASS, false, RECIPIENT, MailPolicy.Ignore.UNKNOWN));
    }

    @Test
    void messageTogglesAndUnloadedPreferencesApplyToMailEvenWithIgnoreBypass() {
        assertEquals(MailPolicy.Outcome.CANNOT_RECEIVE, MailPolicy.decide(SENDER, NONE, false, RECIPIENT,
                MailPolicy.Ignore.NO, MailPolicy.Acceptance.REFUSES));
        assertEquals(MailPolicy.Outcome.CANNOT_RECEIVE, MailPolicy.decide(SENDER, BYPASS, false, RECIPIENT,
                MailPolicy.Ignore.NO, MailPolicy.Acceptance.REFUSES));
        Predicate<String> both = Set.of(MailPolicy.BYPASS_IGNORE, MailPolicy.BYPASS_TOGGLE)::contains;
        assertEquals(MailPolicy.Outcome.DELIVER, MailPolicy.decide(SENDER, both, false, RECIPIENT,
                MailPolicy.Ignore.NO, MailPolicy.Acceptance.REFUSES));
        for (Predicate<String> permissions : java.util.List.of(NONE, both)) {
            assertEquals(MailPolicy.Outcome.UNAVAILABLE, MailPolicy.decide(SENDER, permissions, false, RECIPIENT,
                    MailPolicy.Ignore.NO, MailPolicy.Acceptance.UNAVAILABLE));
        }
    }

    @Test
    void theConsoleIsNeverMutedOrIgnored() {
        assertEquals(MailPolicy.Outcome.DELIVER, MailPolicy.decide(null, NONE, true, RECIPIENT, MailPolicy.Ignore.YES));
        assertEquals(MailPolicy.Outcome.NOT_FOUND, MailPolicy.decide(null, NONE, false, null, MailPolicy.Ignore.NO));
    }

    @Test
    void limitsAreValidatedAndExpiryNeverAppliesWhenZero() {
        assertThrows(IllegalArgumentException.class, () -> new MailLimits(0, 1, 1_000L, 1, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new MailLimits(1, 0, 1_000L, 1, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new MailLimits(1, 1, 10L, 1, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new MailLimits(1, 1, 1_000L, 0, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new MailLimits(1, 1, 1_000L, 1, -1L, 1));
        assertThrows(IllegalArgumentException.class, () -> new MailLimits(1, 1, 1_000L, 1, 0L, 0));
        assertEquals(0L, new MailLimits(1, 1, 1_000L, 1, 0L, 1).expiresAt(5L));
        assertEquals(15L, new MailLimits(1, 1, 1_000L, 1, 10L, 1).expiresAt(5L));
    }

    @Test
    void aGiftCarriesItemsAndTextCarriesNone() {
        assertThrows(IllegalArgumentException.class, () -> MailRecord.pendingGift(UUID.randomUUID(), RECIPIENT, SENDER, "s", null, "x", 0, "o", 1L));
        MailRecord text = MailRecord.text(UUID.fromString("3f2a91c0-0000-0000-0000-000000000000"), RECIPIENT, SENDER, "s", "hi", "o", 1L, 0L);
        assertEquals("3f2a91c0", text.shortId());
        assertFalse(text.expired(Long.MAX_VALUE), "expires_at 0 is never");
    }
}
