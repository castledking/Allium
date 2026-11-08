package codes.castled.allium.scheduler;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scheduler boundary: production code schedules work only through {@code TaskScheduler}.
 * Threads, executors and the Bukkit scheduler appear only in the scheduler packages.
 */
class SchedulerBoundaryTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "Bukkit\\.getScheduler\\(|getServer\\(\\)\\.getScheduler\\(|BukkitRunnable|org\\.bukkit\\.scheduler"
                    + "|new Thread\\(|Executors\\.|new ThreadPoolExecutor\\(|new ScheduledThreadPoolExecutor\\("
                    + "|ForkJoinPool|\\.supplyAsync\\(|\\.runAsync\\(|delayedExecutor\\(|new java\\.util\\.Timer\\(");

    @Test
    void productionCodeSchedulesOnlyThroughTheScheduler() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (String root : List.of("src/main/java", "src/paperCommands/java", "allium-core/src/main/java")) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    scanned++;
                    if (file.toString().replace('\\', '/').contains("/codes/castled/allium/scheduler/")) {
                        continue;
                    }
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).trim();
                        if (!line.startsWith("*") && !line.startsWith("//") && FORBIDDEN.matcher(line).find()) {
                            violations.add(file + ":" + (i + 1) + ": " + line);
                        }
                    }
                }
            }
        }
        assertTrue(scanned > 100, "the scan found the sources (" + scanned + " files)");
        assertEquals(List.of(), violations, "schedule through TaskScheduler (PluginStart#getScheduler) instead");
    }
}
