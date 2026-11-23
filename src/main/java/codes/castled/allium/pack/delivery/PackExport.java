package codes.castled.allium.pack.delivery;

import codes.castled.allium.pack.AssetManifest;
import codes.castled.allium.pack.MixerAudit;
import codes.castled.allium.pack.ResourcePackSettings;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Writes the bundled core pack where its delivery owner needs it. Runs in Allium's {@code onLoad},
 * before any plugin enables, so ResourcePackManager merges the current pack when it starts.
 * Allium writes only its own file: never another pack, and never ResourcePackManager's config.
 */
public final class PackExport {

    public static final String STANDALONE_DIRECTORY = "pack";
    public static final String STANDALONE_FILE = "allium-core-pack.zip";

    private PackExport() {
    }

    public record Logs(Consumer<String> info, Consumer<String> warning) {
    }

    public enum ExportStatus {
        MISSING_DIRECTORY,
        DRY_RUN,
        UNCHANGED,
        WRITTEN
    }

    public record ExportResult(
            ExportStatus status,
            @Nullable Path target,
            @Nullable String sha1,
            List<Path> removedStale
    ) {
        public ExportResult {
            removedStale = removedStale == null ? List.of() : List.copyOf(removedStale);
        }
    }

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** standalone: {@code plugins/Allium/pack/allium-core-pack.zip} and its {@code .sha1}, rewritten only when they differ. */
    public static Path exportStandalone(Path dataFolder, byte[] pack, Logs logs) throws IOException {
        Path target = dataFolder.resolve(STANDALONE_DIRECTORY).resolve(STANDALONE_FILE);
        Files.createDirectories(target.getParent());
        String sha1 = sha1(pack);
        boolean changed = writeIfDifferent(target, pack);
        writeIfDifferent(target.resolveSibling(STANDALONE_FILE + ".sha1"), (sha1 + "\n").getBytes());
        logs.info().accept((changed ? "Exported" : "Kept") + " the core resource pack at " + target + " (" + pack.length
                + " bytes, SHA-1 " + sha1 + "). Host this file at resource-pack.standalone.url.");
        return target;
    }

    /**
     * rpm-export: audits the mixer for packs that share Allium's carrier files, then writes
     * {@code settings.mixerFileName()} (unless it is a dry run) and reconciles stale core exports.
     */
    public static ExportResult exportToMixer(Path pluginsFolder, ResourcePackSettings settings, byte[] pack, List<AssetManifest.Tool> tools,
                                             Logs logs) throws IOException {
        return exportToMixer(pluginsFolder.resolve("Allium"), pluginsFolder, settings, pack, tools, logs);
    }

    public static ExportResult exportToMixer(Path dataFolder, Path pluginsFolder, ResourcePackSettings settings, byte[] pack,
                                             List<AssetManifest.Tool> tools, Logs logs) throws IOException {
        Path configured = Path.of(settings.mixerDirectory());
        Path mixer = configured.isAbsolute() ? configured : pluginsFolder.resolve(configured);
        String prefix = settings.dryRun() ? "[rpm-export dry run] " : "";
        if (!Files.isDirectory(mixer)) {
            logs.warning().accept(prefix + "ResourcePackManager's mixer directory " + mixer + " does not exist; nothing was exported. "
                    + "Install ResourcePackManager or set resource-pack.rpm-export.mixer-directory.");
            return new ExportResult(ExportStatus.MISSING_DIRECTORY, mixer, null, List.of());
        }
        MixerAudit.Report report = MixerAudit.audit(mixer, settings.mixerFileName(), tools);
        logs.info().accept(prefix + "Checked " + report.scanned().size() + " pack(s) in " + mixer + " for Allium's carrier files.");
        for (MixerAudit.Finding f : report.current()) {
            logs.warning().accept(prefix + "Carrier collision: " + f.pack() + " also defines " + f.entry() + ", the carrier file of Allium's "
                    + f.tool() + ". ResourcePackManager keeps one file per path, so one of them will render wrong; set RPM's pack "
                    + "priority deliberately.");
        }
        for (MixerAudit.Finding f : report.planned()) {
            logs.info().accept(prefix + "Carrier collision ahead: " + f.pack() + " defines " + f.entry() + " (" + f.carrier()
                    + "), the carrier of Allium's planned " + f.tool() + ". It does not affect the current pack, but will once "
                    + f.tool() + " ships.");
        }
        for (String unreadable : report.unreadable()) {
            logs.warning().accept(prefix + "Could not read " + unreadable + " in the mixer; its carrier files were not checked.");
        }
        Path target = mixer.resolve(settings.mixerFileName());
        String sha1 = sha1(pack);
        String existing = Files.exists(target) ? sha1(Files.readAllBytes(target)) : null;
        if (settings.dryRun()) {
            logs.info().accept(prefix + "Would write " + target + " (" + pack.length + " bytes, SHA-1 " + sha1 + "); "
                    + (existing == null ? "no such file exists yet" : existing.equals(sha1) ? "the existing file is identical"
                    : "it would replace an existing file with SHA-1 " + existing) + ". Nothing was written.");
            PackExportLedger.reconcile(dataFolder, PackExportLedger.SCOPE_CORE, Map.of(target, sha1), true, logs);
            return new ExportResult(ExportStatus.DRY_RUN, target, sha1, List.of());
        }
        boolean changed = writeIfDifferent(target, pack);
        logs.info().accept((changed ? "Wrote" : "Kept") + " the core resource pack in ResourcePackManager's mixer: " + target + " ("
                + pack.length + " bytes, SHA-1 " + sha1 + "). ResourcePackManager merges and sends it; Allium does not send it itself.");
        List<Path> removed = PackExportLedger.reconcile(dataFolder, PackExportLedger.SCOPE_CORE, Map.of(target, sha1), false, logs);
        return new ExportResult(changed ? ExportStatus.WRITTEN : ExportStatus.UNCHANGED, target, sha1, removed);
    }

    /** Removes any previously exported Allium core mixer ZIP when Allium is no longer in {@code rpm-export} mode. */
    public static List<Path> reconcileUnusedCoreMixerExports(Path dataFolder, boolean dryRun, Logs logs) {
        return PackExportLedger.reconcile(dataFolder, PackExportLedger.SCOPE_CORE, Map.of(), dryRun, logs);
    }

    /** Formats a truthful operator reload reply for {@code rpm-export} based on explicit export results. */
    public static String formatRpmReloadReply(
            @Nullable ExportResult coreMixer,
            @Nullable AddonPacks.RspmExportResult addonMixer
    ) {
        if (coreMixer == null) {
            return "Could not export the core resource pack to ResourcePackManager's mixer; check the server log.";
        }
        int removed = coreMixer.removedStale().size() + (addonMixer == null ? 0 : addonMixer.removedStale().size());
        String removedSuffix = removed == 0 ? "" : " Removed " + removed + " stale Allium export(s).";
        return switch (coreMixer.status()) {
            case MISSING_DIRECTORY -> "ResourcePackManager's mixer directory " + coreMixer.target()
                    + " does not exist; nothing was exported.";
            case DRY_RUN -> "Dry run only: audited ResourcePackManager's mixer; nothing was written.";
            case UNCHANGED -> "Core resource pack in ResourcePackManager's mixer is already up to date." + removedSuffix
                    + (removed > 0 ? " Reload ResourcePackManager to merge it." : "");
            case WRITTEN -> "Re-exported the core resource pack to ResourcePackManager's mixer; reload ResourcePackManager to merge it."
                    + removedSuffix;
        };
    }

    /** Atomically replaces {@code target} with {@code data} unless it already holds exactly that. */
    static boolean writeIfDifferent(Path target, byte[] data) throws IOException {
        if (Files.exists(target) && java.util.Arrays.equals(Files.readAllBytes(target), data)) {
            return false;
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".allium-tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
