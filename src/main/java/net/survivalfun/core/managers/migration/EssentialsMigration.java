package net.survivalfun.core.managers.migration;

import net.survivalfun.core.PluginStart;
import net.survivalfun.core.managers.DB.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Migrates data from Essentials userdata and warps into Allium's database.
 * Expects: path/userdata/*.yml (UUID.yml); warps either as path/warps.yml (warps: { name: { ... } })
 * or path/warps/ directory with &lt;warpName&gt;.yml files (one location per file, root-level world-name, x, y, z, yaw, pitch).
 */
public class EssentialsMigration {

    private final Logger logger;
    private final Database database;
    private final File basePath;

    public EssentialsMigration(Logger logger, Database database, File basePath) {
        this.logger = logger;
        this.database = database;
        this.basePath = basePath;
    }

    /**
     * @return base path (e.g. plugins/Essentials or extracted archive root)
     */
    public File getBasePath() {
        return basePath;
    }

    public File getUserdataFolder() {
        return new File(basePath, "userdata");
    }

    public File getWarpsFile() {
        return new File(basePath, "warps.yml");
    }

    /** Warps directory: one &lt;warpName&gt;.yml per warp (Essentials format). */
    public File getWarpsDir() {
        return new File(basePath, "warps");
    }

    public boolean hasUserdata() {
        File userdata = getUserdataFolder();
        return userdata.isDirectory() && userdata.listFiles() != null && userdata.listFiles().length > 0;
    }

    public boolean hasWarpsFile() {
        return getWarpsFile().isFile();
    }

    /** True if warps can be migrated: either warps.yml or warps/ directory with .yml files. */
    public boolean hasWarps() {
        if (getWarpsFile().isFile()) return true;
        File dir = getWarpsDir();
        if (!dir.isDirectory()) return false;
        File[] yml = dir.listFiles((d, name) -> name != null && name.endsWith(".yml"));
        return yml != null && yml.length > 0;
    }

    /**
     * Migrate homes from Essentials userdata into Allium DB.
     * For each player, sets max_homes to max(existing Allium max, number of homes migrated).
     */
    public MigrationResult migrateHomes() {
        MigrationResult result = new MigrationResult("homes");
        File userdata = getUserdataFolder();
        if (!userdata.isDirectory()) {
            result.addError("userdata folder not found: " + userdata.getAbsolutePath());
            return result;
        }
        File[] files = userdata.listFiles((dir, name) -> name.endsWith(".yml") && name.length() >= 36);
        if (files == null) {
            result.addError("Could not list userdata folder");
            return result;
        }
        for (File file : files) {
            String name = file.getName();
            String uuidStr = name.substring(0, name.length() - 4);
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                result.addSkipped("Invalid UUID in filename: " + name);
                continue;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection homesSection = config.getConfigurationSection("homes");
            if (homesSection == null || homesSection.getKeys(false).isEmpty()) {
                continue;
            }
            int migrated = 0;
            for (String homeName : homesSection.getKeys(false)) {
                ConfigurationSection loc = homesSection.getConfigurationSection(homeName);
                if (loc == null) continue;
                Location location = parseLocation(loc);
                if (location == null) {
                    result.addSkipped("Invalid home '" + homeName + "' for " + uuidStr + " (world not found)");
                    continue;
                }
                try {
                    database.savePlayerHome(uuid, homeName, location, System.currentTimeMillis());
                    migrated++;
                } catch (Exception e) {
                    result.addError("Failed to save home " + homeName + " for " + uuidStr + ": " + e.getMessage());
                }
            }
            if (migrated > 0) {
                int existingMax = database.getPlayerMaxHomes(uuid);
                int newMax = Math.max(existingMax >= 0 ? existingMax : 0, migrated);
                database.setPlayerMaxHomes(uuid, newMax);
                result.addSuccess(uuidStr, migrated + " homes, max_homes set to " + newMax);
            }
        }
        return result;
    }

    /**
     * Migrate economy (money) from Essentials userdata into Allium DB.
     */
    public MigrationResult migrateEconomy() {
        MigrationResult result = new MigrationResult("economy");
        File userdata = getUserdataFolder();
        if (!userdata.isDirectory()) {
            result.addError("userdata folder not found: " + userdata.getAbsolutePath());
            return result;
        }
        File[] files = userdata.listFiles((dir, name) -> name.endsWith(".yml") && name.length() >= 36);
        if (files == null) {
            result.addError("Could not list userdata folder");
            return result;
        }
        for (File file : files) {
            String name = file.getName();
            String uuidStr = name.substring(0, name.length() - 4);
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                result.addSkipped("Invalid UUID in filename: " + name);
                continue;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            Object moneyObj = config.get("money");
            if (moneyObj == null) continue;
            BigDecimal balance;
            try {
                if (moneyObj instanceof Number) {
                    balance = BigDecimal.valueOf(((Number) moneyObj).doubleValue());
                } else {
                    balance = new BigDecimal(moneyObj.toString().replace(",", "").trim());
                }
                balance = balance.setScale(2, java.math.RoundingMode.HALF_UP);
            } catch (Exception e) {
                result.addSkipped("Invalid money for " + uuidStr + ": " + moneyObj);
                continue;
            }
            try {
                database.getPlayerBalance(uuid); // ensure row exists
                database.setPlayerBalance(uuid, balance);
                result.addSuccess(uuidStr, "balance " + balance);
            } catch (Exception e) {
                result.addError("Failed to set balance for " + uuidStr + ": " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * Migrate warp locations into Allium DB.
     * Supports: (1) warps/ directory with &lt;warpName&gt;.yml per warp (root-level world-name, x, y, z, yaw, pitch),
     * or (2) warps.yml with warps: { name: { world, x, y, z, yaw?, pitch? } }.
     */
    public MigrationResult migrateWarps() {
        MigrationResult result = new MigrationResult("warps");
        UUID consoleUuid = new UUID(0, 0);
        String consoleName = "EssentialsMigration";

        // 1) Warps directory: one .yml file per warp
        File warpsDir = getWarpsDir();
        if (warpsDir.isDirectory()) {
            File[] files = warpsDir.listFiles((dir, name) -> name != null && name.endsWith(".yml"));
            if (files != null) {
                for (File file : files) {
                    String warpName = file.getName();
                    if (warpName.endsWith(".yml")) {
                        warpName = warpName.substring(0, warpName.length() - 4);
                    }
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                    Location location = parseLocation(config);
                    if (location == null) {
                        result.addSkipped("Warp '" + warpName + "': world not found");
                        continue;
                    }
                    try {
                        boolean ok = database.setWarp(warpName, location, consoleUuid, consoleName);
                        if (ok) {
                            result.addSuccess(warpName, "warp");
                        } else {
                            result.addError("Failed to set warp: " + warpName);
                        }
                    } catch (Exception e) {
                        result.addError("Warp " + warpName + ": " + e.getMessage());
                    }
                }
            }
        }

        // 2) Single warps.yml with warps section (legacy)
        File warpsFile = getWarpsFile();
        if (warpsFile.isFile()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(warpsFile);
            ConfigurationSection warpsSection = config.getConfigurationSection("warps");
            if (warpsSection != null) {
                for (String warpName : warpsSection.getKeys(false)) {
                    ConfigurationSection loc = warpsSection.getConfigurationSection(warpName);
                    if (loc == null) continue;
                    Location location = parseLocation(loc);
                    if (location == null) {
                        result.addSkipped("Warp '" + warpName + "': world not found");
                        continue;
                    }
                    try {
                        boolean ok = database.setWarp(warpName, location, consoleUuid, consoleName);
                        if (ok) {
                            result.addSuccess(warpName, "warp");
                        } else {
                            result.addError("Failed to set warp: " + warpName);
                        }
                    } catch (Exception e) {
                        result.addError("Warp " + warpName + ": " + e.getMessage());
                    }
                }
            }
        }

        if (result.getSuccess().isEmpty() && result.getErrors().isEmpty() && result.getSkipped().isEmpty()) {
            result.addError("No warps source found: neither warps/ directory nor warps.yml");
        }
        return result;
    }

    /**
     * Parse Essentials location section (world or world-name, x, y, z, yaw, pitch).
     */
    private Location parseLocation(ConfigurationSection section) {
        String worldName = section.getString("world-name");
        if (worldName == null) {
            worldName = section.getString("world");
        }
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            // Try UUID form
            try {
                UUID worldUuid = UUID.fromString(worldName);
                world = Bukkit.getWorld(worldUuid);
            } catch (Exception ignored) {
            }
        }
        if (world == null) return null;
        double x = section.getDouble("x", 0);
        double y = section.getDouble("y", 0);
        double z = section.getDouble("z", 0);
        float yaw = (float) section.getDouble("yaw", 0);
        float pitch = (float) section.getDouble("pitch", 0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    public static final class MigrationResult {
        private final String type;
        private final List<String> success = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();

        public MigrationResult(String type) {
            this.type = type;
        }

        void addSuccess(String id, String detail) {
            success.add(id + ": " + detail);
        }

        void addError(String message) {
            errors.add(message);
        }

        void addSkipped(String message) {
            skipped.add(message);
        }

        public String getType() {
            return type;
        }

        public List<String> getSuccess() {
            return success;
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getSkipped() {
            return skipped;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }
}
