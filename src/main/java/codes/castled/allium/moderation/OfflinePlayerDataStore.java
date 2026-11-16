package codes.castled.allium.moderation;

import codes.castled.allium.mail.PlayerDataReceiptProof;
import org.bukkit.inventory.ItemStack;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Atomic, CAS-checked, login-fenced store for offline {@code <uuid>.dat} inventory/enderchest NBT.
 *
 * <p>Every read validates the complete GZIP + NBT stream ({@code <= 8 MiB}, depth {@code <= 64},
 * valid trailer/CRC) and rejects files saved by a newer {@code DataVersion}. Every write uses a
 * sibling temporary file flushed via {@link FileChannel#force(boolean)}, pre-move read-back
 * verification (preserving all root {@code BukkitValues} entries including mail/kit receipts),
 * SHA-256 compare-and-swap, per-UUID login fencing, atomic rename, and post-move verification.
 */
public final class OfflinePlayerDataStore {

    public static final long MAX_COMPRESSED_BYTES = 8L * 1024L * 1024L;

    public record LoadedSnapshot(
            UUID uuid,
            int dataVersion,
            byte[] rawBytes,
            byte[] sha256,
            Map<String, String> bukkitValues,
            ItemStack[] slots
    ) {
    }

    public record SavedSnapshot(
            byte[] rawBytes,
            byte[] sha256
    ) {
    }

    public static final class CasConflictException extends IOException {
        public CasConflictException(String message) {
            super(message);
        }
    }

    public static final class LoginFencedException extends IOException {
        public LoginFencedException(UUID uuid) {
            super("Target player " + uuid + " is online or logging in");
        }
    }

    private static final class LockState {
        boolean online;
        boolean loginFenced;
        int pendingLoginFences;
        boolean ioActive;
        boolean writeInFlight;
    }

    private final Path playerDataDir;
    private final PlayerDataCodec codec;
    private final ConcurrentHashMap<UUID, LockState> locks = new ConcurrentHashMap<>();

    public OfflinePlayerDataStore(Path playerDataDir, PlayerDataCodec codec) {
        this.playerDataDir = Objects.requireNonNull(playerDataDir, "playerDataDir").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Path playerDataDir() {
        return playerDataDir;
    }

    /**
     * Resolves {@code <uuid>.dat} safely inside {@link #playerDataDir}.
     */
    public Path resolvePlayerFile(UUID uuid) throws IOException {
        Objects.requireNonNull(uuid, "uuid");
        Path resolved = playerDataDir.resolve(uuid + ".dat").normalize();
        if (!resolved.startsWith(playerDataDir) || !resolved.getParent().equals(playerDataDir)) {
            throw new IOException("Refusing invalid player data path for " + uuid);
        }
        return resolved;
    }

    /**
     * Fences offline reads/writes for {@code uuid} before CraftBukkit loads {@code <uuid>.dat} on
     * login, waiting up to {@code timeoutMillis} for any in-flight write to complete.
     */
    public void beginLoginFence(UUID uuid, long timeoutMillis) throws InterruptedException, TimeoutException {
        Objects.requireNonNull(uuid, "uuid");
        LockState state = lockState(uuid);
        long deadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
        synchronized (state) {
            state.pendingLoginFences++;
            try {
                while (state.writeInFlight) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0L) {
                        throw new TimeoutException("Timed out waiting for offline player data write for " + uuid);
                    }
                    long waitMs = Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
                    state.wait(waitMs);
                }
                state.loginFenced = true;
            } finally {
                state.pendingLoginFences--;
                state.notifyAll();
            }
        }
    }

    public void clearLoginFence(UUID uuid) {
        if (uuid == null) {
            return;
        }
        LockState state = locks.get(uuid);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (!state.online) {
                state.loginFenced = false;
                state.notifyAll();
            }
        }
    }

    public void markOnline(UUID uuid) {
        if (uuid == null) {
            return;
        }
        LockState state = lockState(uuid);
        synchronized (state) {
            state.online = true;
            state.loginFenced = false;
            state.notifyAll();
        }
    }

    public void markOffline(UUID uuid) {
        if (uuid == null) {
            return;
        }
        LockState state = locks.get(uuid);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.online = false;
            state.loginFenced = false;
            state.notifyAll();
        }
    }

    public boolean isOnlineOrFenced(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        LockState state = locks.get(uuid);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.online || state.loginFenced || state.pendingLoginFences > 0;
        }
    }

    public boolean isWriteInFlight(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        LockState state = locks.get(uuid);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.writeInFlight;
        }
    }

    public LoadedSnapshot load(UUID uuid, InspectionLayout.InspectionType type) throws IOException {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(type, "type");
        LockState state = lockState(uuid);
        synchronized (state) {
            while (state.ioActive) {
                if (state.loginFenced || state.online || state.pendingLoginFences > 0) {
                    throw new LoginFencedException(uuid);
                }
                try {
                    state.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for player data lock", e);
                }
            }
            if (state.loginFenced || state.online || state.pendingLoginFences > 0) {
                throw new LoginFencedException(uuid);
            }
            state.ioActive = true;
        }
        try {
            Path target = resolvePlayerFile(uuid);
            if (!Files.isRegularFile(target)) {
                throw new FileNotFoundException("Player data file does not exist: " + target.getFileName());
            }
            byte[] rawBytes = readBoundedFile(target);
            PlayerDataReceiptProof.Snapshot proof = PlayerDataReceiptProof.inspect(rawBytes);
            int currentVersion = codec.currentDataVersion();
            if (proof.dataVersion() > currentVersion) {
                throw new PlayerDataCodec.DataVersionTooNewException(proof.dataVersion(), currentVersion);
            }
            PlayerDataCodec.LoadedData decoded = codec.read(rawBytes, type);
            byte[] hash = sha256(rawBytes);
            return new LoadedSnapshot(uuid, proof.dataVersion(), rawBytes, hash, proof.bukkitValues(), decoded.slots());
        } finally {
            synchronized (state) {
                state.ioActive = false;
                state.notifyAll();
            }
        }
    }

    public SavedSnapshot save(
            UUID uuid,
            InspectionLayout.InspectionType type,
            byte[] expectedHash,
            byte[] baseRawBytes,
            Map<String, String> expectedBukkitValues,
            ItemStack[] editedSlots
    ) throws IOException {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(expectedHash, "expectedHash");
        Objects.requireNonNull(baseRawBytes, "baseRawBytes");
        Objects.requireNonNull(expectedBukkitValues, "expectedBukkitValues");
        Objects.requireNonNull(editedSlots, "editedSlots");

        LockState state = lockState(uuid);
        synchronized (state) {
            while (state.ioActive) {
                if (state.loginFenced || state.online || state.pendingLoginFences > 0) {
                    throw new LoginFencedException(uuid);
                }
                try {
                    state.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for player data lock", e);
                }
            }
            if (state.loginFenced || state.online || state.pendingLoginFences > 0) {
                throw new LoginFencedException(uuid);
            }
            state.ioActive = true;
            state.writeInFlight = true;
        }

        Path target = resolvePlayerFile(uuid);
        Path temp = playerDataDir.resolve(uuid + ".dat." + UUID.randomUUID() + ".tmp").normalize();
        if (!temp.startsWith(playerDataDir) || !temp.getParent().equals(playerDataDir)) {
            releaseWriteState(state);
            throw new IOException("Invalid temp path");
        }

        try {
            // 1. Pre-write CAS + source validation
            verifyTargetMatchesCas(target, expectedHash);

            // 2. Encode updated NBT from exact CAS-verified baseline bytes
            byte[] updatedBytes = codec.write(baseRawBytes, type, editedSlots);
            if (updatedBytes.length > MAX_COMPRESSED_BYTES) {
                throw new IOException("Updated player data exceeds compressed size bound");
            }
            PlayerDataReceiptProof.Snapshot memProof = PlayerDataReceiptProof.inspect(updatedBytes);
            verifyPreservedMetadata(memProof, expectedBukkitValues, codec.currentDataVersion(), "memory output");

            // 3. Write temp file and fsync
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(updatedBytes);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                channel.force(true);
            }

            // 4. Pre-move read-back verification from disk
            byte[] tempReadBack = readBoundedFile(temp);
            PlayerDataReceiptProof.Snapshot tempProof = PlayerDataReceiptProof.inspect(tempReadBack);
            verifyPreservedMetadata(tempProof, expectedBukkitValues, codec.currentDataVersion(), "temp read-back");
            codec.read(tempReadBack, type);

            // 5. Commit point synchronized with login fence
            synchronized (state) {
                if (state.loginFenced || state.online) {
                    throw new LoginFencedException(uuid);
                }
                verifyTargetMatchesCas(target, expectedHash);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException fallback) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                // 6. Post-move verification
                PlayerDataReceiptProof.Snapshot postMove = PlayerDataReceiptProof.inspect(target);
                verifyPreservedMetadata(postMove, expectedBukkitValues, codec.currentDataVersion(), "post-move");
            }

            return new SavedSnapshot(updatedBytes, sha256(updatedBytes));
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Best-effort cleanup of temp file
            }
            releaseWriteState(state);
        }
    }

    private void releaseWriteState(LockState state) {
        synchronized (state) {
            state.writeInFlight = false;
            state.ioActive = false;
            state.notifyAll();
        }
    }

    private static void verifyTargetMatchesCas(Path target, byte[] expectedHash) throws IOException {
        if (!Files.isRegularFile(target)) {
            throw new FileNotFoundException("Player data file disappeared: " + target.getFileName());
        }
        byte[] actualHash = sha256(readBoundedFile(target));
        if (!MessageDigest.isEqual(actualHash, expectedHash)) {
            throw new CasConflictException("Player data changed concurrently on disk for " + target.getFileName());
        }
    }

    private static void verifyPreservedMetadata(
            PlayerDataReceiptProof.Snapshot proof,
            Map<String, String> expectedBukkitValues,
            int currentDataVersion,
            String stage
    ) throws IOException {
        if (proof.dataVersion() > currentDataVersion) {
            throw new IOException("Invalid DataVersion after " + stage + ": " + proof.dataVersion());
        }
        if (!expectedBukkitValues.equals(proof.bukkitValues())) {
            throw new IOException("BukkitValues mismatch during " + stage + " verification");
        }
    }

    private static byte[] readBoundedFile(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed player data file exceeds " + MAX_COMPRESSED_BYTES + " bytes: " + size);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed player data file exceeds " + MAX_COMPRESSED_BYTES + " bytes");
        }
        return bytes;
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    private LockState lockState(UUID uuid) {
        return locks.computeIfAbsent(uuid, k -> new LockState());
    }
}
