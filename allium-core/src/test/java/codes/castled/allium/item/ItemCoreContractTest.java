package codes.castled.allium.item;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    void hmacSignerPersistsSecretAndDetectsTampering(@TempDir Path tempDir) throws IOException {
        Path secretFile = tempDir.resolve(".item-hmac-secret");
        ItemHmacSigner signer1 = ItemHmacSigner.loadOrGenerate(secretFile);
        ItemHmacSigner signer2 = ItemHmacSigner.loadOrGenerate(secretFile);

        String uuid = UUID.randomUUID().toString();
        String sig = signer1.sign("allium", "handcuffs", "allium:handcuffs", uuid);

        // Reloaded signer verifies the same signature
        assertTrue(signer2.verify("allium", "handcuffs", "allium:handcuffs", uuid, sig));

        // Any tampering of provider, itemId, behaviorId, instanceUuid, or signature fails
        assertFalse(signer2.verify("minecraft", "handcuffs", "allium:handcuffs", uuid, sig));
        assertFalse(signer2.verify("allium", "iron_shovel", "allium:handcuffs", uuid, sig));
        assertFalse(signer2.verify("allium", "handcuffs", "allium:phantom_obliterator", uuid, sig));
        assertFalse(signer2.verify("allium", "handcuffs", "allium:handcuffs", UUID.randomUUID().toString(), sig));
        assertFalse(signer2.verify("allium", "handcuffs", "allium:handcuffs", uuid, "0".repeat(64)));
    }
}
