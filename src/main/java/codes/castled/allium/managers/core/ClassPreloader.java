package codes.castled.allium.managers.core;

import java.io.File;
import java.net.URI;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/**
 * Loads every Allium class up front so that replacing the plugin jar on a
 * running server cannot break code that had not run yet.
 *
 * <h2>Why this exists</h2>
 *
 * A plugin's classloader reads classes from the jar lazily — a class is only
 * pulled off disk the first time it is actually needed. Overwriting the jar
 * while the server is running leaves the classloader pointing at a file whose
 * contents have changed underneath it, so anything not yet loaded fails with
 * {@code NoClassDefFoundError}:
 *
 * <pre>
 *   Caused by: java.lang.NoClassDefFoundError:
 *       codes/castled/allium/managers/lang/Lang$MessageBundle
 * </pre>
 *
 * That example is typical: a nested class used only when a particular message
 * is first formatted. Everything works until the one code path that needs it
 * runs — often the restart command itself, which is the worst possible moment.
 *
 * <p>Loading everything during startup, while the original jar is still intact,
 * removes that failure mode for Allium's own classes.
 *
 * <h2>What this does not fix</h2>
 *
 * This is damage control, not a licence to hot-swap. It cannot help with
 * classes owned by other plugins or by the server, it cannot make an already
 * running instance pick up new code, and it cannot help if the jar is replaced
 * before this has run. <strong>The supported way to update a plugin is to put
 * the new jar in {@code plugins/update/} and restart</strong>; the server then
 * swaps it while nothing is using it.
 *
 * <h2>Why loading without initialising</h2>
 *
 * Classes are resolved with {@code initialize = false}. That reads and defines
 * each class — which is the part that needs the jar — without running its
 * static initialisers, so no side effects fire early and nothing observable
 * changes about plugin startup. Initialisation still happens naturally on
 * first real use, and by then the class no longer needs the file on disk.
 */
public final class ClassPreloader {

    private static final String PACKAGE_PREFIX = "codes/castled/allium/";

    private ClassPreloader() {
    }

    /**
     * Loads every class in the plugin jar.
     *
     * @return the number of classes loaded, or {@code 0} if the jar could not
     *         be read (running unpacked from an IDE, for instance)
     */
    public static int preloadAll(Plugin plugin) {
        Logger logger = plugin.getLogger();
        File jar = jarFile(plugin);
        if (jar == null || !jar.isFile()) {
            // Running from a directory rather than a jar: there is no file to
            // swap, so there is nothing to protect against.
            return 0;
        }

        long startedAt = System.nanoTime();
        ClassLoader loader = plugin.getClass().getClassLoader();
        int loaded = 0;
        int failed = 0;

        try (JarFile jarContents = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jarContents.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()
                    || !name.startsWith(PACKAGE_PREFIX)
                    || !name.endsWith(".class")) {
                    continue;
                }
                String className = name
                    .substring(0, name.length() - ".class".length())
                    .replace('/', '.');
                try {
                    Class.forName(className, false, loader);
                    loaded++;
                } catch (Throwable t) {
                    // A class that cannot load here would not have loaded on
                    // demand either — usually an optional integration whose
                    // plugin is absent. Not fatal, and not worth a warning each.
                    failed++;
                }
            }
        } catch (Exception e) {
            logger.warning("Could not preload classes (" + e.getMessage()
                + "). The plugin works normally, but replacing the jar while the"
                + " server is running may cause NoClassDefFoundError.");
            return 0;
        }

        long millis = (System.nanoTime() - startedAt) / 1_000_000L;
        logger.info("Preloaded " + loaded + " classes in " + millis + "ms"
            + (failed > 0 ? " (" + failed + " skipped — optional integrations)" : ""));
        return loaded;
    }

    private static File jarFile(Plugin plugin) {
        try {
            URI uri = plugin.getClass().getProtectionDomain()
                .getCodeSource().getLocation().toURI();
            return new File(uri);
        } catch (Exception e) {
            return null;
        }
    }
}
