package codes.castled.allium.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The mail receipts a player carries in their own persistent data, saved in the same playerdata
 * file as their inventory. That makes each inventory change and its receipt durable together:
 * after a crash, the inventory on disk either has the change and its receipt, or neither.
 *
 * <ul>
 *   <li>{@code S/<mail>}: the items of gift {@code mail} left this inventory. Recovery activates the
 *       gift (PENDING to READY); without the receipt, the items never left and the PENDING row is
 *       deleted instead.</li>
 *   <li>{@code C/<mail>/<token>/<a0,a1,...>}: claim {@code token} of gift {@code mail} put
 *       {@code a_i} of item stack {@code i} into this inventory. Recovery finishes the claim with
 *       what was not delivered; without the receipt, nothing was delivered and the claim is
 *       released.</li>
 * </ul>
 *
 * <p>Entries are separated by {@code ;}. Unknown entries are kept untouched (a newer Allium's).
 */
public final class MailReceipts {

    public sealed interface Entry permits Sent, Claimed, Unknown {
        String encode();
    }

    public record Sent(UUID mail) implements Entry {
        public Sent {
            Objects.requireNonNull(mail, "mail");
        }

        @Override
        public String encode() {
            return "S/" + mail;
        }
    }

    /** @param delivered how many of each stored stack went into the inventory, by stack index */
    public record Claimed(UUID mail, UUID token, List<Integer> delivered) implements Entry {
        public Claimed {
            Objects.requireNonNull(mail, "mail");
            Objects.requireNonNull(token, "token");
            delivered = List.copyOf(delivered);
            for (int amount : delivered) {
                if (amount < 0) {
                    throw new IllegalArgumentException("a delivered amount is never negative");
                }
            }
        }

        @Override
        public String encode() {
            StringBuilder out = new StringBuilder("C/").append(mail).append('/').append(token).append('/');
            for (int i = 0; i < delivered.size(); i++) {
                out.append(i == 0 ? "" : ",").append(delivered.get(i));
            }
            return out.toString();
        }
    }

    public record Unknown(String raw) implements Entry {
        @Override
        public String encode() {
            return raw;
        }
    }

    private MailReceipts() {
    }

    public static List<Entry> parse(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(";")) {
            if (!part.isBlank()) {
                out.add(parseOne(part.trim()));
            }
        }
        return out;
    }

    private static Entry parseOne(String part) {
        String[] f = part.split("/", -1);
        try {
            if (f[0].equals("S") && f.length == 2) {
                return new Sent(UUID.fromString(f[1]));
            }
            if (f[0].equals("C") && f.length == 4) {
                List<Integer> amounts = new ArrayList<>();
                if (!f[3].isEmpty()) {
                    for (String a : f[3].split(",")) {
                        amounts.add(Integer.parseInt(a));
                    }
                }
                return new Claimed(UUID.fromString(f[1]), UUID.fromString(f[2]), amounts);
            }
        } catch (IllegalArgumentException ignored) {
            // falls through: kept as-is
        }
        return new Unknown(part);
    }

    public static String format(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry e : entries) {
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(e.encode());
        }
        return out.toString();
    }
}
