package codes.castled.allium.pack;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only check of a ResourcePackManager mixer directory for packs that define the same vanilla
 * carrier files as Allium's tools ({@code assets/minecraft/items/<carrier>.json} and
 * {@code assets/minecraft/models/item/<carrier>.json}, at the pack root or in an overlay). The
 * merger keeps one file per path, so whichever loses changes how that carrier renders. Nothing is
 * opened for writing.
 */
public final class MixerAudit {

    private MixerAudit() {
    }

    /**
     * One pack defining one of Allium's carriers.
     *
     * @param ready whether Allium's pack already ships that tool (a collision now) or only plans it
     */
    public record Finding(String carrier, String tool, boolean ready, String pack, String entry) {
    }

    /** @param unreadable packs that could not be read (for example permissions), each with the reason */
    public record Report(List<String> scanned, List<String> unreadable, List<Finding> findings) {
        public List<Finding> current() {
            return findings.stream().filter(Finding::ready).toList();
        }

        public List<Finding> planned() {
            return findings.stream().filter(f -> !f.ready()).toList();
        }
    }

    /** Audits every {@code *.zip} in {@code mixer} except Allium's own {@code ownFileName}. */
    public static Report audit(Path mixer, String ownFileName, List<AssetManifest.Tool> tools) throws IOException {
        List<Path> packs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mixer, "*.zip")) {
            stream.forEach(packs::add);
        }
        packs.sort(null);
        List<String> scanned = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        for (Path pack : packs) {
            String name = pack.getFileName().toString();
            if (name.equals(ownFileName)) {
                continue;
            }
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                scanned.add(name);
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    for (AssetManifest.Tool tool : tools) {
                        if (definesCarrier(entry, tool.carrier())) {
                            findings.add(new Finding(tool.carrier(), tool.id(), tool.ready(), name, entry));
                        }
                    }
                }
            } catch (IOException | SecurityException e) {
                unreadable.add(name + " (" + (e instanceof java.nio.file.AccessDeniedException || String.valueOf(e.getMessage()).contains("denied")
                        ? "permission denied" : e.getClass().getSimpleName() + ": " + e.getMessage()) + ")");
            }
        }
        return new Report(List.copyOf(scanned), List.copyOf(unreadable), List.copyOf(findings));
    }

    static boolean definesCarrier(String entry, String carrier) {
        return entry.endsWith("assets/minecraft/items/" + carrier + ".json") || entry.endsWith("assets/minecraft/models/item/" + carrier + ".json");
    }
}
