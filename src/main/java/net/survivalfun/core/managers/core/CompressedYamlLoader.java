package net.survivalfun.core.managers.core;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.file.Files;
import java.util.logging.Logger;

public class CompressedYamlLoader {

    private static final Logger LOGGER = Logger.getLogger(CompressedYamlLoader.class.getName());

    public static YamlConfiguration loadCompressedYaml(String resourcePath, File dataFolder, Logger logger) {
        File targetFile = new File(dataFolder, resourcePath.replace(".gz", ""));
        
        try {
            InputStream resourceStream = CompressedYamlLoader.class.getClassLoader()
                    .getResourceAsStream(resourcePath);
            
            if (resourceStream != null) {
                if (!targetFile.exists() || needsUpdate(targetFile, resourceStream)) {
                    extractCompressedResource(resourcePath, targetFile);
                    if (logger != null) {
                        logger.info("Extracted compressed " + resourcePath + " to data folder");
                    }
                }
            } else {
                resourceStream = CompressedYamlLoader.class.getClassLoader()
                        .getResourceAsStream(resourcePath.replace(".gz", ""));
                if (resourceStream != null) {
                    resourcePath = resourcePath.replace(".gz", "");
                }
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("Failed to extract " + resourcePath + ": " + e.getMessage());
            }
        }

        return YamlConfiguration.loadConfiguration(targetFile);
    }

    public static InputStream getCompressedResourceStream(String resourcePath) {
        InputStream stream = CompressedYamlLoader.class.getClassLoader()
                .getResourceAsStream(resourcePath);
        
        if (stream == null) {
            stream = CompressedYamlLoader.class.getClassLoader()
                    .getResourceAsStream(resourcePath.replace(".gz", ""));
        }
        
        return stream;
    }

    private static boolean needsUpdate(File targetFile, InputStream newResourceStream) {
        return true;
    }

    public static void extractCompressedResource(String resourcePath, File targetFile) throws IOException {
        InputStream compressedStream = CompressedYamlLoader.class.getClassLoader()
                .getResourceAsStream(resourcePath);
        
        if (compressedStream == null) {
            String fallbackPath = resourcePath.replace(".gz", "");
            compressedStream = CompressedYamlLoader.class.getClassLoader()
                    .getResourceAsStream(fallbackPath);
            if (compressedStream == null) {
                throw new FileNotFoundException("Resource not found: " + resourcePath + " or " + fallbackPath);
            }
        }

        targetFile.getParentFile().mkdirs();

        try (InputStream gzipStream = new BufferedInputStream(compressedStream);
             java.util.zip.GZIPInputStream decompressor = new java.util.zip.GZIPInputStream(gzipStream);
             FileOutputStream out = new FileOutputStream(targetFile)) {
            
            byte[] buffer = new byte[8192];
            int len;
            while ((len = decompressor.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
    }

    public static YamlConfiguration loadFromInputStream(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            LOGGER.warning("Failed to load YAML from input stream: " + e.getMessage());
            return new YamlConfiguration();
        }
    }
}
