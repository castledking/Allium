package codes.castled.allium.pack.delivery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Which pack Allium delivers: the bundled core pack, or {@code resource-pack.pack-file}, a local
 * build with other art (for example SF's, from {@code pack/tools/sf_export.py}, which is never
 * committed). The local file must keep the bundled pack's structure (every model, item
 * definition and {@code pack.mcmeta}); only textures may differ. Otherwise Allium warns and
 * delivers the bundled pack.
 *
 * @param source a human-readable origin for logs
 */
public record PackSource(byte[] bytes, String sha1, String source, boolean override) {

    public static PackSource bundled(byte[] bundled) {
        return new PackSource(bundled, PackExport.sha1(bundled), "the bundled core pack", false);
    }

    /** @param packFile {@code resource-pack.pack-file}: blank for the bundled pack, else a path inside {@code dataFolder} */
    public static PackSource choose(Path dataFolder, String packFile, byte[] bundled, Consumer<String> warn) {
        if (packFile == null || packFile.isBlank()) {
            return bundled(bundled);
        }
        Path folder = dataFolder.toAbsolutePath().normalize();
        Path file = folder.resolve(packFile).normalize();
        if (!file.startsWith(folder)) {
            warn.accept("resource-pack.pack-file must be a path inside plugins/Allium/; delivering the bundled core pack");
            return bundled(bundled);
        }
        byte[] candidate;
        try {
            candidate = Files.readAllBytes(file);
        } catch (IOException e) {
            warn.accept("resource-pack.pack-file " + file + " could not be read (" + e + "); delivering the bundled core pack");
            return bundled(bundled);
        }
        List<String> missing;
        try {
            missing = missingStructure(bundled, candidate);
        } catch (IOException e) {
            warn.accept("resource-pack.pack-file " + file + " is not a zip (" + e.getMessage() + "); delivering the bundled core pack");
            return bundled(bundled);
        }
        if (!missing.isEmpty()) {
            warn.accept("resource-pack.pack-file " + file + " lacks the core pack's " + missing + "; it must be built from Allium's "
                    + "manifest (pack/tools/sf_export.py does that). Delivering the bundled core pack");
            return bundled(bundled);
        }
        return new PackSource(candidate, PackExport.sha1(candidate), file.toString(), true);
    }

    /** The bundled pack's models, item definitions and pack.mcmeta that the candidate lacks. */
    static List<String> missingStructure(byte[] bundled, byte[] candidate) throws IOException {
        Set<String> required = new TreeSet<>();
        for (String entry : entries(bundled)) {
            if (entry.endsWith(".json") || entry.equals("pack.mcmeta")) {
                required.add(entry);
            }
        }
        required.removeAll(entries(candidate));
        return new ArrayList<>(required);
    }

    private static Set<String> entries(byte[] zip) throws IOException {
        Set<String> names = new TreeSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        if (names.isEmpty()) {
            throw new IOException("no entries");
        }
        return names;
    }
}
