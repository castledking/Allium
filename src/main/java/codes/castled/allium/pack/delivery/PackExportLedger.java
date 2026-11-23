package codes.castled.allium.pack.delivery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks mixer ZIP files written by Allium (by scope, normalized path, and SHA-1) inside
 * {@code plugins/Allium/pack/exported-mixer-packs.txt} so stale exports can be removed when an
 * addon switches delivery mode, is disabled/removed, or Allium changes pack ownership — while
 * never touching foreign or operator-modified ZIPs.
 */
final class PackExportLedger {

    static final String SCOPE_CORE = "core";
    static final String SCOPE_ADDON = "addon";
    static final String LEDGER_FILE = "exported-mixer-packs.txt";

    record Entry(String scope, Path path, String sha1) {}

    private PackExportLedger() {}

    static Path ledgerPath(Path dataFolder) {
        return dataFolder.resolve(PackExport.STANDALONE_DIRECTORY).resolve(LEDGER_FILE);
    }

    static List<Entry> read(Path dataFolder) {
        Path file = ledgerPath(dataFolder);
        if (!Files.isRegularFile(file)) return List.of();
        List<Entry> entries = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) continue;
                String scope = parts[0].trim();
                String sha1 = parts[1].trim();
                String pathText = parts[2].trim();
                if (scope.isEmpty() || sha1.isEmpty() || pathText.isEmpty()) continue;
                entries.add(new Entry(scope, Path.of(pathText).toAbsolutePath().normalize(), sha1));
            }
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
        return entries;
    }

    /**
     * Reconciles all previously recorded exports for {@code scope} against {@code activeOutputs}.
     * Deletes only files previously exported by Allium in {@code scope} that are no longer in
     * {@code activeOutputs} AND still match Allium's recorded SHA-1. Never modifies disk on dry run.
     */
    static List<Path> reconcile(
            Path dataFolder,
            String scope,
            Map<Path, String> activeOutputs,
            boolean dryRun,
            PackExport.Logs logs
    ) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(scope, "scope");
        Map<Path, String> normalizedActive = new LinkedHashMap<>();
        for (var e : activeOutputs.entrySet()) {
            normalizedActive.put(e.getKey().toAbsolutePath().normalize(), e.getValue());
        }

        List<Entry> existing = read(dataFolder);
        List<Entry> retained = new ArrayList<>();
        List<Path> removed = new ArrayList<>();
        String prefix = dryRun ? "[rpm-export dry run] " : "";

        for (Entry entry : existing) {
            if (!entry.scope().equals(scope)) {
                retained.add(entry);
                continue;
            }
            if (normalizedActive.containsKey(entry.path())) {
                continue; // Replaced below with current active entry.
            }
            if (!Files.isRegularFile(entry.path())) {
                continue; // Already absent.
            }
            try {
                String diskSha1 = PackExport.sha1(Files.readAllBytes(entry.path()));
                if (!diskSha1.equalsIgnoreCase(entry.sha1())) {
                    logs.warning().accept(prefix + "Kept stale mixer file " + entry.path()
                            + " because its contents differ from what Allium exported (foreign or manually edited).");
                    continue;
                }
                if (dryRun) {
                    logs.info().accept(prefix + "Would remove stale Allium export " + entry.path() + ".");
                    retained.add(entry);
                } else {
                    Files.deleteIfExists(entry.path());
                    removed.add(entry.path());
                    logs.info().accept("Removed stale Allium resource-pack export " + entry.path() + ".");
                }
            } catch (IOException e) {
                logs.warning().accept("Could not remove stale Allium export " + entry.path() + ": " + e.getMessage());
                retained.add(entry);
            }
        }

        if (dryRun) {
            return List.of();
        }

        for (var e : normalizedActive.entrySet()) {
            retained.add(new Entry(scope, e.getKey(), e.getValue()));
        }

        try {
            write(dataFolder, retained);
        } catch (IOException e) {
            logs.warning().accept("Could not update Allium resource-pack export ledger: " + e.getMessage());
        }
        return List.copyOf(removed);
    }

    private static void write(Path dataFolder, List<Entry> entries) throws IOException {
        Path file = ledgerPath(dataFolder);
        if (entries.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        StringBuilder out = new StringBuilder();
        out.append("# Allium-owned mixer exports (scope<TAB>sha1<TAB>path)\n");
        for (Entry entry : entries) {
            out.append(entry.scope()).append('\t').append(entry.sha1()).append('\t')
                    .append(entry.path().toAbsolutePath().normalize()).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
