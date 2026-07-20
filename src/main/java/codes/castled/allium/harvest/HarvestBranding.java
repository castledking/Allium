package codes.castled.allium.harvest;

/**
 * Single source of truth for every user-visible or persisted name the harvest
 * module uses. Rebranding the subsystem (or extracting it into a standalone
 * plugin) only requires touching this class, plugin.yml, and the resource
 * folder name.
 */
public final class HarvestBranding {

    /** Display name used in messages and logs. */
    public static final String DISPLAY_NAME = "Allium Harvest";

    /** Namespace used for PersistentDataContainer keys and NamespacedKeys. */
    public static final String NAMESPACE = "alliumharvest";

    /** Root command label (must match plugin.yml). */
    public static final String COMMAND = "harvest";

    /** Permission root, e.g. {@code allium.harvest.reload}. */
    public static final String PERMISSION_ROOT = "allium.harvest";

    /** Folder inside the plugin data folder holding all harvest configuration. */
    public static final String DATA_FOLDER = "harvest";

    /** Base file name for the local database (H2/SQLite). */
    public static final String DB_FILE_BASE = "harvest";

    private HarvestBranding() {}
}
