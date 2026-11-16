package codes.castled.allium.moderation;

import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.Objects;

/**
 * Abstraction for reading and updating offline player {@code <uuid>.dat} inventory/enderchest NBT
 * while preserving all unrelated root tags and {@code BukkitValues}.
 */
public interface PlayerDataCodec {

    int currentDataVersion();

    LoadedData read(byte[] compressedNbt, InspectionLayout.InspectionType type) throws IOException;

    byte[] write(byte[] baseCompressedNbt, InspectionLayout.InspectionType type, ItemStack[] playerSlots) throws IOException;

    /**
     * Runs a synthetic in-memory round-trip proving inventory, ender chest, {@code DataVersion},
     * and {@code BukkitValues} serialization works on the current server runtime.
     */
    default void selfTest() throws IOException {
    }

    record LoadedData(int dataVersion, ItemStack[] slots) {
        public LoadedData {
            Objects.requireNonNull(slots, "slots");
        }
    }

    final class DataVersionTooNewException extends IOException {
        private final int fileVersion;
        private final int currentVersion;

        public DataVersionTooNewException(int fileVersion, int currentVersion) {
            super("Player data DataVersion " + fileVersion + " is newer than server DataVersion " + currentVersion);
            this.fileVersion = fileVersion;
            this.currentVersion = currentVersion;
        }

        public int fileVersion() {
            return fileVersion;
        }

        public int currentVersion() {
            return currentVersion;
        }
    }
}
