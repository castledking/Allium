package codes.castled.allium.tool.definition;

import codes.castled.allium.item.ProviderKey;

import java.util.Set;

/** A behavior's validated parameters for one tool definition. */
public interface ToolParameters {

    /**
     * Appearances besides {@code appearance.default} and {@code appearance.aliases} that also
     * identify as this tool, e.g. the Mob Disarmer's charge variants.
     */
    default Set<ProviderKey> extraAppearances() {
        return Set.of();
    }
}
