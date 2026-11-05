package codes.castled.allium.item;

import org.jetbrains.annotations.Nullable;

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
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Platform-neutral server-secret HMAC-SHA256 signer and verifier for privileged item behavior identity.
 *
 * <p>Protects privileged items (such as Handcuffs and Phantom Obliterator) against creative-client
 * PDC spoofing or unauthorized {@code /give} component injection. The secret is generated on first
 * run and stored in its own file outside {@code config.yml} so it is never shared when operators post
 * configuration files.
 */
public final class ItemHmacSigner {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int SECRET_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secretKeyBytes;

    public ItemHmacSigner(byte[] secretKeyBytes) {
        Objects.requireNonNull(secretKeyBytes, "secretKeyBytes");
        if (secretKeyBytes.length < 16) {
            throw new IllegalArgumentException("HMAC secret key must be at least 16 bytes");
        }
        this.secretKeyBytes = secretKeyBytes.clone();
    }

    /**
     * Creates an ephemeral random signer (useful for isolated unit tests).
     */
    public static ItemHmacSigner randomEphemeral() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return new ItemHmacSigner(bytes);
    }

    /**
     * Loads the server HMAC secret from {@code secretFile}, or generates and persists a new
     * 32-byte random secret if the file does not exist yet.
     */
    public static ItemHmacSigner loadOrGenerate(Path secretFile) throws IOException {
        Objects.requireNonNull(secretFile, "secretFile");
        if (Files.exists(secretFile)) {
            String hex = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
            byte[] decoded = HEX.parseHex(hex);
            if (decoded.length >= 16) {
                return new ItemHmacSigner(decoded);
            }
        }

        Path parent = secretFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        byte[] generated = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(generated);
        String encoded = HEX.formatHex(generated);

        Files.writeString(
                secretFile,
                encoded + System.lineSeparator(),
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

        return new ItemHmacSigner(generated);
    }

    /**
     * Computes the lowercase hex HMAC-SHA256 over {@code (provider, itemId, behaviorId, instanceUuid)}.
     */
    public String sign(String provider, String itemId, String behaviorId, String instanceUuid) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(behaviorId, "behaviorId");
        Objects.requireNonNull(instanceUuid, "instanceUuid");

        byte[] macBytes = computeMac(provider, itemId, behaviorId, instanceUuid);
        return HEX.formatHex(macBytes);
    }

    /**
     * Verifies a candidate hex signature in constant time.
     */
    public boolean verify(
            @Nullable String provider,
            @Nullable String itemId,
            @Nullable String behaviorId,
            @Nullable String instanceUuid,
            @Nullable String candidateHexSignature
    ) {
        if (provider == null || provider.isBlank()
                || itemId == null || itemId.isBlank()
                || behaviorId == null || behaviorId.isBlank()
                || instanceUuid == null || instanceUuid.isBlank()
                || candidateHexSignature == null || candidateHexSignature.length() != 64) {
            return false;
        }
        byte[] candidateBytes;
        try {
            candidateBytes = HEX.parseHex(candidateHexSignature.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] expectedBytes = computeMac(provider, itemId, behaviorId, instanceUuid);
        return MessageDigest.isEqual(expectedBytes, candidateBytes);
    }

    private byte[] computeMac(String provider, String itemId, String behaviorId, String instanceUuid) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKeyBytes, HMAC_ALGO));
            String payload = provider.trim().toLowerCase(Locale.ROOT)
                    + '\0' + itemId.trim().toLowerCase(Locale.ROOT)
                    + '\0' + behaviorId.trim().toLowerCase(Locale.ROOT)
                    + '\0' + instanceUuid.trim().toLowerCase(Locale.ROOT);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to compute HmacSHA256 for item signature", e);
        }
    }
}
