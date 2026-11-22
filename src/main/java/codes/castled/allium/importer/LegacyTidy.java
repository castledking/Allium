package codes.castled.allium.importer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Archives only retired Allium paths. Every archive is a new directory on the same filesystem. */
public final class LegacyTidy {
    private static final String ARCHIVES = "import/backups";
    private static final List<String> RETIRED = List.of("harvest", "animations.yml", "changed_spawners.yml", "chat",
            "commands.yml", "customitems.yml", "datapacks", "dialogs", "frames-built.json", "frames-overrides.yml",
            "frames.yml", "items", "itemdb.yml.backup", "maintenance.yml", "modguard", "player-channels.yml",
            "reliques", "resourcepack.yml", "spawner_heads.yml", "tradingcards", "voting", "config.legacy.yml",
            "lang.yml", "vouchers.yml");
    private static final Set<String> H2_FILES = Set.of("sfcore.mv.db", "sfcore.trace.db");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    public record Plan(List<Path> paths, List<Path> skipped) {}
    public record Result(Path archive, List<Path> moved, List<Path> failed) {}

    private LegacyTidy() {}

    public static Plan plan(Path base) throws IOException {
        List<Path> paths = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        for (String name : RETIRED) consider(base, Path.of(name), paths, skipped);
        Path database = base.resolve("database");
        if (Files.isDirectory(database, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(database)) {
                List<Path> entries = children.toList();
                if (!entries.isEmpty() && entries.stream().allMatch(path -> H2_FILES.contains(path.getFileName().toString())
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
                    paths.add(Path.of("database"));
                } else if (!entries.isEmpty()) {
                    entries.stream().map(base::relativize).sorted().forEach(path -> {
                        if (H2_FILES.contains(path.getFileName().toString())) consider(base, path, paths, skipped);
                        else skipped.add(path);
                    });
                }
            }
        } else if (Files.exists(database, LinkOption.NOFOLLOW_LINKS)) {
            skipped.add(Path.of("database"));
        }
        return new Plan(List.copyOf(paths), List.copyOf(skipped));
    }

    private static void consider(Path base, Path path, List<Path> paths, List<Path> skipped) {
        Path entry = base.resolve(path);
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            paths.add(path);
        } else if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
            skipped.add(path);
        }
    }

    public static Result apply(Path base, Plan plan) throws IOException {
        if (plan.paths().isEmpty()) return new Result(null, List.of(), List.of());
        Path root = base.resolve(ARCHIVES);
        if (Files.isSymbolicLink(root.getParent()) || Files.isSymbolicLink(root)) {
            throw new IOException("import or import/backups is a symbolic link");
        }
        Files.createDirectories(root);
        Path archive = Files.createTempDirectory(root, "tidy-" + TIME.format(Instant.now()) + "-");
        Path manifest = archive.resolve("manifest.txt");
        Files.writeString(manifest, "Allium legacy tidy archive\nPlanned moves:\n"
                + String.join("\n", plan.paths().stream().map(Path::toString).toList())
                + "\nResults:\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        List<Path> moved = new ArrayList<>();
        List<Path> failed = new ArrayList<>();
        for (Path relative : plan.paths()) {
            Path from = base.resolve(relative);
            Path to = archive.resolve(relative);
            String status;
            try {
                Files.createDirectories(to.getParent());
                move(from, to);
                moved.add(relative);
                status = "moved " + relative;
            } catch (IOException | OverlappingFileLockException error) {
                failed.add(relative);
                status = "kept " + relative + " (" + error.getClass().getSimpleName() + ")";
            }
            Files.writeString(manifest, status + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        return new Result(archive, List.copyOf(moved), List.copyOf(failed));
    }

    private static void move(Path from, Path to) throws IOException {
        if (Files.isSymbolicLink(from) || Files.isSymbolicLink(from.getParent())) {
            throw new IOException("symbolic link");
        }
        Path database = Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)
                ? from.resolve(from.getFileName().toString().equals("harvest") ? "harvest.mv.db" : "sfcore.mv.db")
                : from;
        if (!database.getFileName().toString().endsWith(".mv.db")
                || !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            return;
        }
        try (var channel = FileChannel.open(database, StandardOpenOption.READ);
             var lock = channel.tryLock(0, Long.MAX_VALUE, true)) {
            if (lock == null) throw new IOException("legacy database is open");
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /** Uses the active source first, or the newest archive that still contains this source. */
    public static Path source(Path base, String relative) throws IOException {
        Path active = base.resolve(relative);
        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) return active;
        Path root = base.resolve(ARCHIVES);
        if (Files.isSymbolicLink(root.getParent()) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return active;
        try (var archives = Files.list(root)) {
            for (Path archive : archives.filter(a -> Files.isDirectory(a, LinkOption.NOFOLLOW_LINKS)
                            && a.getFileName().toString().startsWith("tidy-")).sorted(Comparator.reverseOrder()).toList()) {
                Path archived = archive.resolve(relative);
                if (Files.isRegularFile(archived, LinkOption.NOFOLLOW_LINKS)) return archived;
            }
        }
        return active;
    }
}
