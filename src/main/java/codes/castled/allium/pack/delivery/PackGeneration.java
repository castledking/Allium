package codes.castled.allium.pack.delivery;

import codes.castled.allium.addon.AddonInventory;
import codes.castled.allium.hide.CommandHintPack;
import codes.castled.allium.pack.AssetManifest;
import codes.castled.allium.pack.ResourcePackSettings;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One immutable, validated resource-pack generation built from a single {@link AddonInventory}.
 *
 * <p>Holds the branded core artifact (base pack + validated {@code allium}-mode addon packs +
 * configured command hint), its SHA-1, every validated addon pack contribution, and the exact
 * inventory of owned disk outputs. Both standalone hosting ({@link PackDelivery} /
 * {@link PackHttpServer}) and mixer export ({@link PackExport} / {@link AddonPacks}) consume this
 * same generation without re-scanning jars or re-assembling ZIPs.
 */
public final class PackGeneration {

    public enum OutputKind {
        STANDALONE_ZIP,
        STANDALONE_SHA1,
        MIXER_CORE,
        MIXER_ADDON
    }

    public record OwnedOutput(OutputKind kind, Path path, String sha1, String ownerName) {
        public OwnedOutput {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha1, "sha1");
            Objects.requireNonNull(ownerName, "ownerName");
        }
    }

    public record PublishOutcome(
            PackGeneration generation,
            @Nullable Path standaloneFile,
            @Nullable PackExport.ExportResult coreMixer,
            AddonPacks.RspmExportResult addonMixer
    ) {
        public PublishOutcome {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(addonMixer, "addonMixer");
        }
    }

    private final ResourcePackSettings settings;
    private final AddonInventory inventory;
    private final List<AddonPacks.Discovered> addonPacks;
    private final @Nullable PackSource coreArtifact;
    private final List<String> mergedAddonNames;
    private final String commandHint;
    private final List<OwnedOutput> ownedOutputs;

    private PackGeneration(
            ResourcePackSettings settings,
            AddonInventory inventory,
            List<AddonPacks.Discovered> addonPacks,
            @Nullable PackSource coreArtifact,
            List<String> mergedAddonNames,
            String commandHint,
            List<OwnedOutput> ownedOutputs
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.addonPacks = List.copyOf(addonPacks);
        this.coreArtifact = coreArtifact;
        this.mergedAddonNames = List.copyOf(mergedAddonNames);
        this.commandHint = commandHint == null ? "" : commandHint;
        this.ownedOutputs = List.copyOf(ownedOutputs);
    }

    /** Convenience assembly scanning {@code <dataFolder>/addons} via {@link AddonInventory}. */
    public static PackGeneration assemble(
            Path dataFolder,
            Path pluginsFolder,
            @Nullable FileConfiguration config,
            ResourcePackSettings settings,
            @Nullable byte[] bundledCore,
            @Nullable byte[] bundledStandalone,
            @Nullable String commandHint,
            PackExport.Logs logs
    ) {
        AddonInventory inventory = AddonInventory.scan(dataFolder == null ? null : dataFolder.resolve("addons"), config);
        return assemble(dataFolder, pluginsFolder, inventory, settings, bundledCore, bundledStandalone, commandHint, logs);
    }

    /**
     * Assembles one immutable {@link PackGeneration} from an already validated {@link AddonInventory}.
     */
    public static PackGeneration assemble(
            Path dataFolder,
            Path pluginsFolder,
            AddonInventory inventory,
            ResourcePackSettings settings,
            @Nullable byte[] bundledCore,
            @Nullable byte[] bundledStandalone,
            @Nullable String commandHint,
            PackExport.Logs logs
    ) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(pluginsFolder, "pluginsFolder");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(logs, "logs");

        List<AddonPacks.Discovered> addonPacks = AddonPacks.discover(dataFolder, inventory, logs);
        String effectiveHint = commandHint == null ? "" : commandHint;
        List<String> alliumAddons = addonPacks.stream()
                .filter(d -> d.mode() == AddonPacks.Mode.ALLIUM)
                .map(AddonPacks.Discovered::addonName)
                .toList();

        PackSource coreArtifact = null;
        if (settings.owner() == ResourcePackSettings.Owner.NONE) {
            if (!alliumAddons.isEmpty()) {
                logs.warning().accept("Addon pack(s) " + alliumAddons + " requested resource-pack.delivery: allium, "
                        + "but Allium's resource-pack.owner is none. Set Allium's owner to standalone/rpm-export or set the addon's delivery to rspm.");
            }
        } else if (bundledCore == null) {
            logs.warning().accept("The core resource pack is missing from the plugin jar; nothing was exported or sent.");
        } else {
            PackSource base;
            if (settings.packFile() != null && !settings.packFile().isBlank()) {
                base = PackSource.choose(dataFolder, settings.packFile(), bundledCore, logs.warning());
            } else if (settings.owner() == ResourcePackSettings.Owner.STANDALONE && bundledStandalone != null) {
                base = PackSource.bundled(bundledStandalone);
            } else {
                base = PackSource.bundled(bundledCore);
            }
            PackSource merged = AddonPacks.mergeIntoBase(base, addonPacks, logs);
            if (!effectiveHint.isEmpty()) {
                try {
                    byte[] withHint = CommandHintPack.merge(merged.bytes(), effectiveHint);
                    coreArtifact = withHint == merged.bytes()
                            ? merged
                            : new PackSource(withHint, PackExport.sha1(withHint), merged.source(), merged.override());
                } catch (IOException | IllegalArgumentException failure) {
                    logs.warning().accept("Could not merge the configured command hint: " + failure.getMessage());
                    coreArtifact = merged;
                }
            } else {
                coreArtifact = merged;
            }
        }

        List<OwnedOutput> outputs = computeOwnedOutputs(dataFolder, pluginsFolder, settings, addonPacks, coreArtifact);
        return new PackGeneration(
                settings,
                inventory,
                addonPacks,
                coreArtifact,
                coreArtifact == null ? List.of() : alliumAddons,
                effectiveHint,
                outputs
        );
    }

    private static List<OwnedOutput> computeOwnedOutputs(
            Path dataFolder,
            Path pluginsFolder,
            ResourcePackSettings settings,
            List<AddonPacks.Discovered> addonPacks,
            @Nullable PackSource coreArtifact
    ) {
        List<OwnedOutput> outputs = new ArrayList<>();
        Path configuredMixer = Path.of(settings.mixerDirectory());
        Path mixerDir = configuredMixer.isAbsolute() ? configuredMixer : pluginsFolder.resolve(configuredMixer);

        if (coreArtifact != null) {
            if (settings.owner() == ResourcePackSettings.Owner.STANDALONE) {
                Path zip = dataFolder.resolve(PackExport.STANDALONE_DIRECTORY).resolve(PackExport.STANDALONE_FILE);
                outputs.add(new OwnedOutput(OutputKind.STANDALONE_ZIP, zip, coreArtifact.sha1(), "Allium"));
                outputs.add(new OwnedOutput(OutputKind.STANDALONE_SHA1, zip.resolveSibling(PackExport.STANDALONE_FILE + ".sha1"),
                        coreArtifact.sha1(), "Allium"));
            } else if (settings.owner() == ResourcePackSettings.Owner.RPM_EXPORT) {
                Path coreMixerZip = mixerDir.resolve(settings.mixerFileName());
                outputs.add(new OwnedOutput(OutputKind.MIXER_CORE, coreMixerZip, coreArtifact.sha1(), "Allium"));
            }
        }

        for (AddonPacks.Discovered discovered : addonPacks) {
            if (discovered.mode() == AddonPacks.Mode.RSPM) {
                String safeName = discovered.addonName().replaceAll("[^A-Za-z0-9._-]", "_");
                Path addonZip = mixerDir.resolve(safeName + "_resource_pack.zip");
                outputs.add(new OwnedOutput(OutputKind.MIXER_ADDON, addonZip, PackExport.sha1(discovered.zipBytes()), discovered.addonName()));
            }
        }
        return outputs;
    }

    /**
     * Publishes this generation through the disk export adapters (standalone ZIP + `.sha1` and/or
     * ResourcePackManager mixer ZIPs) and reconciles stale Allium-owned mixer outputs.
     */
    public PublishOutcome publishExports(
            Path dataFolder,
            Path pluginsFolder,
            @Nullable List<AssetManifest.Tool> mixerTools,
            PackExport.Logs logs
    ) {
        AddonPacks.RspmExportResult addonResult = AddonPacks.exportRspm(dataFolder, pluginsFolder, settings, addonPacks, logs);
        if (settings.owner() == ResourcePackSettings.Owner.NONE) {
            PackExport.reconcileUnusedCoreMixerExports(dataFolder, settings.dryRun(), logs);
            return new PublishOutcome(this, null, null, addonResult);
        }
        if (coreArtifact == null) {
            return new PublishOutcome(this, null, null, addonResult);
        }
        if (coreArtifact.override()) {
            logs.info().accept("Delivering " + coreArtifact.source() + " (SHA-1 " + coreArtifact.sha1() + ") instead of the bundled core pack.");
        }
        Path standaloneFile = null;
        PackExport.ExportResult coreMixer = null;
        try {
            if (settings.owner() == ResourcePackSettings.Owner.STANDALONE) {
                standaloneFile = PackExport.exportStandalone(dataFolder, coreArtifact.bytes(), logs);
                PackExport.reconcileUnusedCoreMixerExports(dataFolder, settings.dryRun(), logs);
            } else {
                List<AssetManifest.Tool> tools = mixerTools == null ? List.of() : mixerTools;
                coreMixer = PackExport.exportToMixer(dataFolder, pluginsFolder, settings, coreArtifact.bytes(), tools, logs);
            }
        } catch (IOException | RuntimeException e) {
            logs.warning().accept("Could not export the core resource pack for " + settings.owner().configName() + ": " + e);
        }
        return new PublishOutcome(this, standaloneFile, coreMixer, addonResult);
    }

    public ResourcePackSettings settings() {
        return settings;
    }

    public AddonInventory inventory() {
        return inventory;
    }

    public List<AddonPacks.Discovered> addonPacks() {
        return addonPacks;
    }

    @Nullable
    public PackSource coreArtifact() {
        return coreArtifact;
    }

    @Nullable
    public String sha1() {
        return coreArtifact == null ? null : coreArtifact.sha1();
    }

    public List<String> mergedAddonNames() {
        return mergedAddonNames;
    }

    public String commandHint() {
        return commandHint;
    }

    public List<OwnedOutput> ownedOutputs() {
        return ownedOutputs;
    }
}
