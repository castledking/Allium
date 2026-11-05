package codes.castled.allium.config;

import codes.castled.allium.PluginStart;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;

public final class BackupManager {

    private static final SimpleDateFormat DIR_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    public static final String HMAC_SECRET_FILENAME = ".item-hmac-secret";

    private final PluginStart plugin;
    private final File backupRoot;

    public BackupManager(PluginStart plugin) {
        this.plugin = plugin;
        this.backupRoot = new File(plugin.getDataFolder(), "backups");
    }

    public File backup(File file) {
        if (!file.exists()) return null;

        File timestampDir = new File(backupRoot, DIR_FORMAT.format(new Date()));
        File relativePath = relativize(plugin.getDataFolder(), file);
        File destination = new File(timestampDir, relativePath.getPath());

        File parentDir = destination.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            plugin.logger().severe("Failed to create backup directory: " + parentDir.getAbsolutePath());
            return null;
        }

        try {
            Files.copy(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (HMAC_SECRET_FILENAME.equals(file.getName())) {
                try {
                    Files.setPosixFilePermissions(
                            destination.toPath(),
                            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                    );
                } catch (UnsupportedOperationException ignored) {
                }
            }
            plugin.logger().debug("Backed up " + file.getName() + " -> " + destination.getAbsolutePath());
            return destination;
        } catch (IOException e) {
            plugin.logger().severe("Failed to backup " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    public void backupAll() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) return;

        int count = 0;

        File[] ymlFiles = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (ymlFiles != null) {
            for (File file : ymlFiles) {
                if (backup(file) != null) count++;
            }
        }

        File secretFile = new File(dataFolder, HMAC_SECRET_FILENAME);
        if (secretFile.isFile() && backup(secretFile) != null) {
            count++;
        }

        File langDir = new File(dataFolder, "lang");
        if (langDir.isDirectory()) {
            File[] langFiles = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
            if (langFiles != null) {
                for (File file : langFiles) {
                    if (backup(file) != null) count++;
                }
            }
        }

        plugin.logger().info("Backed up " + count + " file(s).");
    }

    public void pruneOldBackups(int maxKeep) {
        if (!backupRoot.exists()) return;
        File[] dirs = backupRoot.listFiles(File::isDirectory);
        if (dirs == null || dirs.length <= maxKeep) return;

        java.util.Arrays.sort(dirs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int toRemove = dirs.length - maxKeep;
        for (int i = 0; i < toRemove; i++) {
            deleteRecursive(dirs[i]);
            plugin.logger().debug("Pruned old backup: " + dirs[i].getName());
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    private File relativize(File base, File target) {
        return base.toPath().relativize(target.toPath()).toFile();
    }

    public File getBackupRoot() {
        return backupRoot;
    }
}
