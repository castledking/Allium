package codes.castled.allium.item;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Canonical provider-qualified item key {@code <provider>:<id>}.
 *
 * <p>Platform-neutral value object in {@code allium-core}. Rules enforced:
 * <ul>
 *   <li>Splits at the <b>first colon only</b> so CraftEngine references retain their full
 *       internal namespace (e.g. {@code craftengine:sf_ores:silver_ingot} has provider
 *       {@code "craftengine"} and id {@code "sf_ores:silver_ingot"}).</li>
 *   <li>Bare names without a colon resolve strictly to {@code "minecraft"} (vanilla).
 *       Allium items always require {@code allium:<id>}.</li>
 *   <li>Legacy {@code ci:} and {@code custom:} prefixes are rejected.</li>
 *   <li>{@code ce:} is rejected unless explicitly parsed with {@link #parse(String, boolean, Consumer)}
 *       in deprecated compatibility mode, which emits a warning once per distinct reference
 *       per startup/reload and normalizes to {@code craftengine:}.</li>
 * </ul>
 */
public record ProviderKey(String provider, String id) {

    public static final String MINECRAFT = "minecraft";
    public static final String ALLIUM = "allium";
    public static final String NEXO = "nexo";
    public static final String ORAXEN = "oraxen";
    public static final String CRAFTENGINE = "craftengine";

    private static final Set<String> WARNED_DEPRECATED_CE_REFS = ConcurrentHashMap.newKeySet();

    public ProviderKey {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(id, "id");
        provider = provider.trim().toLowerCase(Locale.ROOT);
        id = id.trim();
        // External registries (notably Nexo and Oraxen) use case-sensitive IDs.
        // Only namespaces with a defined lowercase identity are normalized.
        if (MINECRAFT.equals(provider) || ALLIUM.equals(provider) || CRAFTENGINE.equals(provider)) {
            id = id.toLowerCase(Locale.ROOT);
        }
        if (provider.isEmpty()) {
            throw new IllegalArgumentException("Item provider namespace must not be empty");
        }
        if (id.isEmpty() || id.startsWith(":") || id.endsWith(":")) {
            throw new IllegalArgumentException("Invalid item identifier '" + id + "' for provider '" + provider + "'");
        }
    }

    public static ProviderKey of(String provider, String id) {
        return new ProviderKey(provider, id);
    }

    public static ProviderKey minecraft(String id) {
        return new ProviderKey(MINECRAFT, id);
    }

    public static ProviderKey allium(String id) {
        return new ProviderKey(ALLIUM, id);
    }

    /**
     * Resets the per-startup/per-reload set of warned {@code ce:} references.
     */
    public static void resetDeprecationWarnings() {
        WARNED_DEPRECATED_CE_REFS.clear();
    }

    /**
     * Parses a canonical item reference string in strict mode (rejecting {@code ci:}, {@code custom:}, and {@code ce:}).
     */
    public static ProviderKey parse(String rawReference) {
        return parse(rawReference, false, null);
    }

    /**
     * Parses an item reference string, splitting at the first colon only.
     *
     * @param rawReference      raw input string (e.g. {@code "stone"}, {@code "minecraft:stone"},
     *                          {@code "allium:mob_disarmer"}, {@code "craftengine:sf_ores:silver_ingot"})
     * @param allowDeprecatedCe whether to accept deprecated {@code ce:} prefix with a bounded warning
     * @param warningConsumer   optional sink for deprecation warnings (fired at most once per distinct reference per startup/reload)
     */
    public static ProviderKey parse(
            String rawReference,
            boolean allowDeprecatedCe,
            @Nullable Consumer<String> warningConsumer
    ) {
        if (rawReference == null || rawReference.isBlank()) {
            throw new IllegalArgumentException("Item reference must not be null or blank");
        }
        String trimmed = rawReference.trim();
        int firstColon = trimmed.indexOf(':');

        // Bare name -> strictly vanilla (minecraft:<id>)
        if (firstColon == -1) {
            return new ProviderKey(MINECRAFT, trimmed);
        }

        if (firstColon == 0 || firstColon == trimmed.length() - 1) {
            throw new IllegalArgumentException("Malformed item reference: '" + rawReference + "'");
        }

        String rawProvider = trimmed.substring(0, firstColon).trim().toLowerCase(Locale.ROOT);
        String rawId = trimmed.substring(firstColon + 1).trim();

        if (rawId.isEmpty() || rawId.startsWith(":") || rawId.endsWith(":")) {
            throw new IllegalArgumentException("Malformed item identifier in reference: '" + rawReference + "'");
        }

        if (rawProvider.equals("ci") || rawProvider.equals("custom")) {
            throw new IllegalArgumentException(
                    "Legacy prefix '" + rawProvider + ":' is retired; use 'allium:" + rawId + "' instead");
        }

        if (rawProvider.equals("vanilla")) {
            throw new IllegalArgumentException(
                    "Legacy prefix 'vanilla:' is not a runtime namespace; use 'minecraft:" + rawId + "' instead");
        }

        if (rawProvider.equals("ce")) {
            rawId = rawId.toLowerCase(Locale.ROOT);
            if (!allowDeprecatedCe) {
                throw new IllegalArgumentException(
                        "Legacy prefix 'ce:' is deprecated; use 'craftengine:" + rawId + "' instead");
            }
            if (warningConsumer != null && WARNED_DEPRECATED_CE_REFS.add("ce:" + rawId)) {
                warningConsumer.accept("Deprecated item prefix 'ce:" + rawId
                        + "' used; convert to 'craftengine:" + rawId + "'");
            }
            rawProvider = CRAFTENGINE;
        }

        if (rawProvider.equals(CRAFTENGINE) && !rawId.contains(":")) {
            throw new IllegalArgumentException(
                    "CraftEngine references require full '<namespace>:<id>' after 'craftengine:'; got '" + rawReference + "'");
        }

        return new ProviderKey(rawProvider, rawId);
    }

    public boolean isVanilla() {
        return MINECRAFT.equals(provider);
    }

    public boolean isAllium() {
        return ALLIUM.equals(provider);
    }

    public String asString() {
        return provider + ":" + id;
    }

    @Override
    public String toString() {
        return asString();
    }
}
