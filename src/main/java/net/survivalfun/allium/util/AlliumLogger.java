package net.survivalfun.allium.util;

import net.survivalfun.allium.Allium;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class AlliumLogger {

    private final Logger logger;
    private final Allium plugin;

    public AlliumLogger(Allium plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void info(String message) {
        logger.info(message);
    }

    public void warning(String message) {
        logger.warning(message);
    }

    public void severe(String message) {
        logger.severe(message);
    }

    public void debug(String message) {
        if (plugin.getConfig().getBoolean("settings.debug", false)) {
            logger.log(Level.INFO, "[DEBUG] " + message);
        }
    }
}
