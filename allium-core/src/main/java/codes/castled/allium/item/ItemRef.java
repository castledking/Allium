package codes.castled.allium.item;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Platform-neutral immutable item reference contract separating visual appearance ({@link ProviderKey})
 * from authoritative Allium behavior identity ({@code behaviorId}) and persisted item state.
 */
public final class ItemRef {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String KEY_SCHEMA_VERSION = "schema-version";

    private final int schemaVersion;
    private final ProviderKey appearance;
    @Nullable
    private final String behaviorId;
    private final int amount;
    private final Map<String, String> stateData;

    public ItemRef(
            ProviderKey appearance,
            @Nullable String behaviorId,
            int amount,
            @Nullable Map<String, String> stateData
    ) {
        this(CURRENT_SCHEMA_VERSION, appearance, behaviorId, amount, stateData);
    }

    public ItemRef(
            int schemaVersion,
            ProviderKey appearance,
            @Nullable String behaviorId,
            int amount,
            @Nullable Map<String, String> stateData
    ) {
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported ItemRef schemaVersion " + schemaVersion + " (supported: 1.." + CURRENT_SCHEMA_VERSION + ")");
        }
        this.schemaVersion = schemaVersion;
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.behaviorId = normalizeBehaviorId(behaviorId);
        if (amount < 1) {
            throw new IllegalArgumentException("Item amount must be >= 1; got " + amount);
        }
        this.amount = amount;
        if (stateData == null || stateData.isEmpty()) {
            this.stateData = Collections.emptyMap();
        } else {
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : stateData.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
            this.stateData = Collections.unmodifiableMap(copy);
        }
    }

    public static ItemRef parse(String rawReference) {
        ProviderKey key = ProviderKey.parse(rawReference);
        String defaultBehavior = key.isAllium() ? key.asString() : null;
        return new ItemRef(CURRENT_SCHEMA_VERSION, key, defaultBehavior, 1, Collections.emptyMap());
    }

    public static ItemRef of(ProviderKey appearance) {
        String defaultBehavior = appearance.isAllium() ? appearance.asString() : null;
        return new ItemRef(CURRENT_SCHEMA_VERSION, appearance, defaultBehavior, 1, Collections.emptyMap());
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public ProviderKey appearance() {
        return appearance;
    }

    public Optional<String> behaviorId() {
        return Optional.ofNullable(behaviorId);
    }

    public int amount() {
        return amount;
    }

    public Map<String, String> stateData() {
        return stateData;
    }

    public Optional<String> state(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(stateData.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    public ItemRef withAppearance(ProviderKey newAppearance) {
        return new ItemRef(this.schemaVersion, newAppearance, this.behaviorId, this.amount, this.stateData);
    }

    public ItemRef withBehaviorId(@Nullable String newBehaviorId) {
        return new ItemRef(this.schemaVersion, this.appearance, newBehaviorId, this.amount, this.stateData);
    }

    public ItemRef withAmount(int newAmount) {
        return new ItemRef(this.schemaVersion, this.appearance, this.behaviorId, newAmount, this.stateData);
    }

    public ItemRef withState(String key, String value) {
        Map<String, String> updated = new LinkedHashMap<>(this.stateData);
        updated.put(key.trim().toLowerCase(Locale.ROOT), Objects.requireNonNull(value, "value"));
        return new ItemRef(this.schemaVersion, this.appearance, this.behaviorId, this.amount, updated);
    }

    public ItemRef withStateData(Map<String, String> newStateData) {
        return new ItemRef(this.schemaVersion, this.appearance, this.behaviorId, this.amount, newStateData);
    }

    public String toCanonicalString() {
        return appearance.asString();
    }

    /**
     * Serializes this {@link ItemRef} to a structured map suitable for YAML/JSON persistence.
     */
    public Map<String, Object> serialize() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_SCHEMA_VERSION, schemaVersion);
        map.put("ref", appearance.asString());
        if (behaviorId != null) {
            map.put("behavior-id", behaviorId);
        }
        map.put("amount", amount);
        if (!stateData.isEmpty()) {
            map.put("state", new LinkedHashMap<>(stateData));
        }
        return map;
    }

    /**
     * Deserializes an {@link ItemRef} from a structured map produced by {@link #serialize()}.
     */
    public static ItemRef deserialize(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");

        int schemaVersion = CURRENT_SCHEMA_VERSION;
        Object rawSchema = map.get(KEY_SCHEMA_VERSION);
        if (rawSchema instanceof Number num) {
            schemaVersion = num.intValue();
        } else if (rawSchema instanceof String schemaStr && !schemaStr.isBlank()) {
            schemaVersion = Integer.parseInt(schemaStr.trim());
        }

        Object rawRef = map.get("ref");
        if (!(rawRef instanceof String refStr) || refStr.isBlank()) {
            throw new IllegalArgumentException("Serialized ItemRef is missing 'ref'");
        }
        ProviderKey appearance = ProviderKey.parse(refStr);

        String behaviorId = null;
        Object rawBehavior = map.get("behavior-id");
        if (rawBehavior instanceof String bStr && !bStr.isBlank()) {
            behaviorId = bStr;
        } else if (appearance.isAllium()) {
            behaviorId = appearance.asString();
        }

        int amount = 1;
        Object rawAmount = map.get("amount");
        if (rawAmount instanceof Number num) {
            amount = num.intValue();
        } else if (rawAmount instanceof String amtStr) {
            amount = Integer.parseInt(amtStr.trim());
        }

        Map<String, String> state = new LinkedHashMap<>();
        Object rawState = map.get("state");
        if (rawState instanceof Map<?, ?> stateMap) {
            for (Map.Entry<?, ?> entry : stateMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    state.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }

        return new ItemRef(schemaVersion, appearance, behaviorId, amount, state);
    }

    @Nullable
    public static String normalizeBehaviorId(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains(":")) {
            return ProviderKey.ALLIUM + ":" + normalized;
        }
        return normalized;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemRef itemRef)) return false;
        return schemaVersion == itemRef.schemaVersion
                && amount == itemRef.amount
                && Objects.equals(appearance, itemRef.appearance)
                && Objects.equals(behaviorId, itemRef.behaviorId)
                && Objects.equals(stateData, itemRef.stateData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, appearance, behaviorId, amount, stateData);
    }

    @Override
    public String toString() {
        return "ItemRef{schemaVersion=" + schemaVersion
                + ", appearance=" + appearance
                + ", behaviorId=" + behaviorId
                + ", amount=" + amount
                + ", stateData=" + stateData + '}';
    }
}
