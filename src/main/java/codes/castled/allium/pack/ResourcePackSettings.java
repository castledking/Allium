package codes.castled.allium.pack;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code resource-pack} settings. Only the delivery owner exists so far, and only {@code none} is
 * implemented: Allium generates and bundles the core pack but never sends or exports it.
 */
public record ResourcePackSettings(Owner owner, List<String> warnings) {

    public enum Owner {
        /** Allium does not send or export the pack. */
        NONE("none"),
        /** Reserved: Allium would send the pack from a configured URL. */
        STANDALONE("standalone"),
        /** Reserved: Allium would export the pack into ResourcePackManager's mixer. */
        RPM_EXPORT("rpm-export");

        private final String configName;

        Owner(String configName) {
            this.configName = configName;
        }

        public String configName() {
            return configName;
        }
    }

    public ResourcePackSettings {
        warnings = List.copyOf(warnings);
    }

    public static ResourcePackSettings parse(@Nullable ConfigurationSection section) {
        List<String> warnings = new ArrayList<>();
        String raw = section == null ? "none" : section.getString("owner", "none");
        String normalized = raw == null ? "none" : raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        Owner owner = null;
        for (Owner candidate : Owner.values()) {
            if (candidate.configName.equals(normalized)) {
                owner = candidate;
            }
        }
        if (owner == null) {
            warnings.add("resource-pack.owner must be none, standalone or rpm-export; using none");
            owner = Owner.NONE;
        } else if (owner != Owner.NONE) {
            warnings.add("resource-pack.owner '" + owner.configName + "' is reserved for the delivery step and is not "
                    + "implemented yet; Allium does not send or export the core pack");
        }
        return new ResourcePackSettings(owner, warnings);
    }
}
