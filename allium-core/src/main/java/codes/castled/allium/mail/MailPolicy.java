package codes.castled.allium.mail;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Whether a mail may be sent, the same privacy rules as private messages: a recipient who ignores
 * the sender gets the sender the generic {@link Outcome#CANNOT_RECEIVE}, and an ignore list that
 * is not loaded fails closed as {@link Outcome#UNAVAILABLE}. Mail is offline-capable, so being
 * offline or vanished never changes the outcome, and a sent mail never tells the sender whether
 * the recipient is online.
 *
 * <p>Mail follows the message acceptance preference, including for offline recipients. Storage
 * failures refuse delivery even when a sender may bypass a recipient's explicit toggle.
 */
public final class MailPolicy {

    /** Lets staff mail someone who ignores them; the same node as private messages. */
    public static final String BYPASS_IGNORE = "allium.msg.bypass.ignore";
    public static final String BYPASS_TOGGLE = "allium.msg.bypass.toggle";
    public enum Acceptance { ACCEPTS, REFUSES, UNAVAILABLE }

    public enum Outcome {
        DELIVER,
        SENDER_MUTED,
        /** No player of that name has ever joined (nothing to deliver to). */
        NOT_FOUND,
        SELF,
        /** The recipient ignores the sender: one generic refusal. */
        CANNOT_RECEIVE,
        /** The recipient's ignore list could not be read: refuse, "try again shortly". */
        UNAVAILABLE
    }

    /** Whether the recipient ignores the sender; UNKNOWN when it could not be read. */
    public enum Ignore {
        NO,
        YES,
        UNKNOWN
    }

    private MailPolicy() {
    }

    /**
     * @param sender    {@code null} for the console
     * @param recipient {@code null} when no player of that name is known
     */
    public static Outcome decide(@Nullable UUID sender, Predicate<String> permission, boolean muted,
                                 @Nullable UUID recipient, Ignore ignore) {
        return decide(sender, permission, muted, recipient, ignore, Acceptance.ACCEPTS);
    }

    public static Outcome decide(@Nullable UUID sender, Predicate<String> permission, boolean muted,
                                 @Nullable UUID recipient, Ignore ignore, Acceptance acceptance) {
        if (muted && sender != null) {
            return Outcome.SENDER_MUTED;
        }
        if (recipient == null) {
            return Outcome.NOT_FOUND;
        }
        if (recipient.equals(sender)) {
            return Outcome.SELF;
        }
        if (acceptance == Acceptance.UNAVAILABLE) return Outcome.UNAVAILABLE;
        if (sender != null) {
            switch (ignore) {
                case YES -> {
                    if (!permission.test(BYPASS_IGNORE)) {
                        return Outcome.CANNOT_RECEIVE;
                    }
                }
                case UNKNOWN -> {
                    if (!permission.test(BYPASS_IGNORE)) {
                        return Outcome.UNAVAILABLE;
                    }
                }
                default -> {
                }
            }
        }
        if (acceptance == Acceptance.REFUSES && !permission.test(BYPASS_TOGGLE)) return Outcome.CANNOT_RECEIVE;
        return Outcome.DELIVER;
    }
}
