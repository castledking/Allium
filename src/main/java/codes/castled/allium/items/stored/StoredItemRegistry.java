package codes.castled.allium.items.stored;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.items.CustomItemRegistry;
import codes.castled.allium.managers.core.Text;

import static codes.castled.allium.managers.core.Text.DebugSeverity.*;

/**
 * Owns {@code plugins/Allium/items/} — one yml per stored item.
 * <p>
 * A file per item keeps definitions easy to hand-edit, diff and drop in, and stops one item's
 * unreadable snapshot from taking the rest down with it. Every definition loaded here is also
 * registered into {@link CustomItemRegistry} so it resolves through the {@code ci:} prefix.
 */
public final class StoredItemRegistry {

    private static final String DIRECTORY = "items";
    private static final String EXTENSION = ".yml";

    private static StoredItemRegistry instance;

    private final Plugin plugin;
    private final CustomItemRegistry customItems;
    private final File directory;
    /** Sorted so list output and tab completion come out in a stable order. */
    private final Map<String, StoredItem> items = new TreeMap<>();

    public StoredItemRegistry(Plugin plugin, CustomItemRegistry customItems) {
        this.plugin = plugin;
        this.customItems = customItems;
        this.directory = new File(plugin.getDataFolder(), DIRECTORY);
        instance = this;
    }

    public static StoredItemRegistry getInstance() {
        return instance;
    }

    /**
     * Reloads every definition from disk.
     * <p>
     * Unregisters only the ids this registry previously owned, leaving Allium's built-in behaviour
     * items (tree axe, spawner changer, item renamer) registered.
     */
    public void loadAll() {
        for (String id : items.keySet()) {
            customItems.unregister(id);
        }
        items.clear();

        if (!directory.exists() && !directory.mkdirs()) {
            Text.sendDebugLog(WARN, "Could not create stored items directory: " + directory.getPath());
            return;
        }

        File[] files = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(EXTENSION));
        if (files == null) {
            return;
        }

        int failed = 0;
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - EXTENSION.length());
            try {
                requireNotBuiltIn(StoredItem.normaliseId(id));
                StoredItem item = StoredItem.load(id, YamlConfiguration.loadConfiguration(file));
                items.put(item.getId(), item);
                customItems.register(new StoredCustomItem(plugin, item));
            } catch (Exception e) {
                failed++;
                Text.sendDebugLog(WARN, "Skipping stored item '" + file.getName() + "': " + e.getMessage());
            }
        }

        Text.sendDebugLog(INFO, "Loaded " + items.size() + " stored item(s) from " + directory.getPath()
                + (failed > 0 ? " (" + failed + " skipped)" : ""));
    }

    /**
     * Captures the given stack under {@code id} and writes it to disk.
     *
     * @return the stored definition
     * @throws IllegalArgumentException if the id is unusable
     * @throws IOException              if the file could not be written
     */
    public StoredItem add(String id, ItemStack source) throws IOException {
        String normalised = StoredItem.normaliseId(id);
        if (!StoredItem.isValidId(normalised)) {
            throw new IllegalArgumentException("ids may only contain a-z, 0-9, _ and -");
        }
        requireNotBuiltIn(normalised);
        return persist(StoredItem.capture(normalised, source));
    }

    /**
     * Rejects ids already owned by one of Allium's behaviour items.
     * <p>
     * Registering over one would not merely hide it: managers such as ItemRenamerManager look their
     * tool up by id and cast the result, so a stored item under that id turns every lookup into a
     * ClassCastException.
     */
    private void requireNotBuiltIn(String id) {
        var existing = customItems.getItem(id);
        if (existing != null && !(existing instanceof StoredCustomItem)) {
            throw new IllegalArgumentException("'" + id + "' is a built-in item id");
        }
    }

    /** Re-captures an existing definition from a live stack, keeping its Allium options. */
    public StoredItem update(String id, ItemStack source) throws IOException {
        StoredItem existing = get(id);
        if (existing == null) {
            throw new IllegalArgumentException("no stored item named '" + id + "'");
        }
        return persist(existing.recapture(source));
    }

    private StoredItem persist(StoredItem item) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("could not create " + directory.getPath());
        }
        item.toConfig().save(new File(directory, item.getId() + EXTENSION));
        items.put(item.getId(), item);
        customItems.register(new StoredCustomItem(plugin, item));
        return item;
    }

    /** Deletes a definition and its file. Returns false when no such id was stored. */
    public boolean remove(String id) {
        String normalised = StoredItem.normaliseId(id);
        if (normalised == null || items.remove(normalised) == null) {
            return false;
        }
        customItems.unregister(normalised);
        File file = new File(directory, normalised + EXTENSION);
        if (file.exists() && !file.delete()) {
            Text.sendDebugLog(WARN, "Removed stored item '" + normalised
                    + "' but could not delete " + file.getPath() + "; it will return on reload");
        }
        return true;
    }

    public StoredItem get(String id) {
        String normalised = StoredItem.normaliseId(id);
        return normalised == null ? null : items.get(normalised);
    }

    public boolean has(String id) {
        return get(id) != null;
    }

    /** Snapshot copy, so callers can iterate while a give or reload mutates the registry. */
    public Set<String> getIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(items.keySet()));
    }

    public Collection<StoredItem> getAll() {
        return Collections.unmodifiableCollection(new ArrayList<>(items.values()));
    }

    public int size() {
        return items.size();
    }

    public File getDirectory() {
        return directory;
    }
}
