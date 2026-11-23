package codes.castled.allium.addon;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * SHA-256 digest parsing, computation, and lookup for verified addon artifact installation.
 */
public final class AddonChecksums {

    /** Classpath location inside {@code Allium.jar} holding trusted official addon digests for this build. */
    public static final String BUNDLED_RESOURCE = "/META-INF/allium/addon-checksums.txt";

    /** Hard cap on remote {@code SHA256SUMS.txt} manifest size (1 MiB). */
    static final long MAX_MANIFEST_BYTES = 1024L * 1024L;

    private static final HexFormat HEX = HexFormat.of();

    private AddonChecksums() {}

    public static boolean isValidSha256(@Nullable String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = trimmed.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    public static String normalizeSha256(String value) {
        if (!isValidSha256(value)) {
            throw new IllegalArgumentException("Expected a 64-character hex SHA-256 digest");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean digestsEqual(String expectedHex, String actualHex) {
        return MessageDigest.isEqual(
                normalizeSha256(expectedHex).getBytes(StandardCharsets.US_ASCII),
                normalizeSha256(actualHex).getBytes(StandardCharsets.US_ASCII)
        );
    }

    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
        byte[] buffer = new byte[16384];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    /**
     * Parses a standard {@code sha256sum} manifest ({@code <64-hex>  <filename>} or
     * {@code <64-hex> *<filename>}), rejecting malformed lines or conflicting duplicate entries.
     */
    public static Map<String, String> parseManifest(String content) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (content == null || content.isEmpty()) return out;
        int lineNumber = 0;
        for (String rawLine : content.split("\\R")) {
            lineNumber++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int split = -1;
            for (int i = 0; i < line.length(); i++) {
                if (Character.isWhitespace(line.charAt(i))) {
                    split = i;
                    break;
                }
            }
            if (split <= 0) {
                throw new IOException("Malformed checksum line " + lineNumber);
            }
            String hashToken = line.substring(0, split);
            if (!isValidSha256(hashToken)) {
                throw new IOException("Invalid SHA-256 digest on line " + lineNumber);
            }
            String fileToken = line.substring(split).trim();
            if (fileToken.startsWith("*")) fileToken = fileToken.substring(1).trim();
            while (fileToken.startsWith("./")) fileToken = fileToken.substring(2);
            if (fileToken.isEmpty() || fileToken.contains("/") || fileToken.contains("\\")) {
                throw new IOException("Invalid checksum filename on line " + lineNumber);
            }
            String normalized = hashToken.toLowerCase(Locale.ROOT);
            String previous = out.putIfAbsent(fileToken, normalized);
            if (previous != null && !previous.equals(normalized)) {
                throw new IOException("Conflicting SHA-256 digests for " + fileToken + " on line " + lineNumber);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Loads the bundled official addon checksum manifest shipped inside {@code Allium.jar}, if present. */
    public static Map<String, String> loadBundled() {
        try (InputStream in = AddonChecksums.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) return Map.of();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseManifest(text);
        } catch (IOException e) {
            return Map.of();
        }
    }

    /**
     * Reads an operator-pinned SHA-256 digest from {@code addons.checksums.*} in {@code config.yml}.
     * Returns empty when unset, or throws {@link IOException} if set to a malformed value.
     */
    public static Optional<String> configuredPin(
            @Nullable FileConfiguration config,
            AddonCatalog.Entry entry,
            String version
    ) throws IOException {
        if (config == null) return Optional.empty();
        ConfigurationSection section = config.getConfigurationSection("addons.checksums");
        if (section == null) return Optional.empty();

        String jarName = entry.jarFileName(version);
        String raw = readDirectString(section, entry.key());
        if (raw == null) raw = readDirectString(section, entry.displayName());
        if (raw == null) {
            for (String alias : entry.aliases()) {
                raw = readDirectString(section, alias);
                if (raw != null) break;
            }
        }
        if (raw == null) {
            // Also support keys stored by filename if the config loader preserved them without dot-splitting.
            for (String key : section.getKeys(false)) {
                if (key.equalsIgnoreCase(jarName) || key.equalsIgnoreCase(entry.key()) || key.equalsIgnoreCase(entry.displayName())) {
                    Object val = section.get(key);
                    if (val instanceof String s && !s.isBlank()) {
                        raw = s.trim();
                        break;
                    }
                }
            }
        }
        if (raw == null) return Optional.empty();
        if (!isValidSha256(raw)) {
            throw new IOException("addons.checksums." + entry.key() + " is not a valid 64-character hex SHA-256 digest");
        }
        return Optional.of(raw.toLowerCase(Locale.ROOT));
    }

    @Nullable
    private static String readDirectString(ConfigurationSection section, String key) {
        if (!section.isString(key)) return null;
        String value = section.getString(key);
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
