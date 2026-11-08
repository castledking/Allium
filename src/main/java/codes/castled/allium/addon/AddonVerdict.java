package codes.castled.allium.addon;

import codes.castled.allium.api.ApiRange;
import codes.castled.allium.api.ApiVersion;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Whether a plugin may use the Allium API, decided once per plugin from its {@code plugin.yml}
 * declaration and a probe of its own classloader.
 */
public record AddonVerdict(String plugin, Status status, @Nullable String declared, String message) {

    public enum Status {
        /** Declares a range that includes this API version. */
        ACCEPTED,
        /** Declares no {@code allium-api} range and bundles no API classes: not an API addon. */
        NOT_AN_ADDON,
        /** The declared range does not include this API version. */
        VERSION_MISMATCH,
        /** The declaration does not parse. */
        INVALID_DECLARATION,
        /** The plugin's jar contains its own copy of the API classes. */
        SHADED_API
    }

    public boolean refused() {
        return status == Status.VERSION_MISMATCH || status == Status.INVALID_DECLARATION || status == Status.SHADED_API;
    }

    /** Pure decision: shading wins, then the declaration. */
    public static AddonVerdict evaluate(String plugin, @Nullable String declared, ApiVersion provided, List<String> shadedClasses) {
        if (!shadedClasses.isEmpty()) {
            return new AddonVerdict(plugin, Status.SHADED_API, declared, plugin + " bundles its own copy of the Allium API ("
                    + String.join(", ", shadedClasses) + "). Allium refuses it: rebuild it with the Allium API as compileOnly, "
                    + "never shaded.");
        }
        if (declared == null || declared.isBlank()) {
            return new AddonVerdict(plugin, Status.NOT_AN_ADDON, null, plugin + " declares no allium-api range.");
        }
        ApiRange range;
        try {
            range = ApiRange.parse(declared);
        } catch (IllegalArgumentException e) {
            return new AddonVerdict(plugin, Status.INVALID_DECLARATION, declared, plugin + " declares allium-api: \"" + declared
                    + "\", which is not a valid range (" + e.getMessage() + "). Use for example \"^1.0\" or \">=1.0 <2.0\".");
        }
        if (!range.contains(provided)) {
            return new AddonVerdict(plugin, Status.VERSION_MISMATCH, declared, plugin + " needs Allium API " + declared + " ("
                    + range.describe() + "), but this Allium provides API " + provided + ". Install an Allium whose API version "
                    + "is in that range, or a build of " + plugin + " made for API " + provided.major() + "." + provided.minor() + ".");
        }
        return new AddonVerdict(plugin, Status.ACCEPTED, declared, plugin + " uses Allium API " + declared + " (provided " + provided + ").");
    }
}
