package codes.castled.allium.item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Platform-neutral read-only snapshot of legacy Allium item PDC tags
 * ({@code allium:custom_item_id} and {@code alliumcards} namespace keys)
 * used by the legacy data/world importer.
 *
 * <p>These legacy tags are strictly read-only for migration/import and never grant
 * privileged runtime behavior without migration and HMAC signing.
 */
public record LegacyItemMetadata(
        Optional<String> legacyCustomItemId,
        Map<String, String> alliumCardsTags
) {
    public static final LegacyItemMetadata EMPTY = new LegacyItemMetadata(Optional.empty(), Collections.emptyMap());

    public LegacyItemMetadata {
        legacyCustomItemId = legacyCustomItemId == null ? Optional.empty() : legacyCustomItemId;
        alliumCardsTags = (alliumCardsTags == null || alliumCardsTags.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(alliumCardsTags));
    }

    public boolean hasLegacyData() {
        return legacyCustomItemId.isPresent() || !alliumCardsTags.isEmpty();
    }
}
