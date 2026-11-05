package codes.castled.allium.item;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
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
 *   <li>Signatures are formatted as {@code <keyId>:<64-char-hex-hmac>} (e.g. {@code k1:9f86...}).</li>
 *   <li>The keyring holds one {@code activeKeyId} used for all new signatures and state mutations,
 *       plus zero or more {@code retired} keys that remain valid for verifying existing items.</li>
 *   <li>If an item carries an unknown {@code keyId}, {@link #verifyDetailed} returns
 *       {@link VerificationStatus#UNKNOWN_KEY_ID} so the caller can log a loud diagnostic rather
 *       than silently degrading items after a misconfigured migration or key rotation.</li>
 * </ul>
 */
public final class ItemHmacSigner {

    public static final String DEFAULT_KEY_ID = "k1";
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int SECRET_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    public enum VerificationStatus {
        VALID,
        MISSING,
        UNKNOWN_KEY_ID,
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
        if (normActive.isEmpty()) {
            throw new IllegalArgumentException("activeKeyId must not be empty");
        }
        Map<String, byte[]> copied = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : keyring.entrySet()) {
            String kId = normalizeKeyId(entry.getKey());
            byte[] secret = entry.getValue();
            if (kId.isEmpty() || secret == null || secret.length < 16) {
                throw new IllegalArgumentException("Each keyring entry must have a non-empty keyId and >= 16-byte secret");
            }
            copied.put(kId, secret.clone());
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
        if (normNew.isEmpty()) {
            throw new IllegalArgumentException("newActiveKeyId must not be empty");
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
     */
    public static ItemHmacSigner loadOrGenerate(Path secretFile) throws IOException {
        Objects.requireNonNull(secretFile, "secretFile");
        if (Files.exists(secretFile)) {
            List<String> lines = Files.readAllLines(secretFile, StandardCharsets.UTF_8);
            ItemHmacSigner parsed = parseKeyringLines(lines);
            if (parsed != null) {
                return parsed;
            }
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

        Files.writeString(
                secretFile,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        try {
            Files.setPosixFilePermissions(
                    secretFile,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            );
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem
        }

        return new ItemHmacSigner(DEFAULT_KEY_ID, Map.of(DEFAULT_KEY_ID, generated));
    }

    private static ItemHmacSigner parseKeyringLines(List<String> lines) {
        String activeId = null;
        Map<String, byte[]> keys = new LinkedHashMap<>();

        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("active=") || line.startsWith("retired=")) {
                boolean isActive = line.startsWith("active=");
                String spec = line.substring(line.indexOf('=') + 1).trim();
                int colon = spec.indexOf(':');
                if (colon <= 0 || colon == spec.length() - 1) {
                    continue;
                }
                String kId = normalizeKeyId(spec.substring(0, colon));
                String hex = spec.substring(colon + 1).trim();
                byte[] decoded = decodeHexSafe(hex);
                if (!kId.isEmpty() && decoded != null && decoded.length >= 16) {
                    keys.put(kId, decoded);
                    if (isActive) {
                        activeId = kId;
                    }
                }
            } else {
                // Single-line bare hex secret compatibility
                byte[] decoded = decodeHexSafe(line);
                if (decoded != null && decoded.length >= 16) {
                    keys.put(DEFAULT_KEY_ID, decoded);
                    if (activeId == null) {
                        activeId = DEFAULT_KEY_ID;
                    }
                }
            }
        }

        if (activeId != null && keys.containsKey(activeId)) {
            return new ItemHmacSigner(activeId, keys);
        }
        return null;
    }

    private static byte[] decodeHexSafe(String hex) {
        try {
            return HEX.parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Computes a keyed signature {@code <activeKeyId>:<hex-hmac>} over
     * {@code (provider, itemId, behaviorId, instanceUuid)} with empty state.
     */
    public String sign(String provider, String itemId, String behaviorId, String instanceUuid) {
        return sign(provider, itemId, behaviorId, instanceUuid, Map.of());
    }

    /**
     * Computes a keyed signature {@code <activeKeyId>:<hex-hmac>} over
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
        byte[] macBytes = computeMac(activeSecret, provider, itemId, behaviorId, instanceUuid, stateData);
        return activeKeyId + ":" + HEX.formatHex(macBytes);
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
     * unknown key IDs, and tampered/forged MACs.
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

        String trimmedSig = candidateSignature.trim();
        String keyId;
        String hexPart;
        int colon = trimmedSig.indexOf(':');
        if (colon > 0) {
            keyId = normalizeKeyId(trimmedSig.substring(0, colon));
            hexPart = trimmedSig.substring(colon + 1).trim();
        } else {
            keyId = DEFAULT_KEY_ID;
            hexPart = trimmedSig;
        }

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

        byte[] expectedBytes = computeMac(secret, provider, itemId, behaviorId, instanceUuid, stateData);
        boolean matches = MessageDigest.isEqual(expectedBytes, candidateBytes);
        return new VerificationOutcome(
                matches ? VerificationStatus.VALID : VerificationStatus.INVALID_MAC,
                keyId
        );
    }

    /**
     * Encodes {@code allium:state_*} key/value pairs in a canonical, key-sorted format.
     */
    public static String encodeCanonicalState(Map<String, String> stateData) {
        if (stateData == null || stateData.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : stateData.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                String k = entry.getKey().trim().toLowerCase(Locale.ROOT);
                if (!k.isEmpty()) {
                    sorted.put(k, entry.getValue());
                }
            }
        }
        if (sorted.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) {
                sb.append('\0');
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    public static String normalizeKeyId(String keyId) {
        if (keyId == null) {
            return "";
        }
        return keyId.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] computeMac(
            byte[] secretKey,
            String provider,
            String itemId,
            String behaviorId,
            String instanceUuid,
            Map<String, String> stateData
    ) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGO));
            String payload = provider.trim().toLowerCase(Locale.ROOT)
                    + '\0' + itemId.trim().toLowerCase(Locale.ROOT)
                    + '\0' + behaviorId.trim().toLowerCase(Locale.ROOT)
                    + '\0' + instanceUuid.trim().toLowerCase(Locale.ROOT)
                    + '\0' + encodeCanonicalState(stateData);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute HmacSHA256 for item signature", e);
        }
    }
}
