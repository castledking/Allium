package codes.castled.allium.item;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ItemCoreContractTest {

    @Test
    void caseSensitiveExternalIdsSurviveParsingAndSerialization() {
        for (String provider : List.of("nexo", "oraxen")) {
            ProviderKey mixed = ProviderKey.parse(provider.toUpperCase() + ":Seasonal_Wand");
            assertEquals("Seasonal_Wand", mixed.id());
            assertNotEquals(mixed, ProviderKey.parse(provider + ":seasonal_wand"));
            ItemRef ref = new ItemRef(mixed, null, 1, Map.of());
            assertEquals(ref, ItemRef.deserialize(ref.serialize()));
        }
        assertEquals("stone", ProviderKey.parse("STONE").id());
        assertEquals("handcuffs", ProviderKey.parse("allium:HANDCUFFS").id());
        assertEquals("sf_ores:silver_ingot", ProviderKey.parse("craftengine:SF_ORES:SILVER_INGOT").id());
    }

    @BeforeEach
    void resetWarnings() {
        ProviderKey.resetDeprecationWarnings();
    }

    @Test
    void ceDeprecationWarningFiresOncePerDistinctReferencePerReload() {
        List<String> warnings = new ArrayList<>();

        // First resolution of ce:sf_ores:silver_ingot -> fires warning
        ProviderKey first = ProviderKey.parse("ce:sf_ores:silver_ingot", true, warnings::add);
        assertEquals(new ProviderKey("craftengine", "sf_ores:silver_ingot"), first);
        assertEquals(1, warnings.size());

        // Subsequent resolutions of the same reference -> suppressed
        ProviderKey second = ProviderKey.parse("ce:sf_ores:silver_ingot", true, warnings::add);
        ProviderKey third = ProviderKey.parse("CE:SF_ORES:SILVER_INGOT", true, warnings::add);
        assertEquals(first, second);
        assertEquals(first, third);
        assertEquals(1, warnings.size());

        // A different ce: reference fires its own single warning
        ProviderKey other = ProviderKey.parse("ce:sf_ores:gold_ingot", true, warnings::add);
        assertEquals(new ProviderKey("craftengine", "sf_ores:gold_ingot"), other);
        assertEquals(2, warnings.size());

        // After resetDeprecationWarnings() (simulating reload), warning fires once again
        ProviderKey.resetDeprecationWarnings();
        ProviderKey.parse("ce:sf_ores:silver_ingot", true, warnings::add);
        assertEquals(3, warnings.size());
    }

    @Test
    void itemRefSerializationIncludesAndValidatesSchemaVersion() {
        ItemRef ref = new ItemRef(
                ProviderKey.parse("craftengine:sf_ores:silver_ingot"),
                "allium:silver_ingot",
                8,
                Map.of("charges", "3")
        );
        assertEquals(ItemRef.CURRENT_SCHEMA_VERSION, ref.schemaVersion());

        Map<String, Object> serialized = ref.serialize();
        assertEquals(ItemRef.CURRENT_SCHEMA_VERSION, serialized.get(ItemRef.KEY_SCHEMA_VERSION));

        ItemRef roundTripped = ItemRef.deserialize(serialized);
        assertEquals(ref, roundTripped);
        assertEquals(ItemRef.CURRENT_SCHEMA_VERSION, roundTripped.schemaVersion());

        // Unsupported schema version is rejected
        Map<String, Object> futureVersion = Map.of(
                ItemRef.KEY_SCHEMA_VERSION, 999,
                "ref", "minecraft:stone"
        );
        assertThrows(IllegalArgumentException.class, () -> ItemRef.deserialize(futureVersion));
    }

    @Test
    void hmacSignerIncludesCanonicalSortedStateAndSupportsKeyringRotation(@TempDir Path tempDir) throws IOException {
        Path secretFile = tempDir.resolve(".item-hmac-secret");
        ItemHmacSigner signer1 = ItemHmacSigner.loadOrGenerate(secretFile);
        ItemHmacSigner signer2 = ItemHmacSigner.loadOrGenerate(secretFile);

        String uuid = UUID.randomUUID().toString();
        Map<String, String> stateOrderA = new LinkedHashMap<>();
        stateOrderA.put("charges", "3");
        stateOrderA.put("tier", "2");

        Map<String, String> stateOrderB = new LinkedHashMap<>();
        stateOrderB.put("tier", "2");
        stateOrderB.put("charges", "3");

        String sigA = signer1.sign("allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderA);
        String sigB = signer1.sign("allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderB);

        // Canonical sorted state produces identical signatures regardless of map insertion order
        assertEquals(sigA, sigB);
        assertTrue(sigA.startsWith("v2:k1:"));
        assertTrue(signer2.verify("allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderB, sigA));

        // Editing state values or adding/removing state keys invalidates the signature
        assertFalse(signer2.verify(
                "allium", "phantom_obliterator", "allium:phantom_obliterator", uuid,
                Map.of("charges", "99", "tier", "2"), sigA
        ));
        assertFalse(signer2.verify(
                "allium", "phantom_obliterator", "allium:phantom_obliterator", uuid,
                Map.of("charges", "3"), sigA
        ));

        // Key rotation: rotate to k2 while keeping k1 as a retired key
        byte[] key2Bytes = new byte[32];
        new SecureRandom().nextBytes(key2Bytes);
        ItemHmacSigner rotatedSigner = signer2.rotateKey("k2", key2Bytes);
        assertEquals("k2", rotatedSigner.activeKeyId());
        assertEquals(Set.of("k1", "k2"), rotatedSigner.keyIds());

        // Old signature from k1 still verifies under rotatedSigner
        assertTrue(rotatedSigner.verify("allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderA, sigA));

        // New signatures use k2
        String sigK2 = rotatedSigner.sign("allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderA);
        assertTrue(sigK2.startsWith("v2:k2:"));
        assertTrue(rotatedSigner.verify("allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderA, sigK2));

        // Signer1 (which only knows k1) reports UNKNOWN_KEY_ID for k2
        ItemHmacSigner.VerificationOutcome unknownKeyOutcome = signer1.verifyDetailed(
                "allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderA, sigK2
        );
        assertEquals(ItemHmacSigner.VerificationStatus.UNKNOWN_KEY_ID, unknownKeyOutcome.status());
        assertEquals("k2", unknownKeyOutcome.keyId());

        // Multi-key file format round-trip
        Path rotatedFile = tempDir.resolve(".item-hmac-secret-rotated");
        String hexK1 = Files.readAllLines(secretFile).stream()
                .filter(line -> line.startsWith("active=k1:"))
                .findFirst().orElseThrow().substring("active=k1:".length());
        String hexK2 = HexFormat.of().formatHex(key2Bytes);
        Files.writeString(rotatedFile, "active=k2:" + hexK2 + "\nretired=k1:" + hexK1 + "\n", StandardCharsets.UTF_8);
        ItemHmacSigner loadedRotated = ItemHmacSigner.loadOrGenerate(rotatedFile);
        assertEquals("k2", loadedRotated.activeKeyId());
        assertEquals(Set.of("k1", "k2"), loadedRotated.keyIds());
        assertTrue(loadedRotated.verify("allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderA, sigA),
                "The persisted retired key must verify the actual pre-rotation signature");
        assertTrue(loadedRotated.verify("allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderA, sigK2));
    }

    @Test
    void hmacLengthPrefixesRejectActualDelimiterCollisions() {
        ItemHmacSigner signer = new ItemHmacSigner(new byte[32]);
        Map<String, String> embeddedField = Map.of("a", "x\0charges=999");
        Map<String, String> separateFields = Map.of("a", "x", "charges", "999");
        // Both states encoded as exactly these bytes in the unsafe delimiter format.
        assertEquals("a=x\0charges=999", "a=" + embeddedField.get("a"));
        assertEquals("a=x\0charges=999", "a=" + separateFields.get("a") + "\0charges=" + separateFields.get("charges"));
        String signature = signer.sign("allium", "wand", "allium:wand", "uuid", embeddedField);
        assertFalse(signer.verify("allium", "wand", "allium:wand", "uuid", separateFields, signature));
        assertNotEquals(ItemHmacSigner.encodeCanonicalState(embeddedField), ItemHmacSigner.encodeCanonicalState(separateFields));

        String identitySignature = signer.sign("allium\0wand", "item", "allium:wand", "uuid");
        assertFalse(signer.verify("allium", "wand\0item", "allium:wand", "uuid", identitySignature));
        String externalSignature = signer.sign("nexo", "Seasonal_Wand", "allium:wand", "uuid");
        assertFalse(signer.verify("nexo", "seasonal_wand", "allium:wand", "uuid", externalSignature));
    }

    @Test
    void unsafeLegacySignaturesRequireExplicitVersionMigration() throws Exception {
        byte[] secret = new byte[32];
        javax.crypto.Mac legacyMac = javax.crypto.Mac.getInstance("HmacSHA256");
        legacyMac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
        String legacyHex = HexFormat.of().formatHex(legacyMac.doFinal(
                "allium\0wand\0allium:wand\0uuid\0charges=3".getBytes(StandardCharsets.UTF_8)));
        ItemHmacSigner signer = new ItemHmacSigner(secret);
        for (String legacy : List.of(legacyHex, "k1:" + legacyHex, "v1:k1:" + legacyHex, "v99:k1:" + legacyHex)) {
            ItemHmacSigner.VerificationOutcome outcome = signer.verifyDetailed(
                    "allium", "wand", "allium:wand", "uuid", Map.of("charges", "3"), legacy);
            assertFalse(outcome.isValid());
            assertEquals("UNSUPPORTED_VERSION", outcome.status().name(), legacy);
        }
        String versioned = signer.sign("allium", "wand", "allium:wand", "uuid", Map.of("charges", "3"));
        assertTrue(versioned.matches("v2:k1:[0-9a-f]{64}"));
        assertFalse(signer.verify("allium", "wand", "allium:wand", "uuid", Map.of("charges", "3"), "v2:k1:" + legacyHex));
    }

    @Test
    void existingMalformedKeyfilesThrowWithoutChangingAnyBytes(@TempDir Path tempDir) throws IOException {
        String hex = "01".repeat(32);
        List<String> malformed = List.of(
                "", "  \n# empty ring\n", "retired=k1:" + hex + "\n",
                "active=k2:not-hex\nretired=k1:" + hex + "\n",
                "active=k1:01\n", "active=k1:" + hex + "\njunk\n",
                "active=k2:" + hex + "\nretired=k1:bad-hex\n",
                "active=k1:" + hex + "\nactive=k2:" + hex + "\n",
                "active=k1:" + hex + "\nretired=K1:" + "02".repeat(32) + "\n",
                "active=bad:id:" + hex + "\n", hex + "\nretired=k2:" + hex + "\n"
        );
        Path file = tempDir.resolve("existing-keyring");
        for (String content : malformed) {
            byte[] original = content.getBytes(StandardCharsets.UTF_8);
            Files.write(file, original);
            assertThrows(IOException.class, () -> ItemHmacSigner.loadOrGenerate(file), content);
            assertArrayEquals(original, Files.readAllBytes(file), content);
        }
        byte[] malformedUtf8 = {(byte) 0xc3, (byte) 0x28};
        Files.write(file, malformedUtf8);
        assertThrows(IOException.class, () -> ItemHmacSigner.loadOrGenerate(file));
        assertArrayEquals(malformedUtf8, Files.readAllBytes(file));
    }

    @Test
    void validBareHexSecretLoadsWithoutRewritingAndMissingFileGenerates(@TempDir Path tempDir) throws IOException {
        Path legacyFile = tempDir.resolve("bare-key");
        byte[] original = ("# shared key\n" + "ab".repeat(32) + "\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(legacyFile, original);
        ItemHmacSigner loaded = ItemHmacSigner.loadOrGenerate(legacyFile);
        assertEquals("k1", loaded.activeKeyId());
        assertArrayEquals(original, Files.readAllBytes(legacyFile));
        ItemHmacSigner expected = new ItemHmacSigner(HexFormat.of().parseHex("ab".repeat(32)));
        String signature = expected.sign("allium", "wand", "allium:wand", "uuid");
        assertTrue(loaded.verify("allium", "wand", "allium:wand", "uuid", signature));

        Path missing = tempDir.resolve("new-directory/keyring");
        ItemHmacSigner generated = ItemHmacSigner.loadOrGenerate(missing);
        byte[] saved = Files.readAllBytes(missing);
        ItemHmacSigner reloaded = ItemHmacSigner.loadOrGenerate(missing);
        assertArrayEquals(saved, Files.readAllBytes(missing));
        assertTrue(reloaded.verify("allium", "wand", "allium:wand", "uuid",
                generated.sign("allium", "wand", "allium:wand", "uuid")));
        assertNotEquals(signature, generated.sign("allium", "wand", "allium:wand", "uuid"));
    }

    @Test
    void malformedUnicodeAndNormalizedKeyCollisionsCannotVerify() {
        ItemHmacSigner signer = new ItemHmacSigner(new byte[32]);
        String signature = signer.sign("allium", "wand", "allium:wand", "uuid", Map.of("label", "?"));
        assertFalse(signer.verify("allium", "wand", "allium:wand", "uuid", Map.of("label", "\ud800"), signature));
        assertThrows(IllegalArgumentException.class,
                () -> signer.sign("allium", "wand", "allium:wand", "uuid", Map.of("label", "\ud800")));
        assertThrows(IllegalArgumentException.class,
                () -> ItemHmacSigner.encodeCanonicalState(Map.of("charges", "3", " CHARGES ", "999")));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemHmacSigner("k1", Map.of("k1", new byte[32], "K1", new byte[32])));
        assertThrows(IllegalArgumentException.class, () -> signer.rotateKey("k1", new byte[32]));
    }

}
