package codes.castled.allium.item;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Platform-neutral server-secret HMAC-SHA256 signer and keyring verifier for privileged item
 * behavior identity and persisted {@code allium:state_*} values.
 *
 * <h3>Security Scope</h3>
 * <ul>
 *   <li>Prevents creative clients and unauthorized {@code /give} component syntax from minting
 *       privileged items or editing {@code allium:*} identity and {@code allium:state_*} tags
 *       (such as charges).</li>
 *   <li>Does <strong>not</strong> prevent creative-mode item stack duplication (copying an entire
 *       validly signed stack duplicates its {@code instance_uuid} and signature); granting
 *       privileged items to creative-mode players is a permission concern.</li>
 * </ul>
 *
 * <h3>Keyring & Rotation</h3>
 * <ul>
 *   <li>Signatures are formatted as {@code v2:<keyId>:<64-char-hex-hmac>}.
 *       The version identifies a length-prefixed payload; unsafe unversioned delimiter signatures
 *       require explicit migration and are never accepted or silently re-signed.</li>
 *   <li>The keyring holds one {@code activeKeyId} used for all new signatures and state mutations,
 *       plus zero or more {@code retired} keys that remain valid for verifying existing items.</li>
 *   <li>If an item carries an unknown {@code keyId}, {@link #verifyDetailed} returns
 *       {@link VerificationStatus#UNKNOWN_KEY_ID} so the caller can log a loud diagnostic rather
 *       than silently degrading items after a misconfigured migration or key rotation.</li>
 * </ul>
 */
public final class ItemHmacSigner {

    public static final String DEFAULT_KEY_ID = "k1";
    public static final String SIGNATURE_VERSION = "v2";
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int SECRET_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    public enum VerificationStatus {
        VALID,
        MISSING,
        UNKNOWN_KEY_ID,
        UNSUPPORTED_VERSION,
        INVALID_MAC
    }

    public record VerificationOutcome(
            VerificationStatus status,
            String keyId
    ) {
        public boolean isValid() {
            return status == VerificationStatus.VALID;
        }
    }

    private final String activeKeyId;
    private final Map<String, byte[]> keyring;

    public ItemHmacSigner(byte[] secretKeyBytes) {
        this(DEFAULT_KEY_ID, Map.of(DEFAULT_KEY_ID, secretKeyBytes));
    }

    public ItemHmacSigner(String activeKeyId, Map<String, byte[]> keyring) {
        Objects.requireNonNull(activeKeyId, "activeKeyId");
        Objects.requireNonNull(keyring, "keyring");
        String normActive = normalizeKeyId(activeKeyId);
        if (!isValidKeyId(normActive)) {
            throw new IllegalArgumentException("activeKeyId must use letters, digits, dots, underscores, or hyphens");
        }
        Map<String, byte[]> copied = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : keyring.entrySet()) {
            String kId = normalizeKeyId(entry.getKey());
            byte[] secret = entry.getValue();
            if (!isValidKeyId(kId) || secret == null || secret.length < 16) {
                throw new IllegalArgumentException("Each keyring entry must have a non-empty keyId and >= 16-byte secret");
            }
            if (copied.putIfAbsent(kId, secret.clone()) != null) {
                throw new IllegalArgumentException("Duplicate normalized keyId '" + kId + "'");
            }
        }
        if (!copied.containsKey(normActive)) {
            throw new IllegalArgumentException("keyring must contain activeKeyId '" + normActive + "'");
        }
        this.activeKeyId = normActive;
        this.keyring = Collections.unmodifiableMap(copied);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public Set<String> keyIds() {
        return keyring.keySet();
    }

    /**
     * Creates an ephemeral random signer with default key ID {@code k1} (useful for unit tests).
     */
    public static ItemHmacSigner randomEphemeral() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return new ItemHmacSigner(bytes);
    }

    /**
     * Creates an ephemeral random signer with the specified active key ID.
     */
    public static ItemHmacSigner randomEphemeral(String activeKeyId) {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        String norm = normalizeKeyId(activeKeyId);
        return new ItemHmacSigner(norm, Map.of(norm, bytes));
    }

    /**
     * Returns a new {@link ItemHmacSigner} that rotates to {@code newActiveKeyId} with
     * {@code newSecretBytes} while retaining all existing keys as retired verification keys.
     */
    public ItemHmacSigner rotateKey(String newActiveKeyId, byte[] newSecretBytes) {
        String normNew = normalizeKeyId(newActiveKeyId);
        if (keyring.containsKey(normNew)) {
            throw new IllegalArgumentException("Rotation requires a new keyId to preserve retired verification keys");
        }
        Map<String, byte[]> updated = new LinkedHashMap<>(this.keyring);
        updated.put(normNew, newSecretBytes.clone());
        return new ItemHmacSigner(normNew, updated);
    }

    /**
     * Loads the server HMAC keyring from {@code secretFile}, or generates and persists a new
     * 32-byte random secret with key ID {@code k1} if the file does not exist yet.
     *
     * <p>Supported file formats:
     * <ul>
     *   <li>Keyring format:
     *       <pre>
     *       active=k2:&lt;64-char-hex&gt;
     *       retired=k1:&lt;64-char-hex&gt;
     *       </pre>
     *   </li>
     *   <li>Single-line legacy hex format: {@code <64-char-hex>} (loaded as {@code active=k1:<hex>}).</li>
     * </ul>
     * Existing empty, malformed, or unusable files throw {@link IOException} without modification.
     * New files are created exclusively, with owner-only permissions on POSIX filesystems.
     */
    public static ItemHmacSigner loadOrGenerate(Path secretFile) throws IOException {
        Objects.requireNonNull(secretFile, "secretFile");
        try {
            return parseKeyringLines(Files.readAllLines(secretFile, StandardCharsets.UTF_8));
        } catch (NoSuchFileException missing) {
            // Only a missing file permits generation. Malformed or unreadable files must survive.
        }

        Path parent = secretFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        byte[] generated = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(generated);
        String encoded = HEX.formatHex(generated);

        String content = "# Allium Privileged Item HMAC Keyring (DO NOT SHARE PUBLICLY)" + System.lineSeparator()
                + "# Include this file in server backups and copy across backend servers that share item state." + System.lineSeparator()
                + "# To rotate keys: move the current active key to 'retired=<keyId>:<hex>' and set a new 'active=<newKeyId>:<hex>'." + System.lineSeparator()
                + "active=" + DEFAULT_KEY_ID + ":" + encoded + System.lineSeparator();

        Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            SeekableByteChannel channel;
            try {
                channel = Files.newByteChannel(secretFile, options, PosixFilePermissions.asFileAttribute(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } catch (UnsupportedOperationException nonPosix) {
                channel = Files.newByteChannel(secretFile, options);
            }
            try (SeekableByteChannel output = channel) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
            }
        } catch (FileAlreadyExistsException raced) {
            // Another process created the keyring since our read. Never truncate its secret.
            return parseKeyringLines(Files.readAllLines(secretFile, StandardCharsets.UTF_8));
        }

        return new ItemHmacSigner(DEFAULT_KEY_ID, Map.of(DEFAULT_KEY_ID, generated));
    }

    private static ItemHmacSigner parseKeyringLines(List<String> lines) throws IOException {
        List<String> entries = lines.stream().map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        if (entries.isEmpty()) {
            throw new IOException("Existing item HMAC keyring is empty; restore a valid keyring from backup");
        }
        if (entries.size() == 1 && !entries.get(0).contains("=")) {
            byte[] legacy = decodeHexSafe(entries.get(0));
            if (legacy != null && legacy.length >= 16) {
                return new ItemHmacSigner(legacy);
            }
            throw new IOException("Invalid legacy item HMAC secret; existing file was preserved");
        }

        String activeId = null;
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (String line : entries) {
            boolean isActive = line.startsWith("active=");
            if (!isActive && !line.startsWith("retired=")) {
                throw new IOException("Unrecognized item HMAC keyring entry; existing file was preserved");
            }
            String spec = line.substring(line.indexOf('=') + 1).trim();
            int colon = spec.indexOf(':');
            if (colon <= 0) {
                throw new IOException("Invalid item HMAC keyring entry; expected keyId:hexSecret");
            }
            String keyId = normalizeKeyId(spec.substring(0, colon));
            byte[] secret = decodeHexSafe(spec.substring(colon + 1).trim());
            if (!isValidKeyId(keyId) || secret == null || secret.length < 16) {
                throw new IOException("Invalid item HMAC key ID or secret; existing file was preserved");
            }
            if (keys.putIfAbsent(keyId, secret) != null) {
                throw new IOException("Duplicate item HMAC key ID '" + keyId + "'; existing file was preserved");
            }
            if (isActive) {
                if (activeId != null) {
                    throw new IOException("Item HMAC keyring must have exactly one active key");
                }
                activeId = keyId;
            }
        }
        if (activeId == null) {
            throw new IOException("Item HMAC keyring has no active key; restore it without discarding retired keys");
        }
        return new ItemHmacSigner(activeId, keys);
    }

    private static byte[] decodeHexSafe(String hex) {
        try {
            return HEX.parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Computes a keyed signature {@code v2:<activeKeyId>:<hex-hmac>} over
     * {@code (provider, itemId, behaviorId, instanceUuid)} with empty state.
     */
    public String sign(String provider, String itemId, String behaviorId, String instanceUuid) {
        return sign(provider, itemId, behaviorId, instanceUuid, Map.of());
    }

    /**
     * Computes a keyed signature {@code v2:<activeKeyId>:<hex-hmac>} over
     * {@code (provider, itemId, behaviorId, instanceUuid, canonicalState)}.
     */
    public String sign(
            String provider,
            String itemId,
            String behaviorId,
            String instanceUuid,
            Map<String, String> stateData
    ) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(behaviorId, "behaviorId");
        Objects.requireNonNull(instanceUuid, "instanceUuid");

        byte[] activeSecret = keyring.get(activeKeyId);
        byte[] macBytes = computeMac(activeSecret, activeKeyId, provider, itemId, behaviorId, instanceUuid, stateData);
        return SIGNATURE_VERSION + ":" + activeKeyId + ":" + HEX.formatHex(macBytes);
    }

    /**
     * Verifies a candidate signature over {@code (provider, itemId, behaviorId, instanceUuid)}
     * with empty state.
     */
    public boolean verify(
            String provider,
            String itemId,
            String behaviorId,
            String instanceUuid,
            String candidateSignature
    ) {
        return verifyDetailed(provider, itemId, behaviorId, instanceUuid, Map.of(), candidateSignature).isValid();
    }

    /**
     * Verifies a candidate signature over {@code (provider, itemId, behaviorId, instanceUuid, stateData)}.
     */
    public boolean verify(
            String provider,
            String itemId,
            String behaviorId,
            String instanceUuid,
            Map<String, String> stateData,
            String candidateSignature
    ) {
        return verifyDetailed(provider, itemId, behaviorId, instanceUuid, stateData, candidateSignature).isValid();
    }

    /**
     * Verifies a candidate signature in constant time and distinguishes missing signatures,
     * unknown key IDs, unsupported signature versions, and tampered/forged MACs.
     * Unsafe unversioned signatures are rejected even when their old MAC matches.
     */
    public VerificationOutcome verifyDetailed(
            String provider,
            String itemId,
            String behaviorId,
            String instanceUuid,
            Map<String, String> stateData,
            String candidateSignature
    ) {
        if (provider == null || provider.isBlank()
                || itemId == null || itemId.isBlank()
                || behaviorId == null || behaviorId.isBlank()
                || instanceUuid == null || instanceUuid.isBlank()
                || candidateSignature == null || candidateSignature.isBlank()) {
            return new VerificationOutcome(VerificationStatus.MISSING, null);
        }

        String[] parts = candidateSignature.trim().split(":", -1);
        if (!SIGNATURE_VERSION.equals(parts[0])) {
            String legacyKeyId = parts.length == 2 ? normalizeKeyId(parts[0])
                    : parts.length >= 3 ? normalizeKeyId(parts[1]) : null;
            return new VerificationOutcome(VerificationStatus.UNSUPPORTED_VERSION, legacyKeyId);
        }
        String keyId = parts.length > 1 ? normalizeKeyId(parts[1]) : null;
        if (parts.length != 3 || !isValidKeyId(keyId)) {
            return new VerificationOutcome(VerificationStatus.INVALID_MAC, keyId);
        }
        String hexPart = parts[2];

        byte[] secret = keyring.get(keyId);
        if (secret == null) {
            return new VerificationOutcome(VerificationStatus.UNKNOWN_KEY_ID, keyId);
        }

        if (hexPart.length() != 64) {
            return new VerificationOutcome(VerificationStatus.INVALID_MAC, keyId);
        }

        byte[] candidateBytes = decodeHexSafe(hexPart.toLowerCase(Locale.ROOT));
        if (candidateBytes == null) {
            return new VerificationOutcome(VerificationStatus.INVALID_MAC, keyId);
        }

        byte[] expectedBytes;
        try {
            expectedBytes = computeMac(secret, keyId, provider, itemId, behaviorId, instanceUuid, stateData);
        } catch (IllegalArgumentException invalidPayload) {
            return new VerificationOutcome(VerificationStatus.INVALID_MAC, keyId);
        }
        boolean matches = MessageDigest.isEqual(expectedBytes, candidateBytes);
        return new VerificationOutcome(
                matches ? VerificationStatus.VALID : VerificationStatus.INVALID_MAC,
                keyId
        );
    }

    /**
     * Encodes {@code allium:state_*} pairs as a count followed by key-sorted, length-prefixed
     * fields. Lengths count UTF-16 code units, so embedded NULs, colons, and equals signs cannot
     * create field boundaries. The MAC payload also length-prefixes every identity field.
     */
    public static String encodeCanonicalState(Map<String, String> stateData) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (stateData != null) {
            for (Map.Entry<String, String> entry : stateData.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
                    if (!key.isEmpty() && sorted.putIfAbsent(key, entry.getValue()) != null) {
                        throw new IllegalArgumentException("Duplicate normalized item state key '" + key + "'");
                    }
                }
            }
        }
        StringBuilder encoded = new StringBuilder().append(sorted.size()).append(':');
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            appendLengthPrefixed(encoded, entry.getKey());
            appendLengthPrefixed(encoded, entry.getValue());
        }
        return encoded.toString();
    }

    private static void appendLengthPrefixed(StringBuilder payload, String value) {
        payload.append(value.length()).append(':').append(value);
    }

    private static boolean isValidKeyId(String keyId) {
        return keyId != null && keyId.matches("[a-z0-9][a-z0-9_.-]*");
    }

    public static String normalizeKeyId(String keyId) {
        if (keyId == null) {
            return "";
        }
        return keyId.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] computeMac(
            byte[] secretKey,
            String keyId,
            String provider,
            String itemId,
            String behaviorId,
            String instanceUuid,
            Map<String, String> stateData
    ) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGO));
            StringBuilder payload = new StringBuilder();
            appendLengthPrefixed(payload, SIGNATURE_VERSION);
            appendLengthPrefixed(payload, keyId);
            appendLengthPrefixed(payload, provider.trim().toLowerCase(Locale.ROOT));
            // External provider IDs can be case sensitive; callers supply canonical ProviderKey IDs.
            appendLengthPrefixed(payload, itemId.trim());
            appendLengthPrefixed(payload, behaviorId.trim().toLowerCase(Locale.ROOT));
            appendLengthPrefixed(payload, instanceUuid.trim().toLowerCase(Locale.ROOT));
            appendLengthPrefixed(payload, encodeCanonicalState(stateData));
            // Reject malformed Unicode instead of collapsing distinct strings to replacement bytes.
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(payload));
            mac.update(encoded);
            return mac.doFinal();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Item signature fields must contain valid Unicode", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute HmacSHA256 for item signature", e);
        }
    }
}
