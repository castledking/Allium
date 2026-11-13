package codes.castled.allium.mail;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code allium_mail}.
 *
 * @param sender     {@code null} for the console or an import with no sender UUID
 * @param body       the text, or a gift's note; {@code null} for a gift without one
 * @param items      a gift's encoded items (the plugin's versioned item codec); {@code null} for text
 * @param itemCount  how many item stacks {@code items} holds (0 for text)
 * @param origin     the server ID that created the row; PENDING recovery is that server's
 * @param expiresAt  0 for never
 * @param source     {@code allium}, or an importer's name ({@code legacy-allium}, {@code essentials})
 * @param sourceRef  the importer's idempotency key; {@code null} for Allium's own mail
 */
public record MailRecord(UUID id, UUID recipient, @Nullable UUID sender, String senderName, MailKind kind,
                         @Nullable String body, @Nullable String items, int itemCount, MailState state,
                         @Nullable UUID claimToken, @Nullable String claimOwner, String origin, boolean unread,
                         long createdAt, long expiresAt, String source, @Nullable String sourceRef, long version) {

    public static final String SOURCE_ALLIUM = "allium";

    public MailRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(source, "source");
        if (kind == MailKind.GIFT && (items == null || itemCount < 1)) {
            throw new IllegalArgumentException("a gift carries at least one item stack");
        }
        if (kind == MailKind.TEXT && items != null) {
            throw new IllegalArgumentException("text mail carries no items");
        }
    }

    public static MailRecord text(UUID id, UUID recipient, @Nullable UUID sender, String senderName, String body,
                                  String origin, long now, long expiresAt) {
        return new MailRecord(id, recipient, sender, senderName, MailKind.TEXT, body, null, 0, MailState.READY,
                null, null, origin, true, now, expiresAt, SOURCE_ALLIUM, null, 0L);
    }

    /** A gift starts PENDING: its items have not durably left the sender yet. */
    public static MailRecord pendingGift(UUID id, UUID recipient, UUID sender, String senderName, @Nullable String note,
                                         String items, int itemCount, String origin, long now) {
        return new MailRecord(id, recipient, sender, senderName, MailKind.GIFT, note, items, itemCount, MailState.PENDING,
                null, null, origin, true, now, 0L, SOURCE_ALLIUM, null, 0L);
    }

    /** The first 8 hex digits of the ID, which players type ({@code /mail claim 3f2a91c0}). */
    public String shortId() {
        return id.toString().substring(0, 8);
    }

    public boolean expired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }
}
