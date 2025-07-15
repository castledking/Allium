package net.survivalfun.core.managers.migration;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Handles migration from SFCore to Allium plugin
 * Detects SFCore folder and moves its contents to Allium folder on startup
 */
public class MigrationManager {
    
    private final Logger logger;
    private final File pluginsDirectory;
    private final File currentPluginFolder;
    
    public MigrationManager(Logger logger, File dataFolder) {
        this.logger = logger;
        this.currentPluginFolder = dataFolder;
        this.pluginsDirectory = dataFolder.getParentFile();
    }
    
    public void performMigration() {
        File sfcoreFolder = new File(pluginsDirectory, "SFCore");
        if (!sfcoreFolder.exists() || !sfcoreFolder.isDirectory()) {
            logger.info("No SFCore folder found, skipping migration.");
            return;
        }
        logger.info("SFCore folder detected! Starting migration to Allium...");
        try {
            if (!currentPluginFolder.exists()) {
                if (!currentPluginFolder.mkdirs()) {
                    logger.severe("Failed to create Allium plugin folder!");
                    return;
                }
            }
            // Preserve existing config.yml
            File alliumConfig = new File(currentPluginFolder, "config.yml");
            if (alliumConfig.exists()) {
                logger.info("Allium config.yml exists, skipping migration of config.yml");
                migrateFolderContents(sfcoreFolder, currentPluginFolder, true);
            } else {
                migrateFolderContents(sfcoreFolder, currentPluginFolder, false);
            }
            createBackup(sfcoreFolder);
            deleteDirectory(sfcoreFolder);
            logger.info("Migration completed successfully! SFCore data has been moved to Allium.");
            logger.info("A backup of the original SFCore folder has been created as 'SFCore_backup'.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error during migration from SFCore to Allium!", e);
        }
    }
    
    private void migrateFolderContents(File source, File destination, boolean skipConfig) throws IOException {
        File[] files = source.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (skipConfig && file.getName().equals("config.yml")) {
                logger.info("Skipping migration of config.yml from SFCore");
                continue;
            }
            File targetFile = new File(destination, file.getName());
            if (file.isDirectory()) {
                if (!targetFile.exists()) {
                    if (!targetFile.mkdirs()) {
                        logger.warning("Failed to create directory: " + targetFile.getAbsolutePath());
                        continue;
                    }
                }
                migrateFolderContents(file, targetFile, skipConfig);
            } else {
                if (targetFile.exists()) {
                    logger.info("File already exists in Allium folder, skipping: " + file.getName());
                    continue;
                }
                try {
                    Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Migrated file: " + file.getName());
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to migrate file: " + file.getName(), e);
                }
            }
        }
    }
    
    /**
     * Creates a backup of the SFCore folder before deletion
     */
    private void createBackup(File sfcoreFolder) {
        File backupFolder = new File(pluginsDirectory, "SFCore_backup");
        
        // Remove existing backup if it exists
        if (backupFolder.exists()) {
            try {
                deleteDirectory(backupFolder);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to remove existing backup folder", e);
            }
        }
        
        try {
            copyDirectory(sfcoreFolder.toPath(), backupFolder.toPath());
            logger.info("Created backup: " + backupFolder.getAbsolutePath());
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to create backup of SFCore folder", e);
        }
    }
    
    /**
     * Copies a directory and all its contents recursively
     */
    private void copyDirectory(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = destination.resolve(source.relativize(dir));
                try {
                    Files.copy(dir, targetDir);
                } catch (FileAlreadyExistsException e) {
                    if (!Files.isDirectory(targetDir)) {
                        throw e;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    /**
     * Recursively deletes a directory and all its contents
     */
    private void deleteDirectory(File directory) throws IOException {
        if (!directory.exists()) return;
        
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    /**
     * Checks if migration is needed (SFCore folder exists)
     */
    public boolean isMigrationNeeded() {
        File sfcoreFolder = new File(pluginsDirectory, "SFCore");
        return sfcoreFolder.exists() && sfcoreFolder.isDirectory();
    }
    
    /**
     * Gets the path to the old SFCore folder
     */
    public File getSFCoreFolder() {
        return new File(pluginsDirectory, "SFCore");
    }
    
    /**
     * Gets the path to the current Allium folder
     */
    public File getAlliumFolder() {
        return currentPluginFolder;
    }
}