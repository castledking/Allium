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
        assertTrue(sigA.startsWith("k1:"));
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
        assertTrue(sigK2.startsWith("k2:"));
        assertTrue(rotatedSigner.verify("allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderA, sigK2));

        // Signer1 (which only knows k1) reports UNKNOWN_KEY_ID for k2
        ItemHmacSigner.VerificationOutcome unknownKeyOutcome = signer1.verifyDetailed(
                "allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderA, sigK2
        );
        assertEquals(ItemHmacSigner.VerificationStatus.UNKNOWN_KEY_ID, unknownKeyOutcome.status());
        assertEquals("k2", unknownKeyOutcome.keyId());

        // Multi-key file format round-trip
        Path rotatedFile = tempDir.resolve(".item-hmac-secret-rotated");
        String hexK1 = HexFormat.of().formatHex(new byte[32]);
        String hexK2 = HexFormat.of().formatHex(key2Bytes);
        Files.writeString(rotatedFile, "active=k2:" + hexK2 + "\nretired=k1:" + hexK1 + "\n", StandardCharsets.UTF_8);
        ItemHmacSigner loadedRotated = ItemHmacSigner.loadOrGenerate(rotatedFile);
        assertEquals("k2", loadedRotated.activeKeyId());
        assertEquals(Set.of("k1", "k2"), loadedRotated.keyIds());
        assertTrue(loadedRotated.verify("allium", "phantom_obliterator", "allium:phantom_obliterator", uuid, stateOrderA, sigK2));
    }
}
