package codes.castled.allium.messaging;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Whether a private message may be delivered, shared by {@code /msg} and {@code /reply} and
 * evaluated at delivery time (a reply re-checks everything; nothing is cached from the earlier
 * message).
 *
 * <p>Privacy rules: a recipient who toggled messages off and one who ignores the sender produce
 * the same {@link Outcome#CANNOT_RECEIVE}; a vanished recipient the sender may not see is
 * {@link Outcome#NOT_FOUND}, exactly like an offline player. Unloaded preferences or ignore lists
 * fail closed as {@link Outcome#UNAVAILABLE}. Staff bypass only through explicit permissions.
 */
public final class MessagePolicy {

    public static final String BYPASS_TOGGLE = "allium.msg.bypass.toggle";
    public static final String BYPASS_IGNORE = "allium.msg.bypass.ignore";
    public static final String SEE_VANISHED = "allium.vanish.see";

    public enum Outcome {
        DELIVER,
        /** The sender is muted (moderation). */
        SENDER_MUTED,
        /** Offline, or vanished from a sender who may not see them: indistinguishable to the sender. */
        NOT_FOUND,
        SELF,
        /** Toggled messages off, or ignores the sender: one generic message for both. */
        CANNOT_RECEIVE,
        /** The recipient's preferences or ignore list are not loaded: refuse, "try again shortly". */
        UNAVAILABLE
    }

    /** What the recipient's preferences say about accepting messages. */
    public enum Acceptance {
        ACCEPTS,
        REFUSES,
        UNAVAILABLE
    }

    /** Whether the recipient ignores the sender; {@code UNKNOWN} when their ignore list is not loaded. */
    public enum Ignore {
        NO,
        YES,
        UNKNOWN
    }

    /**
     * @param id         {@code null} for the console
     * @param permission the sender's permission check (the console has every permission)
     * @param muted      whether moderation mutes the sender (never true for the console)
     */
    public record Sender(@Nullable UUID id, Predicate<String> permission, boolean muted) {
        public Sender {
            Objects.requireNonNull(permission, "permission");
        }

        public boolean console() {
            return id == null;
        }
    }

    /**
     * @param online      whether the recipient is connected at all ({@code null} recipients are offline)
     * @param hiddenFromSender whether vanish hides the recipient from this sender (before any see-vanished permission)
     */
    public record Recipient(@Nullable UUID id, boolean online, boolean hiddenFromSender, Acceptance acceptance, Ignore ignoresSender) {
    }

    private MessagePolicy() {
    }

    public static Outcome decide(Sender sender, Recipient recipient) {
        if (sender.muted() && !sender.console()) {
            return Outcome.SENDER_MUTED;
        }
        if (!recipient.online() || (recipient.hiddenFromSender() && !sender.permission().test(SEE_VANISHED))) {
            return Outcome.NOT_FOUND;
        }
        if (recipient.id() != null && recipient.id().equals(sender.id())) {
            return Outcome.SELF;
        }
        if (!sender.console()) {
            switch (recipient.ignoresSender()) {
                case YES -> {
                    if (!sender.permission().test(BYPASS_IGNORE)) {
                        return Outcome.CANNOT_RECEIVE;
                    }
                }
                case UNKNOWN -> {
                    if (!sender.permission().test(BYPASS_IGNORE)) {
                        return Outcome.UNAVAILABLE;
                    }
                }
                default -> {
                }
            }
        }
        return switch (recipient.acceptance()) {
            case ACCEPTS -> Outcome.DELIVER;
            case REFUSES -> sender.permission().test(BYPASS_TOGGLE) ? Outcome.DELIVER : Outcome.CANNOT_RECEIVE;
            case UNAVAILABLE -> Outcome.UNAVAILABLE;
        };
    }
}
