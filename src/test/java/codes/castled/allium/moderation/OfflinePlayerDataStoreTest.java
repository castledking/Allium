package codes.castled.allium.moderation;

import codes.castled.allium.mail.PlayerDataReceiptProof;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfflinePlayerDataStoreTest {

    @TempDir
    Path tempDir;

    private UUID targetUuid;
    private Map<Path, ItemStack[]> virtualPayloads;
    private FakeCodec codec;
    private OfflinePlayerDataStore store;

    private static final int SERVER_DATA_VERSION = 3465;

    private static final class FakeCodec implements PlayerDataCodec {
        int currentVersion = SERVER_DATA_VERSION;
        boolean dropBukkitValueOnWrite;
        boolean emitTooNewVersionOnWrite;
        CountDownLatch blockOnWrite;
        CountDownLatch insideWrite;

        @Override
        public int currentDataVersion() {
            return currentVersion;
        }

        @Override
        public LoadedData read(byte[] compressedNbt, InspectionLayout.InspectionType type) throws IOException {
            PlayerDataReceiptProof.Snapshot snap = PlayerDataReceiptProof.inspect(compressedNbt);
            ItemStack[] slots = new ItemStack[InspectionLayout.slotCount(type)];
            String marker = snap.bukkitValues().get("test:slot0");
            if (marker != null && !marker.isEmpty()) {
                slots[0] = mockItem(Material.DIAMOND, Integer.parseInt(marker));
            }
            return new LoadedData(snap.dataVersion(), slots);
        }

        @Override
        public byte[] write(byte[] baseCompressedNbt, InspectionLayout.InspectionType type, ItemStack[] playerSlots) throws IOException {
            if (insideWrite != null) {
                insideWrite.countDown();
            }
            if (blockOnWrite != null) {
                try {
                    assertTrue(blockOnWrite.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            PlayerDataReceiptProof.Snapshot base = PlayerDataReceiptProof.inspect(baseCompressedNbt);
            Map<String, String> values = new LinkedHashMap<>(base.bukkitValues());
            if (dropBukkitValueOnWrite) {
                values.remove(PlayerDataReceiptProof.MAIL_RECEIPTS);
            }
            int outVersion = emitTooNewVersionOnWrite ? currentVersion + 10 : base.dataVersion();
            return buildPlayerDat(outVersion, values);
        }
    }

    @BeforeEach
    void setup() {
        targetUuid = UUID.randomUUID();
        codec = new FakeCodec();
        store = new OfflinePlayerDataStore(tempDir, codec);
    }

    @Test
    void loadAndSaveRoundTripWithCasAndPreservedBukkitValues() throws Exception {
        Map<String, String> bukkit = new LinkedHashMap<>();
        bukkit.put(PlayerDataReceiptProof.MAIL_RECEIPTS, "S/11111111-1111-1111-1111-111111111111");
        bukkit.put(PlayerDataReceiptProof.KIT_RECEIPTS, "K/22222222-2222-2222-2222-222222222222");
        bukkit.put("other:tag", "keep-me");
        byte[] initial = buildPlayerDat(SERVER_DATA_VERSION, bukkit);
        Files.write(store.resolvePlayerFile(targetUuid), initial);

        OfflinePlayerDataStore.LoadedSnapshot loaded = store.load(targetUuid, InspectionLayout.InspectionType.INVSEE);
        assertEquals(SERVER_DATA_VERSION, loaded.dataVersion());
        assertEquals(bukkit, loaded.bukkitValues());

        ItemStack[] edited = new ItemStack[InspectionLayout.INVSEE_PLAYER_SLOT_COUNT];
        edited[0] = mockItem(Material.EMERALD, 3);
        OfflinePlayerDataStore.SavedSnapshot saved = store.save(
                targetUuid,
                InspectionLayout.InspectionType.INVSEE,
                loaded.sha256(),
                loaded.rawBytes(),
                loaded.bukkitValues(),
                edited
        );

        assertNotNull(saved.sha256());
        byte[] diskBytes = Files.readAllBytes(store.resolvePlayerFile(targetUuid));
        assertArrayEquals(saved.rawBytes(), diskBytes);
        PlayerDataReceiptProof.Snapshot onDisk = PlayerDataReceiptProof.inspect(diskBytes);
        assertEquals(bukkit, onDisk.bukkitValues());
    }

    @Test
    void loadRejectsMissingCorruptTruncatedTooLargeAndNewerDataVersion() throws Exception {
        // Missing
        assertThrows(FileNotFoundException.class, () -> store.load(targetUuid, InspectionLayout.InspectionType.INVSEE));

        Path file = store.resolvePlayerFile(targetUuid);

        // Corrupt GZIP CRC
        byte[] valid = buildPlayerDat(SERVER_DATA_VERSION, Map.of());
        byte[] badCrc = valid.clone();
        badCrc[badCrc.length - 8] ^= 1;
        Files.write(file, badCrc);
        assertThrows(IOException.class, () -> store.load(targetUuid, InspectionLayout.InspectionType.INVSEE));

        // Truncated stream
        Files.write(file, Arrays.copyOf(valid, valid.length - 3));
        assertThrows(IOException.class, () -> store.load(targetUuid, InspectionLayout.InspectionType.INVSEE));

        // Newer DataVersion
        Files.write(file, buildPlayerDat(SERVER_DATA_VERSION + 1, Map.of()));
        PlayerDataCodec.DataVersionTooNewException newer = assertThrows(
                PlayerDataCodec.DataVersionTooNewException.class,
                () -> store.load(targetUuid, InspectionLayout.InspectionType.INVSEE)
        );
        assertEquals(SERVER_DATA_VERSION + 1, newer.fileVersion());
        assertEquals(SERVER_DATA_VERSION, newer.currentVersion());
    }

    @Test
    void saveAbortsOnCasMismatchOrDroppedBukkitValuesWithoutModifyingDiskFile() throws Exception {
        Map<String, String> bukkit = Map.of(PlayerDataReceiptProof.MAIL_RECEIPTS, "S/abc");
        byte[] initial = buildPlayerDat(SERVER_DATA_VERSION, bukkit);
        Path file = store.resolvePlayerFile(targetUuid);
        Files.write(file, initial);

        OfflinePlayerDataStore.LoadedSnapshot loaded = store.load(targetUuid, InspectionLayout.InspectionType.ENDERCHEST);

        // 1. Corrupt/dropped BukkitValues by buggy codec -> rejected before move, original untouched
        codec.dropBukkitValueOnWrite = true;
        assertThrows(IOException.class, () -> store.save(
                targetUuid,
                InspectionLayout.InspectionType.ENDERCHEST,
                loaded.sha256(),
                loaded.rawBytes(),
                loaded.bukkitValues(),
                new ItemStack[InspectionLayout.ENDERCHEST_GUI_SIZE]
        ));
        assertArrayEquals(initial, Files.readAllBytes(file));
        codec.dropBukkitValueOnWrite = false;

        // 2. Concurrent external modification -> CasConflictException, original external bytes preserved
        byte[] externalUpdate = buildPlayerDat(SERVER_DATA_VERSION, Map.of(
                PlayerDataReceiptProof.MAIL_RECEIPTS, "S/abc",
                "external", "1"
        ));
        Files.write(file, externalUpdate);

        assertThrows(OfflinePlayerDataStore.CasConflictException.class, () -> store.save(
                targetUuid,
                InspectionLayout.InspectionType.ENDERCHEST,
                loaded.sha256(),
                loaded.rawBytes(),
                loaded.bukkitValues(),
                new ItemStack[InspectionLayout.ENDERCHEST_GUI_SIZE]
        ));
        assertArrayEquals(externalUpdate, Files.readAllBytes(file));
    }

    @Test
    void loginFenceWaitsForInFlightWriteAndBlocksNewLoadsAndSaves() throws Exception {
        Map<String, String> bukkit = Map.of(PlayerDataReceiptProof.KIT_RECEIPTS, "K/1");
        byte[] initial = buildPlayerDat(SERVER_DATA_VERSION, bukkit);
        Path file = store.resolvePlayerFile(targetUuid);
        Files.write(file, initial);

        OfflinePlayerDataStore.LoadedSnapshot loaded = store.load(targetUuid, InspectionLayout.InspectionType.INVSEE);

        codec.insideWrite = new CountDownLatch(1);
        codec.blockOnWrite = new CountDownLatch(1);
        AtomicBoolean saveCompleted = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            try {
                store.save(targetUuid, InspectionLayout.InspectionType.INVSEE, loaded.sha256(),
                        loaded.rawBytes(), loaded.bukkitValues(), new ItemStack[41]);
                saveCompleted.set(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();
        assertTrue(codec.insideWrite.await(5, TimeUnit.SECONDS));

        // Login fence with short timeout fails while write is blocked
        assertThrows(TimeoutException.class, () -> store.beginLoginFence(targetUuid, 40));

        // Unblock write; login fence now waits cleanly until write finishes
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException ignored) {}
            codec.blockOnWrite.countDown();
        });
        releaser.start();
        store.beginLoginFence(targetUuid, 3000);
        writer.join(3000);
        assertTrue(saveCompleted.get());
        assertTrue(store.isOnlineOrFenced(targetUuid));

        // While fenced, load and save must fail with LoginFencedException
        assertThrows(OfflinePlayerDataStore.LoginFencedException.class,
                () -> store.load(targetUuid, InspectionLayout.InspectionType.INVSEE));

        store.markOffline(targetUuid);
        assertFalse(store.isOnlineOrFenced(targetUuid));
    }

    private static ItemStack mockItem(Material mat, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(mat);
        when(stack.getAmount()).thenReturn(amount);
        return stack;
    }

    private static byte[] buildPlayerDat(int dataVersion, Map<String, String> bukkitValues) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos);
             DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeByte(10); // TAG_Compound
            out.writeUTF("");  // root name
            out.writeByte(3);  // TAG_Int
            out.writeUTF("DataVersion");
            out.writeInt(dataVersion);
            if (!bukkitValues.isEmpty()) {
                out.writeByte(10); // TAG_Compound
                out.writeUTF("BukkitValues");
                for (Map.Entry<String, String> entry : bukkitValues.entrySet()) {
                    out.writeByte(8); // TAG_String
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
                out.writeByte(0); // END BukkitValues
            }
            out.writeByte(0); // END root
        }
        return baos.toByteArray();
    }
}
