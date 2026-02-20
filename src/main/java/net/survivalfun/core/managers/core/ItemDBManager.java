package net.survivalfun.core.managers.core;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;

import java.util.zip.GZIPInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import static net.survivalfun.core.managers.core.Text.DebugSeverity.*;

/**
 * Manages ItemDB updates and migrations.
 * Handles one-time replacement of old itemdb.yml with new version.
 */
public class ItemDBManager {

    private final PluginStart plugin;
    private final Database database;

    public ItemDBManager(PluginStart plugin) {
        this.plugin = plugin;
        this.database = plugin.getDatabase();
    }

    /**
     * Checks if ItemDB has been updated and performs the update if needed.
     * This is a one-time operation that replaces the old itemdb.yml.
     */
    public void checkAndUpdateItemDB() {
        try {
            // Check if the update has already been performed
            boolean hasUpdated = hasItemDBBeenUpdated();
            if (hasUpdated) {
                Text.sendDebugLog(INFO, "ItemDB already updated, skipping...");
                return;
            }

            Text.sendDebugLog(INFO, "Starting ItemDB update process...");

            // Create the new itemdb.yml content
            String newItemDBContent = generateNewItemDBContent();
            
            if (newItemDBContent == null) {
                Text.sendDebugLog(WARN, "ItemDB update skipped - could not generate new content");
                return;
            }

            // Get the plugins/Allium folder path
            Path alliumFolder = Paths.get(plugin.getDataFolder().getParent(), "Allium");

            if (!alliumFolder.toFile().exists()) {
                Text.sendDebugLog(WARN, "Allium folder not found at: " + alliumFolder.toString());
                return;
            }

            // Backup the old itemdb.yml if it exists
            Path oldItemDB = alliumFolder.resolve("itemdb.yml");
            Path backupItemDB = alliumFolder.resolve("itemdb.yml.backup");

            if (oldItemDB.toFile().exists()) {
                Text.sendDebugLog(INFO, "Backing up existing itemdb.yml...");
                Files.copy(oldItemDB, backupItemDB, StandardCopyOption.REPLACE_EXISTING);
            }

            // Write the new itemdb.yml
            Text.sendDebugLog(INFO, "Writing new itemdb.yml...");
            Files.write(oldItemDB, newItemDBContent.getBytes());

            // Mark the update as completed in the database
            markItemDBAsUpdated();

            Text.sendDebugLog(INFO, "ItemDB update completed successfully!");

        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to update ItemDB", e);
        }
    }

    /**
     * Checks if the ItemDB update has been performed by querying the database.
     */
    private boolean hasItemDBBeenUpdated() {
        String value = database.queryString("SELECT \"value\" FROM server_data WHERE \"key\" = ?", "itemdb_updated");
        return "true".equals(value);
    }

    /**
     * Marks the ItemDB update as completed in the database.
     */
    private void markItemDBAsUpdated() {
        try {
            database.executeUpdate("REPLACE INTO server_data (\"key\", \"value\") VALUES (?, ?)", "itemdb_updated", "true");
        } catch (Exception e) {
            Text.sendDebugLog(WARN, "Failed to mark ItemDB as updated in database", e);
        }
    }

    /**
     * Generates the new ItemDB content.
     * Reads from itemdb.yml.gz in resources and decompresses it.
     */
    private String generateNewItemDBContent() {
        StringBuilder content = new StringBuilder();

        try {
            // Read the itemdb.yml.gz from resources
            InputStream resourceStream = plugin.getClass().getResourceAsStream("/itemdb.yml.gz");
            if (resourceStream != null) {
                // Decompress gzipped content
                GZIPInputStream gzipStream = new GZIPInputStream(resourceStream);
                BufferedReader reader = new BufferedReader(new InputStreamReader(gzipStream));
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                reader.close();
                Text.sendDebugLog(INFO, "Successfully loaded and decompressed itemdb.yml.gz from resources");
            } else {
                Text.sendDebugLog(ERROR, "Could not find itemdb.yml.gz in resources!");
                return null;
            }
        } catch (Exception e) {
            Text.sendDebugLog(ERROR, "Failed to read itemdb.yml.gz from resources", e);
            return null;
        }

        return content.toString();
    }
}
