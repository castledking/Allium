package codes.castled.allium.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerDataReceiptProofTest {

    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String SENT = "S/00000000-0000-0000-0000-000000000001";
    private static final String CLAIM = "C/00000000-0000-0000-0000-000000000002/"
            + "00000000-0000-0000-0000-000000000003/64,0,12";
    private static final String RECEIPT = SENT + ";" + CLAIM;

    @TempDir
    Path directory;

    @Test
    void acceptsTheExactFullReceiptInRootBukkitValues() throws IOException {
        Path playerDat = nbt(out -> receipt(out, RECEIPT));
        assertDoesNotThrow(() -> PlayerDataReceiptProof.verify(playerDat, RECEIPT));
        rejects(playerDat, SENT); // A matching entry alone is not the full fresh receipt.
        rejects(playerDat, RECEIPT.replace("000000000003", "000000000004"));
        rejects(playerDat, RECEIPT + ";S/00000000-0000-0000-0000-000000000005");
    }

    @Test
    void acceptsNamedRootAndModifiedUtfStrings() throws IOException {
        String expected = RECEIPT + ";X/\u0000/\uD83C\uDF3B";
        Path playerDat = gzip(out -> {
            header(out, 10, "named player root");
            string(out, "unrelated", "\u0000/\uD83C\uDF3B");
            receipt(out, expected);
            out.writeByte(0);
        });
        assertDoesNotThrow(() -> PlayerDataReceiptProof.verify(playerDat, expected));
    }

    @Test
    void rejectsMissingEmptyAndWrongTypedReceipts() throws IOException {
        rejects(nbt(out -> {}), RECEIPT);
        rejects(nbt(out -> {
            header(out, 10, "BukkitValues");
            out.writeByte(0);
        }), RECEIPT);
        rejects(nbt(out -> receipt(out, "")), RECEIPT);
        rejects(nbt(out -> string(out, "BukkitValues", RECEIPT)), RECEIPT);
        rejects(nbt(out -> {
            header(out, 10, "BukkitValues");
            header(out, 3, "allium:mail_receipts");
            out.writeInt(42);
            out.writeByte(0);
        }), RECEIPT);
        Path valid = nbt(out -> receipt(out, RECEIPT));
        rejects(valid, "");
        rejects(valid, null);
    }

    @Test
    void rejectsReceiptKeysOutsideTheExactRootCompoundPath() throws IOException {
        rejects(nbt(out -> string(out, "allium:mail_receipts", RECEIPT)), RECEIPT);
        rejects(nbt(out -> {
            header(out, 10, "bukkit");
            string(out, "allium:mail_receipts", RECEIPT);
            out.writeByte(0);
        }), RECEIPT);
        rejects(nbt(out -> {
            header(out, 10, "other");
            receipt(out, RECEIPT);
            out.writeByte(0);
        }), RECEIPT);
        rejects(nbt(out -> {
            header(out, 10, "BukkitValues");
            header(out, 10, "nested");
            string(out, "allium:mail_receipts", RECEIPT);
            out.writeByte(0);
            out.writeByte(0);
        }), RECEIPT);
        rejects(nbt(out -> {
            header(out, 9, "players");
            out.writeByte(10);
            out.writeInt(1);
            receipt(out, RECEIPT);
            out.writeByte(0);
        }), RECEIPT);
    }

    @Test
    void unrelatedMatchingKeysCannotOverrideTheActualReceipt() throws IOException {
        rejects(nbt(out -> {
            string(out, "allium:mail_receipts", RECEIPT);
            receipt(out, "stale");
        }), RECEIPT);
        Path valid = nbt(out -> {
            string(out, "allium:mail_receipts", "stale");
            receipt(out, RECEIPT);
            header(out, 10, "other");
            receipt(out, "stale");
            out.writeByte(0);
        });
        assertDoesNotThrow(() -> PlayerDataReceiptProof.verify(valid, RECEIPT));
    }

    @Test
    void rejectsAmbiguousDuplicateProofKeys() throws IOException {
        rejects(nbt(out -> {
            receipt(out, RECEIPT);
            receipt(out, RECEIPT);
        }), RECEIPT);
        rejects(nbt(out -> {
            header(out, 10, "BukkitValues");
            string(out, "allium:mail_receipts", RECEIPT);
            string(out, "allium:mail_receipts", RECEIPT);
            out.writeByte(0);
        }), RECEIPT);
    }

    @Test
    void skipsEveryScalarArrayAndListPayloadBeforeAndAfterTheReceipt() throws IOException {
        Path playerDat = nbt(out -> {
            allPayloads(out, "before");
            receipt(out, RECEIPT);
            allPayloads(out, "after");
        });
        assertDoesNotThrow(() -> PlayerDataReceiptProof.verify(playerDat, RECEIPT));
    }

    @Test
    void rejectsTruncatedNbtEvenAfterFindingTheReceipt() throws IOException {
        rejects(gzip(out -> {
            header(out, 10, "");
            receipt(out, RECEIPT);
            // Missing root END, despite a complete GZIP stream.
        }), RECEIPT);
        for (int type : new int[]{7, 11, 12}) {
            rejects(gzip(out -> {
                header(out, 10, "");
                receipt(out, RECEIPT);
                header(out, type, "truncated array");
                out.writeInt(3);
                out.writeByte(1);
            }), RECEIPT);
        }
        rejects(gzip(out -> {
            header(out, 10, "");
            receipt(out, RECEIPT);
            header(out, 8, "truncated string");
            out.writeShort(5);
            out.writeByte('x');
        }), RECEIPT);
    }

    @Test
    void rejectsTruncatedOrCorruptGzipAndUncompressedNbt() throws IOException {
        Path playerDat = nbt(out -> receipt(out, RECEIPT));
        byte[] compressed = Files.readAllBytes(playerDat);
        Path truncated = directory.resolve("truncated.dat");
        Files.write(truncated, Arrays.copyOf(compressed, compressed.length - 1));
        rejects(truncated, RECEIPT);
        compressed[compressed.length - 8] ^= 1; // Corrupt CRC after otherwise valid NBT.
        Path corrupt = directory.resolve("corrupt.dat");
        Files.write(corrupt, compressed);
        rejects(corrupt, RECEIPT);
        Path plain = directory.resolve("plain.dat");
        Files.write(plain, new byte[]{10, 0, 0, 0});
        rejects(plain, RECEIPT);
        rejects(directory.resolve("missing.dat"), RECEIPT);
    }

    @Test
    void rejectsMalformedUnsupportedAndTrailingNbtAfterAValidProof() throws IOException {
        rejects(gzip(out -> out.writeByte(8)), RECEIPT);
        rejects(nbt(out -> {
            receipt(out, RECEIPT);
            out.writeByte(13);
        }), RECEIPT);
        rejects(nbt(out -> {
            receipt(out, RECEIPT);
            header(out, 8, "bad UTF");
            out.writeShort(1);
            out.writeByte(0x80);
        }), RECEIPT);
        rejects(gzip(out -> {
            header(out, 10, "");
            receipt(out, RECEIPT);
            out.writeByte(0);
            out.writeByte(0);
        }), RECEIPT);
        for (int type : new int[]{7, 9, 11, 12}) {
            rejects(nbt(out -> {
                receipt(out, RECEIPT);
                header(out, type, "negative length");
                if (type == 9) {
                    out.writeByte(1);
                }
                out.writeInt(-1);
            }), RECEIPT);
        }
        for (int elementType : new int[]{0, 13, 255}) {
            rejects(nbt(out -> {
                receipt(out, RECEIPT);
                header(out, 9, "invalid list type");
                out.writeByte(elementType);
                out.writeInt(elementType == 0 ? 1 : 0);
            }), RECEIPT);
        }
    }

    @Test
    void acceptsExactlyEightMiBAndRejectsOneAdditionalExpandedByte() throws IOException {
        int overhead = raw(out -> {
            header(out, 10, "");
            receipt(out, RECEIPT);
            byteArray(out, 0);
            out.writeByte(0);
        }).length;
        Path atLimit = nbt(out -> {
            receipt(out, RECEIPT);
            byteArray(out, MAX_BYTES - overhead);
        });
        assertDoesNotThrow(() -> PlayerDataReceiptProof.verify(atLimit, RECEIPT));
        rejects(nbt(out -> {
            receipt(out, RECEIPT);
            byteArray(out, MAX_BYTES - overhead + 1);
        }), RECEIPT);
    }

    @Test
    void rejectsHugeDeclaredArrayAndListSizesWithoutAllocatingTheirPayloads() throws IOException {
        for (int type : new int[]{7, 9, 11, 12}) {
            rejects(nbt(out -> {
                receipt(out, RECEIPT);
                header(out, type, "huge");
                if (type == 9) {
                    out.writeByte(12);
                }
                out.writeInt(Integer.MAX_VALUE);
            }), RECEIPT);
        }
    }

    @Test
    void boundsBothCompoundAndListNestingAtSixtyFourContainersIncludingRoot() throws IOException {
        Path compoundsAtLimit = nestedCompounds(63);
        assertDoesNotThrow(() -> PlayerDataReceiptProof.verify(compoundsAtLimit, RECEIPT));
        rejects(nestedCompounds(64), RECEIPT);
        Path listsAtLimit = nestedLists(63);
        assertDoesNotThrow(() -> PlayerDataReceiptProof.verify(listsAtLimit, RECEIPT));
        rejects(nestedLists(64), RECEIPT);
    }

    private Path nestedCompounds(int count) throws IOException {
        return nbt(out -> {
            receipt(out, RECEIPT);
            for (int i = 0; i < count; i++) {
                header(out, 10, "nested");
            }
            for (int i = 0; i < count; i++) {
                out.writeByte(0);
            }
        });
    }

    private Path nestedLists(int count) throws IOException {
        return nbt(out -> {
            receipt(out, RECEIPT);
            header(out, 9, "nested");
            for (int i = 1; i < count; i++) {
                out.writeByte(9);
                out.writeInt(1);
            }
            out.writeByte(0);
            out.writeInt(0);
        });
    }

    private static void allPayloads(DataOutputStream out, String prefix) throws IOException {
        for (int type = 1; type <= 12; type++) {
            header(out, type, prefix + "tag" + type);
            payload(out, type);
        }
        // END is legal as the element type of an empty list only.
        header(out, 9, prefix + "empty");
        out.writeByte(0);
        out.writeInt(0);
        for (int type = 1; type <= 12; type++) {
            header(out, 9, prefix + "list" + type);
            out.writeByte(type);
            out.writeInt(2);
            payload(out, type);
            payload(out, type);
        }
    }

    private static void payload(DataOutputStream out, int type) throws IOException {
        switch (type) {
            case 1 -> out.writeByte(42);
            case 2 -> out.writeShort(42);
            case 3 -> out.writeInt(42);
            case 4 -> out.writeLong(42);
            case 5 -> out.writeFloat(42);
            case 6 -> out.writeDouble(42);
            case 7 -> {
                out.writeInt(3);
                out.write(new byte[]{0, 10, 12});
            }
            case 8 -> out.writeUTF("unrelated string");
            case 9 -> {
                out.writeByte(8);
                out.writeInt(2);
                out.writeUTF("first");
                out.writeUTF("second");
            }
            case 10 -> {
                // A valid-looking proof in a skipped compound is not authoritative.
                receipt(out, "stale");
                out.writeByte(0);
            }
            case 11 -> {
                out.writeInt(3);
                out.writeInt(0);
                out.writeInt(Integer.MIN_VALUE);
                out.writeInt(Integer.MAX_VALUE);
            }
            case 12 -> {
                out.writeInt(3);
                out.writeLong(0);
                out.writeLong(Long.MIN_VALUE);
                out.writeLong(Long.MAX_VALUE);
            }
            default -> throw new AssertionError("Unexpected fixture type: " + type);
        }
    }

    private static void byteArray(DataOutputStream out, int length) throws IOException {
        header(out, 7, "padding");
        out.writeInt(length);
        byte[] chunk = new byte[8192];
        while (length > 0) {
            int bytes = Math.min(length, chunk.length);
            out.write(chunk, 0, bytes);
            length -= bytes;
        }
    }

    private static void receipt(DataOutputStream out, String value) throws IOException {
        header(out, 10, "BukkitValues");
        string(out, "allium:mail_receipts", value);
        out.writeByte(0);
    }

    private static void string(DataOutputStream out, String key, String value) throws IOException {
        header(out, 8, key);
        out.writeUTF(value);
    }

    private static void header(DataOutputStream out, int type, String name) throws IOException {
        out.writeByte(type);
        out.writeUTF(name);
    }

    private Path nbt(NbtWriter body) throws IOException {
        return gzip(out -> {
            header(out, 10, "");
            body.write(out);
            out.writeByte(0);
        });
    }

    private Path gzip(NbtWriter writer) throws IOException {
        Path path = Files.createTempFile(directory, "player-", ".dat");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            writer.write(out);
        }
        return path;
    }

    private static byte[] raw(NbtWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        }
        return bytes.toByteArray();
    }

    private static void rejects(Path path, String expected) {
        assertThrows(IOException.class, () -> PlayerDataReceiptProof.verify(path, expected));
    }

    @FunctionalInterface
    private interface NbtWriter {
        void write(DataOutputStream out) throws IOException;
    }
}
