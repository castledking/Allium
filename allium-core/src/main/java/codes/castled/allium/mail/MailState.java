package codes.castled.allium.mail;

/**
 * Where a mail row is in its life.
 *
 * <ul>
 *   <li>{@code PENDING}: a gift whose items have not durably left the sender yet. The recipient
 *       never sees it. It becomes {@code READY} once the sender's inventory change is saved, or
 *       is deleted when it never was.</li>
 *   <li>{@code READY}: visible; a gift can be claimed.</li>
 *   <li>{@code CLAIMING}: one server owns a claim of this gift (a token guards it). It finishes
 *       (deleted, or back to {@code READY} with what did not fit) or is released.</li>
 * </ul>
 */
public enum MailState {
    PENDING,
    READY,
    CLAIMING
}
