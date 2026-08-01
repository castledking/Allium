package codes.castled.allium.items.stored;

import java.util.Locale;
import java.util.regex.Pattern;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * One item definition stored under {@code plugins/Allium/items/<id>.yml}.
 * <p>
 * The {@code snapshot} is a Bukkit-serialized ItemStack and is losslessly complete on its own: it
 * carries vanilla components, ItemEdit's edits, ItemTag's persistent-data mechanics, and any
 * third-party tags the source item happened to have. The readable {@code vanilla}/{@code itemtag}
 * blocks layered on top of it are not yet written — see docs/CUSTOM_ITEM_STORAGE.md §3.
 */
public final class StoredItem {

    /**
     * Ids double as filenames and must stay interchangeable with ItemEdit's storage ids, whose
     * {@code ServerStorage.validateID} rejects spaces, dots and empties. This is stricter still so
     * an id can never escape the items directory.
     */
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_-]+");

    private static final boolean DEFAULT_NOTIFY_RECEIVERS = true;
    private static final int DEFAULT_MAX_GIVE = 64;

    private final String id;
    private final Material material;
    private final int amount;
    private final boolean notifyReceivers;
    private final String permission;
    private final int maxGive;
    private final ItemStack snapshot;
    private final VanillaMeta vanilla;

    private StoredItem(String id, Material material, int amount, boolean notifyReceivers,
                       String permission, int maxGive, ItemStack snapshot, VanillaMeta vanilla) {
        this.id = id;
        this.material = material;
        this.amount = amount;
        this.notifyReceivers = notifyReceivers;
        this.permission = permission;
        this.maxGive = maxGive;
        this.snapshot = snapshot;
        this.vanilla = vanilla;
    }

    /** Captures a live stack as a new definition, with Allium options at their defaults. */
    public static StoredItem capture(String id, ItemStack source) {
        ItemStack snapshot = source.clone();
        int amount = Math.max(1, snapshot.getAmount());
        // The snapshot is normalised to a single item so it stays a pure template; the held stack
        // size becomes the default give amount instead.
        snapshot.setAmount(1);
        return new StoredItem(normaliseId(id), snapshot.getType(), amount,
                DEFAULT_NOTIFY_RECEIVERS, null, DEFAULT_MAX_GIVE, snapshot, VanillaMeta.from(snapshot));
    }

    /** Re-captures a stack while keeping the existing definition's Allium options. */
    public StoredItem recapture(ItemStack source) {
        ItemStack updated = source.clone();
        int newAmount = Math.max(1, updated.getAmount());
        updated.setAmount(1);
        return new StoredItem(id, updated.getType(), newAmount, notifyReceivers, permission, maxGive,
                updated, VanillaMeta.from(updated));
    }

    /**
     * Reads a definition from disk.
     *
     * @throws IllegalArgumentException if the id is unusable or no material can be resolved
     */
    public static StoredItem load(String id, YamlConfiguration config) {
        String normalised = normaliseId(id);
        if (!isValidId(normalised)) {
            throw new IllegalArgumentException("invalid item id '" + id + "'");
        }

        // Deserialisation can fail on a snapshot written by a newer server; that is recoverable
        // (fall back to the bare material) and must not take the whole loader down.
        ItemStack snapshot = null;
        if (config.contains("snapshot")) {
            try {
                snapshot = config.getItemStack("snapshot");
            } catch (Exception e) {
                throw new IllegalArgumentException("unreadable snapshot: " + e.getMessage(), e);
            }
        }

        Material material = snapshot != null ? snapshot.getType() : matchMaterial(config.getString("material"));
        if (material == null || material == Material.AIR) {
            throw new IllegalArgumentException("no usable material"
                    + (config.contains("material") ? " for '" + config.getString("material") + "'" : ""));
        }

        ConfigurationSection allium = config.getConfigurationSection("allium");
        return new StoredItem(
                normalised,
                material,
                Math.max(1, config.getInt("amount", 1)),
                allium == null || allium.getBoolean("notify-receivers", DEFAULT_NOTIFY_RECEIVERS),
                allium == null ? null : emptyToNull(allium.getString("permission")),
                allium == null ? DEFAULT_MAX_GIVE : Math.max(1, allium.getInt("max-give", DEFAULT_MAX_GIVE)),
                snapshot,
                // Absent rather than empty: a file with no vanilla block leaves the snapshot's own
                // meta completely untouched, which is how step-2 files keep working.
                VanillaMeta.read(config.getConfigurationSection("vanilla")));
    }

    /** Renders this definition to a fresh configuration ready to be written out. */
    public YamlConfiguration toConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("id", id);
        config.set("material", material.name());
        config.set("amount", amount);

        config.set("allium.notify-receivers", notifyReceivers);
        config.set("allium.permission", permission == null ? "" : permission);
        config.set("allium.max-give", maxGive);

        if (vanilla != null) {
            vanilla.write(config.createSection("vanilla"));
        }

        if (snapshot != null) {
            config.set("snapshot", snapshot);
        }

        config.options().setHeader(java.util.Arrays.asList(
                "Allium stored item: " + id,
                "",
                "Give with:  /core item give <player> " + id,
                "            /give <player> ci:" + id + "    or    /i ci:" + id,
                "",
                "'snapshot' is a serialized ItemStack and the lossless base this item is built from.",
                "'vanilla' is applied on top of it, so editing a value there wins and deleting a",
                "value removes it from the item. Delete the whole 'vanilla' block to hand the",
                "snapshot back full control.",
                "",
                "Re-capture from the item in your hand with: /core item update " + id));
        return config;
    }

    /**
     * Builds a giveable stack: the snapshot is the base, then every domain Allium models is cleared
     * and re-applied from the readable blocks, so hand edits win and deletions take effect.
     * <p>
     * Falls back to a bare material stack when the definition carries no snapshot, which is how a
     * hand-authored file with no {@code snapshot:} block behaves.
     */
    public ItemStack toItemStack(int stackAmount) {
        ItemStack item = snapshot != null ? snapshot.clone() : new ItemStack(material);
        item.setAmount(Math.max(1, stackAmount));
        if (vanilla != null) {
            vanilla.applyTo(item, id);
        }
        return item;
    }

    public String getId() {
        return id;
    }

    public Material getMaterial() {
        return material;
    }

    /** Default give amount, taken from the stack size held when the item was captured. */
    public int getAmount() {
        return amount;
    }

    /** Whether the receiving player is told they got the item. */
    public boolean isNotifyReceivers() {
        return notifyReceivers;
    }

    /** Permission required to receive this item, or null for none. */
    public String getPermission() {
        return permission;
    }

    /** Upper bound on a single give. */
    public int getMaxGive() {
        return maxGive;
    }

    public boolean hasSnapshot() {
        return snapshot != null;
    }

    /** The readable vanilla block, or null when the file has none and the snapshot rules alone. */
    public VanillaMeta getVanilla() {
        return vanilla;
    }

    public static String normaliseId(String id) {
        return id == null ? null : id.trim().toLowerCase(Locale.ENGLISH);
    }

    public static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && VALID_ID.matcher(id).matches();
    }

    private static Material matchMaterial(String name) {
        return name == null || name.isEmpty() ? null : Material.matchMaterial(name);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
