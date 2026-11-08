package codes.castled.allium.pack;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code resource-pack} settings: who delivers Allium's core pack, and how.
 *
 * @param url            standalone: where the operator hosts the exported pack
 * @param verifyHostedFile standalone: download the URL once at startup and send it only if its SHA-1 matches
 * @param required       players must load the pack; declining or failing kicks them with {@code kickMessage}
 * @param prompt         the text the client shows with the offer (Allium formatting)
 * @param mixerDirectory rpm-export: ResourcePackManager's mixer, relative to {@code plugins/} or absolute
 * @param mixerFileName  rpm-export: the file Allium writes there (and the only one it ever writes)
 * @param dryRun         rpm-export: report what would be written and any carrier collisions, write nothing
 * @param packFile       a local pack to deliver instead of the bundled one (for example SF's art build),
 *                       relative to {@code plugins/Allium/}; blank for the bundled pack
 */
public record ResourcePackSettings(Owner owner, String url, boolean verifyHostedFile, boolean required, String prompt,
                                   String kickMessage, String mixerDirectory, String mixerFileName, boolean dryRun,
                                   String packFile, List<String> warnings) {

    public static final String DEFAULT_PROMPT = "&eThis pack shows Allium's custom tool models. Without it you see named vanilla items.";
    public static final String DEFAULT_KICK = "&cThis server requires its resource pack. Reconnect and accept it to play.";
    public static final String DEFAULT_MIXER = "ResourcePackManager/mixer";
    public static final String DEFAULT_MIXER_FILE = "Allium_core_resource_pack.zip";

    public enum Owner {
        /** Allium does not send or export the pack. */
        NONE("none"),
        /** Allium exports the pack, and sends the operator-hosted URL with its SHA-1. */
        STANDALONE("standalone"),
        /** Allium writes the pack into ResourcePackManager's mixer; RPM merges and sends it. */
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
        }
        String url = section == null ? "" : section.getString("standalone.url", "").trim();
        if (owner == Owner.STANDALONE && !(url.startsWith("https://") || url.startsWith("http://"))) {
            warnings.add("resource-pack.owner is standalone but resource-pack.standalone.url is not an http(s) URL; "
                    + "Allium exports the pack but sends nothing until it is set");
        }
        String file = section == null ? DEFAULT_MIXER_FILE : section.getString("rpm-export.file-name", DEFAULT_MIXER_FILE).trim();
        if (!file.matches("[A-Za-z0-9_.-]+\\.zip")) {
            warnings.add("resource-pack.rpm-export.file-name must be a plain .zip file name; using " + DEFAULT_MIXER_FILE);
            file = DEFAULT_MIXER_FILE;
        }
        return new ResourcePackSettings(owner, url,
                section == null || section.getBoolean("standalone.verify-hosted-file", true),
                section != null && section.getBoolean("required", false),
                section == null ? DEFAULT_PROMPT : section.getString("prompt", DEFAULT_PROMPT),
                section == null ? DEFAULT_KICK : section.getString("kick-message", DEFAULT_KICK),
                section == null ? DEFAULT_MIXER : section.getString("rpm-export.mixer-directory", DEFAULT_MIXER).trim(),
                file,
                section != null && section.getBoolean("rpm-export.dry-run", false),
                section == null ? "" : section.getString("pack-file", "").trim(),
                warnings);
    }

    public boolean sendsUrl() {
        return owner == Owner.STANDALONE && (url.startsWith("https://") || url.startsWith("http://"));
    }
}
