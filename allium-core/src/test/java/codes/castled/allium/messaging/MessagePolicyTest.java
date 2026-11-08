package codes.castled.allium.messaging;

import codes.castled.allium.messaging.MessagePolicy.Acceptance;
import codes.castled.allium.messaging.MessagePolicy.Ignore;
import codes.castled.allium.messaging.MessagePolicy.Outcome;
import codes.castled.allium.messaging.MessagePolicy.Recipient;
import codes.castled.allium.messaging.MessagePolicy.Sender;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MessagePolicyTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static Sender player(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return new Sender(ALICE, granted::contains, false);
    }

    private static Sender console() {
        return new Sender(null, p -> true, false);
    }

    private static Recipient bob(Acceptance acceptance, Ignore ignore) {
        return new Recipient(BOB, true, false, acceptance, ignore);
    }

    @Test
    void anAcceptingRecipientGetsTheMessage() {
        assertEquals(Outcome.DELIVER, MessagePolicy.decide(player(), bob(Acceptance.ACCEPTS, Ignore.NO)));
    }

    @Test
    void toggledOffAndIgnoredLookTheSameToTheSender() {
        assertEquals(Outcome.CANNOT_RECEIVE, MessagePolicy.decide(player(), bob(Acceptance.REFUSES, Ignore.NO)));
        assertEquals(Outcome.CANNOT_RECEIVE, MessagePolicy.decide(player(), bob(Acceptance.ACCEPTS, Ignore.YES)));
        assertEquals(Outcome.CANNOT_RECEIVE, MessagePolicy.decide(player(), bob(Acceptance.REFUSES, Ignore.YES)));
    }

    @Test
    void unloadedPreferencesOrIgnoreListsFailClosed() {
        assertEquals(Outcome.UNAVAILABLE, MessagePolicy.decide(player(), bob(Acceptance.UNAVAILABLE, Ignore.NO)));
        assertEquals(Outcome.UNAVAILABLE, MessagePolicy.decide(player(), bob(Acceptance.ACCEPTS, Ignore.UNKNOWN)));
        assertEquals(Outcome.UNAVAILABLE, MessagePolicy.decide(player(MessagePolicy.BYPASS_TOGGLE), bob(Acceptance.UNAVAILABLE, Ignore.NO)),
                "bypassing the toggle does not bypass unloaded preferences");
    }

    @Test
    void aMutedSenderIsStoppedFirstButTheConsoleIsNeverMuted() {
        Sender muted = new Sender(ALICE, p -> false, true);
        assertEquals(Outcome.SENDER_MUTED, MessagePolicy.decide(muted, bob(Acceptance.ACCEPTS, Ignore.NO)));
        assertEquals(Outcome.SENDER_MUTED, MessagePolicy.decide(muted, new Recipient(null, false, false, Acceptance.ACCEPTS, Ignore.NO)),
                "a muted sender learns nothing about the recipient");
        assertEquals(Outcome.DELIVER, MessagePolicy.decide(new Sender(null, p -> true, true), bob(Acceptance.ACCEPTS, Ignore.NO)));
    }

    @Test
    void aVanishedRecipientLooksOfflineUnlessTheSenderMaySeeThem() {
        Recipient offline = new Recipient(null, false, false, Acceptance.ACCEPTS, Ignore.NO);
        Recipient vanished = new Recipient(BOB, true, true, Acceptance.ACCEPTS, Ignore.NO);
        assertEquals(Outcome.NOT_FOUND, MessagePolicy.decide(player(), offline));
        assertEquals(Outcome.NOT_FOUND, MessagePolicy.decide(player(), vanished), "the same outcome as offline");
        assertEquals(Outcome.DELIVER, MessagePolicy.decide(player(MessagePolicy.SEE_VANISHED), vanished));
        Recipient vanishedAndOff = new Recipient(BOB, true, true, Acceptance.REFUSES, Ignore.YES);
        assertEquals(Outcome.NOT_FOUND, MessagePolicy.decide(player(), vanishedAndOff), "vanish hides even the refusal");
    }

    @Test
    void staffBypassOnlyThroughExplicitPermissions() {
        assertEquals(Outcome.DELIVER, MessagePolicy.decide(player(MessagePolicy.BYPASS_TOGGLE), bob(Acceptance.REFUSES, Ignore.NO)));
        assertEquals(Outcome.CANNOT_RECEIVE, MessagePolicy.decide(player(MessagePolicy.BYPASS_TOGGLE), bob(Acceptance.ACCEPTS, Ignore.YES)),
                "the toggle bypass does not bypass ignore");
        assertEquals(Outcome.DELIVER, MessagePolicy.decide(player(MessagePolicy.BYPASS_IGNORE), bob(Acceptance.ACCEPTS, Ignore.YES)));
        assertEquals(Outcome.CANNOT_RECEIVE, MessagePolicy.decide(player(MessagePolicy.BYPASS_IGNORE), bob(Acceptance.REFUSES, Ignore.NO)));
        assertEquals(Outcome.CANNOT_RECEIVE, MessagePolicy.decide(player("allium.admin", "allium.*"), bob(Acceptance.REFUSES, Ignore.NO)),
                "no implied bypass from other permissions");
    }

    @Test
    void theConsoleIsNeverIgnoredAndHasEveryBypass() {
        assertEquals(Outcome.DELIVER, MessagePolicy.decide(console(), bob(Acceptance.ACCEPTS, Ignore.YES)));
        assertEquals(Outcome.DELIVER, MessagePolicy.decide(console(), bob(Acceptance.REFUSES, Ignore.UNKNOWN)));
        assertEquals(Outcome.UNAVAILABLE, MessagePolicy.decide(console(), bob(Acceptance.UNAVAILABLE, Ignore.NO)));
    }

    @Test
    void messagingYourselfIsRefused() {
        assertEquals(Outcome.SELF, MessagePolicy.decide(player(), new Recipient(ALICE, true, false, Acceptance.ACCEPTS, Ignore.NO)));
    }
}
