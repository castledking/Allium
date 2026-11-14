package codes.castled.allium.mail;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Reads back a receipt from the same compressed player data that contains the inventory. */
public final class PlayerDataReceiptProof {

    private static final long MAX_EXPANDED_BYTES = 8L * 1024 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final String BUKKIT_VALUES = "BukkitValues";
    public static final String MAIL_RECEIPTS = "allium:mail_receipts";
    public static final String KIT_RECEIPTS = "allium:kit_receipts";

    private PlayerDataReceiptProof() {
    }

    /**
     * Verifies the entire freshly written mail receipt string in root {@code BukkitValues}.
     * Call off the server thread, holding the player's mail lock until verification completes.
     * This proves that the file can be read back, not that a power loss cannot lose the write.
     *
     * @throws IOException if the file is invalid, exceeds the limits, or lacks the exact receipt
     */
    public static void verify(Path playerDat, String expected) throws IOException {
        verify(playerDat, MAIL_RECEIPTS, expected);
    }

    /**
     * Verifies the entire freshly written receipt string under {@code receiptKey} in root {@code BukkitValues}.
     *
     * @throws IOException if the file is invalid, exceeds the limits, or lacks the exact receipt
     */
    public static void verify(Path playerDat, String receiptKey, String expected) throws IOException {
        if (receiptKey == null || receiptKey.isEmpty()) {
            throw new IOException("Player data receipt key must not be empty");
        }
        if (expected == null || expected.isEmpty()) {
            throw new IOException("Expected player data receipt must not be empty");
        }
        try (InputStream file = Files.newInputStream(playerDat);
             InputStream gzip = new GZIPInputStream(new BufferedInputStream(file));
             LimitedInputStream limited = new LimitedInputStream(gzip);
             DataInputStream input = new DataInputStream(limited)) {
            new Reader(input, limited, receiptKey, expected).verify();
        }
    }

    private enum Location { ROOT, BUKKIT_VALUES, OTHER }

    private static final class Reader {
        private final DataInputStream input;
        private final LimitedInputStream limited;
        private final String receiptKey;
        private final String expected;
        private final byte[] scratch = new byte[8192];
        private boolean foundBukkitValues;
        private boolean foundReceipt;

        private Reader(DataInputStream input, LimitedInputStream limited, String receiptKey, String expected) {
            this.input = input;
            this.limited = limited;
            this.receiptKey = receiptKey;
            this.expected = expected;
        }

        private void verify() throws IOException {
            if (input.readUnsignedByte() != 10) {
                throw new IOException("Player data root must be an NBT compound");
            }
            input.readUTF(); // The named root is normally unnamed; NBT uses modified UTF-8.
            compound(1, Location.ROOT);
            // Do not stop at the proof: validate remaining NBT and the GZIP checksum/trailer.
            if (input.read() != -1) {
                throw new IOException("Trailing data after player data root");
            }
            if (!foundReceipt) {
                throw new IOException("Player data receipt is missing");
            }
        }

        private void compound(int depth, Location location) throws IOException {
            checkDepth(depth);
            while (true) {
                int type = input.readUnsignedByte();
                if (type == 0) {
                    return;
                }
                checkType(type);
                String name = input.readUTF();
                if (location == Location.ROOT && BUKKIT_VALUES.equals(name)) {
                    if (foundBukkitValues || type != 10) {
                        throw new IOException("Invalid or duplicate player BukkitValues compound");
                    }
                    foundBukkitValues = true;
                    compound(depth + 1, Location.BUKKIT_VALUES);
                } else if (location == Location.BUKKIT_VALUES && receiptKey.equals(name)) {
                    if (foundReceipt || type != 8) {
                        throw new IOException("Invalid or duplicate player data receipt");
                    }
                    String actual = input.readUTF();
                    if (actual.isEmpty() || !expected.equals(actual)) {
                        throw new IOException("Player data receipt does not match the saved receipt");
                    }
                    foundReceipt = true;
                } else {
                    payload(type, depth);
                }
            }
        }

        private void payload(int type, int parentDepth) throws IOException {
            switch (type) {
                case 1 -> skip(1);
                case 2 -> skip(2);
                case 3, 5 -> skip(4);
                case 4, 6 -> skip(8);
                case 7 -> array(1);
                case 8 -> input.readUTF();
                case 9 -> list(parentDepth + 1);
                case 10 -> compound(parentDepth + 1, Location.OTHER);
                case 11 -> array(4);
                case 12 -> array(8);
                default -> throw new IOException("Unsupported NBT payload type: " + type);
            }
        }

        private void array(int elementBytes) throws IOException {
            skip((long) length() * elementBytes);
        }

        private void list(int depth) throws IOException {
            checkDepth(depth);
            int type = input.readUnsignedByte();
            checkType(type);
            int length = length();
            if (type == 0 && length != 0) {
                throw new IOException("Nonempty NBT list has END element type");
            }
            int fixedBytes = switch (type) {
                case 1 -> 1;
                case 2 -> 2;
                case 3, 5 -> 4;
                case 4, 6 -> 8;
                default -> 0;
            };
            if (fixedBytes != 0) {
                skip((long) length * fixedBytes);
            } else {
                // Every non-END element needs at least one byte, even an empty compound.
                limited.requireAvailable(length);
                for (int i = 0; i < length; i++) {
                    payload(type, depth);
                }
            }
        }

        private int length() throws IOException {
            int length = input.readInt();
            if (length < 0) {
                throw new IOException("Negative NBT array or list length");
            }
            return length;
        }

        private void skip(long bytes) throws IOException {
            limited.requireAvailable(bytes);
            while (bytes > 0) {
                int chunk = (int) Math.min(bytes, scratch.length);
                input.readFully(scratch, 0, chunk);
                bytes -= chunk;
            }
        }

        private static void checkType(int type) throws IOException {
            if (type < 0 || type > 12) {
                throw new IOException("Unsupported NBT type: " + type);
            }
        }

        private static void checkDepth(int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("Player data NBT nesting exceeds " + MAX_DEPTH);
            }
        }
    }

    /** Counts all expanded bytes, including skipped payloads; never allocates from NBT lengths. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream source;
        private long remaining = MAX_EXPANDED_BYTES;

        private LimitedInputStream(InputStream source) {
            this.source = source;
        }

        private void requireAvailable(long bytes) throws IOException {
            if (bytes > remaining) {
                throw new IOException("Player data exceeds " + MAX_EXPANDED_BYTES + " expanded bytes");
            }
        }

        @Override
        public int read() throws IOException {
            int value = source.read();
            if (value != -1) {
                requireAvailable(1);
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = source.read(b, off, len);
            if (count > 0) {
                requireAvailable(count);
                remaining -= count;
            }
            return count;
        }
    }
}
