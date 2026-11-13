package codes.castled.allium.mail;

/**
 * Mail limits from {@code mail.*} in config.yml. Captured once per send, so a reload in the middle
 * of a send never changes the rules it is checked against.
 *
 * @param mailboxSize      most mails (text and gifts, not expired) a player's mailbox holds
 * @param sendsPerWindow   most mails one player may send per {@code windowMillis}
 * @param bodyMaxLength    longest text body or gift note, in characters
 * @param textExpiryMillis how long text mail lives; 0 for never. Gifts never expire: expiry must
 *                         never destroy items.
 * @param giftMaxItems     most items (summed amounts) one gift may carry
 */
public record MailLimits(int mailboxSize, int sendsPerWindow, long windowMillis, int bodyMaxLength,
                         long textExpiryMillis, int giftMaxItems) {

    public static final MailLimits DEFAULTS = new MailLimits(50, 10, 60_000L, 256, 30L * 24 * 3_600_000L, 64);

    public MailLimits {
        if (mailboxSize < 1 || mailboxSize > 10_000) {
            throw new IllegalArgumentException("mail.mailbox-size must be from 1 to 10000 (got " + mailboxSize + ")");
        }
        if (sendsPerWindow < 1 || sendsPerWindow > 10_000) {
            throw new IllegalArgumentException("mail.rate-limit.sends must be from 1 to 10000 (got " + sendsPerWindow + ")");
        }
        if (windowMillis < 1_000L || windowMillis > 86_400_000L) {
            throw new IllegalArgumentException("mail.rate-limit.per-seconds must be from 1 to 86400");
        }
        if (bodyMaxLength < 1 || bodyMaxLength > 4_096) {
            throw new IllegalArgumentException("mail.body-max-length must be from 1 to 4096 (got " + bodyMaxLength + ")");
        }
        if (textExpiryMillis < 0L) {
            throw new IllegalArgumentException("mail.text-expiry-days must not be negative");
        }
        if (giftMaxItems < 1 || giftMaxItems > 64 * 36) {
            throw new IllegalArgumentException("mail.gift.max-items must be from 1 to 2304 (got " + giftMaxItems + ")");
        }
    }

    public long expiresAt(long now) {
        return textExpiryMillis == 0L ? 0L : now + textExpiryMillis;
    }
}
