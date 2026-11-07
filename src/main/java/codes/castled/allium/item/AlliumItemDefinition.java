package codes.castled.allium.item;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Definition of a built-in Allium item ({@code allium:<id>}).
 *
 * @param privileged whether this item's behavior requires a valid server-secret HMAC signature
 *                   in PDC (e.g. {@code true} for Handcuffs and Phantom Obliterator)
 * @param signed     whether new stacks of this behavior carry an instance UUID and MAC even
 *                   though the behavior does not require one. A present MAC is always verified,
 *                   so edited signed state is rejected; an unsigned stack of a non-privileged
 *                   behavior is still accepted. Privileged definitions are always signed.
 */
public record AlliumItemDefinition(
        String id,
        Material carrierMaterial,
        @Nullable String behaviorId,
        boolean privileged,
        @Nullable Integer customModelData,
        @Nullable String displayName,
        Map<String, String> defaultState,
        boolean signed
) {
    public AlliumItemDefinition(
            String id,
            Material carrierMaterial,
            @Nullable String behaviorId,
            @Nullable Integer customModelData,
            @Nullable String displayName,
            Map<String, String> defaultState
    ) {
        this(id, carrierMaterial, behaviorId, false, customModelData, displayName, defaultState, false);
    }

    public AlliumItemDefinition(
            String id,
            Material carrierMaterial,
            @Nullable String behaviorId,
            boolean privileged,
            @Nullable Integer customModelData,
            @Nullable String displayName,
            Map<String, String> defaultState
    ) {
        this(id, carrierMaterial, behaviorId, privileged, customModelData, displayName, defaultState, privileged);
    }

    public AlliumItemDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(carrierMaterial, "carrierMaterial");
        id = id.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || id.contains(":")) {
            throw new IllegalArgumentException("Allium item id must be a non-empty local id; got '" + id + "'");
        }
        if (carrierMaterial == Material.AIR || carrierMaterial.isAir()) {
            throw new IllegalArgumentException("Allium item carrierMaterial must never be AIR for 'allium:" + id + "'");
        }
        behaviorId = behaviorId != null ? ItemRef.normalizeBehaviorId(behaviorId) : ProviderKey.ALLIUM + ":" + id;
        signed = signed || privileged;
        if (defaultState == null || defaultState.isEmpty()) {
            defaultState = Collections.emptyMap();
        } else {
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : defaultState.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    copy.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
                }
            }
            defaultState = Collections.unmodifiableMap(copy);
        }
    }

    public ProviderKey providerKey() {
        return ProviderKey.allium(id);
    }
}
