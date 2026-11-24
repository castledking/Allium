package codes.castled.allium.importer.essentials;

import codes.castled.allium.PluginStart;
import codes.castled.allium.chat.ChatRepository;
import codes.castled.allium.economy.EconomyEngine;
import codes.castled.allium.economy.EconomySchema;
import codes.castled.allium.importer.ImportApplyCoordinator;
import codes.castled.allium.importer.ImportLedger;
import codes.castled.allium.importer.ImportLifecycle;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Destination;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Domain;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.ExistingAccount;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Status;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.ResolvedWorld;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.kit.KitDefinition;
import codes.castled.allium.kit.KitDefinitionLoader;
import codes.castled.allium.kit.KitRepository;
import codes.castled.allium.kit.KitUsageRecord;
import codes.castled.allium.mail.MailRepository;
import codes.castled.allium.message.Messages;
import codes.castled.allium.messaging.IgnoreRepository;
import codes.castled.allium.moderation.JailAssignment;
import codes.castled.allium.moderation.JailRecord;
import codes.castled.allium.moderation.ModerationRepository;
import codes.castled.allium.moderation.MuteRecord;
import codes.castled.allium.module.EconomyModule;
import codes.castled.allium.module.ItemsModule;
import codes.castled.allium.module.KitsModule;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.module.TravelModule;
import codes.castled.allium.module.WorthModule;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.pack.Json;
import codes.castled.allium.preference.PreferenceRepository;
import codes.castled.allium.preference.StoredPreferences;
import codes.castled.allium.scheduler.AlliumScheduler;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.travel.TravelRepository;
import codes.castled.allium.worth.WorthSettings;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /allium import essentials [apply <run>]}: imports Essentials/EssentialsX userdata, warps
 * and jails into Allium.
 *
 * <p>A dry run reads {@code plugins/Essentials} (or the path configured in
 * {@code plugins/Allium/import/essentials.yml}) read-only and writes {@code plan.json},
 * {@code records.jsonl} and {@code summary.txt} under {@code plugins/Allium/import/essentials/<run>/}.
 * Apply needs that run's ID, an empty server (logins are refused while it runs), the exact same
 * source hash and the exact same plan hash.
 */
public final class EssentialsImportService {

    static final String SETTINGS = "import/essentials.yml";
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final PluginStart plugin;
    private final Executor io;
    private final ImportApplyCoordinator coordinator;
    private final AtomicBoolean running = new AtomicBoolean();

    public EssentialsImportService(PluginStart plugin, Executor io) {
        this(plugin, io, plugin.getImportApplyCoordinator());
    }

    public EssentialsImportService(PluginStart plugin, Executor io, ImportApplyCoordinator coordinator) {
        this.plugin = plugin;
        this.io = io;
        this.coordinator = coordinator != null ? coordinator : new ImportApplyCoordinator(plugin);
    }

    public boolean running() {
        return running.get();
    }

    public void dryRun(CommandSender sender) {
        start(sender, null);
    }

    public void apply(CommandSender sender, String runId) {
        if (!runId.matches("essentials-[0-9]{8}-[0-9]{6}(-[0-9]{1,3})?")) {
            refuse(sender, "unknown run '" + runId + "'");
            return;
        }
        start(sender, runId);
    }

    private void start(CommandSender sender, String applyRun) {
        if (applyRun == null && coordinator.isApplying()) {
            refuse(sender, "an import apply (" + coordinator.activeDescription() + ") is already running");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            refuse(sender, "another Essentials import is running");
            return;
        }
        Settings settings;
        try {
            settings = settings();
        } catch (IOException | RuntimeException invalid) {
            running.set(false);
            refuse(sender, SETTINGS + ": " + invalid.getMessage());
            return;
        }
        ImportApplyCoordinator.Lease lease = null;
        if (applyRun != null) {
            var acquisition = coordinator.tryAcquireApply(EssentialsImportPlan.SOURCE, applyRun);
            if (acquisition instanceof ImportApplyCoordinator.Refused refused) {
                running.set(false);
                refuse(sender, refused.reason());
                return;
            }
            lease = ((ImportApplyCoordinator.Acquired) acquisition).lease();
        }
        ImportLifecycle.LoadedWorlds loadedWorlds = ImportLifecycle.LoadedWorlds.capture(plugin.getServer());
        int loadedSigns = (plugin.getModuleManager().getModule("signs") instanceof codes.castled.allium.module.SignsModule sm
                && sm.isEnabled() && sm.signs() != null) ? sm.signs().countLoadedEssentialsSigns() : 0;
        plugin.getMessageManager().send(sender, Messages.IMPORT_ESSENTIALS_STARTED,
                "{mode}", applyRun == null ? "a dry run" : "apply " + applyRun);
        final ImportApplyCoordinator.Lease acquiredLease = lease;
        try {
            io.execute(() -> {
                boolean handedOff = false;
                try {
                    if (applyRun == null) runDry(sender, settings, loadedWorlds, loadedSigns);
                    else handedOff = runApply(sender, settings, loadedWorlds, applyRun, acquiredLease);
                } catch (Exception | LinkageError failure) {
                    String reason = failure.getMessage() == null ? failure.getClass().getSimpleName()
                            : failure.getMessage().lines().findFirst().orElse("");
                    plugin.logger().warning("Essentials import failed: " + reason);
                    tell(sender, Messages.IMPORT_ESSENTIALS_FAILED, "{reason}", reason);
                } finally {
                    if (!handedOff) finish(acquiredLease);
                }
            });
        } catch (RejectedExecutionException busy) {
            finish(acquiredLease);
            refuse(sender, "the import worker is busy");
        }
    }

    private void finish(ImportApplyCoordinator.Lease lease) {
        if (lease != null) lease.close();
        running.set(false);
    }

    private void runDry(CommandSender sender, Settings settings, ImportLifecycle.LoadedWorlds loadedWorlds,
                        int loadedSigns) throws Exception {
        String runId = newRunId();
        Path dir = runDir(runId);
        long now = System.currentTimeMillis();
        EssentialsSnapshot snapshot = EssentialsSnapshot.read(settings.source());
        EconomyEngine econ = economyEngine();
        EssentialsImportPlan plan = EssentialsImportPlan.build(snapshot, worlds(settings, loadedWorlds),
                destination(true, econ != null), now, econ != null ? econ.settings() : null);
        Files.createDirectories(dir);
        writePlan(dir, runId, settings, plan, now);
        writeRecords(dir.resolve("records.jsonl"), plan);
        writeSummary(dir.resolve("summary.txt"), runId, settings, plan, null, loadedSigns);
        plugin.logger().info("Essentials import dry run " + runId + ": " + plan.count(Status.IMPORT)
                + " to import, " + plan.count(Status.PRESENT) + " present, " + plan.count(Status.CONFLICT)
                + " conflicts, " + plan.count(Status.BLOCKED) + " blocked. Reports: " + relative(dir));
        tell(sender, Messages.IMPORT_ESSENTIALS_DRY_RUN,
                "{run}", runId,
                "{import}", Long.toString(plan.count(Status.IMPORT)),
                "{present}", Long.toString(plan.count(Status.PRESENT)),
                "{conflicts}", Long.toString(plan.count(Status.CONFLICT)),
                "{blocked}", Long.toString(plan.count(Status.BLOCKED)),
                "{summary}", relative(dir.resolve("summary.txt")));
    }

    private boolean runApply(CommandSender sender, Settings settings, ImportLifecycle.LoadedWorlds loadedWorlds,
                             String runId, ImportApplyCoordinator.Lease lease) throws Exception {
        Path dir = runDir(runId);
        Path planFile = dir.resolve("plan.json");
        var db = plugin.getDatabaseManager();
        TravelRepository travel = new TravelRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        TeleportRepository teleports = new TeleportRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        MailRepository mail = new MailRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        ChatRepository chat = new ChatRepository(db.connectionSource(), db.dialect());
        IgnoreRepository ignores = new IgnoreRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        PreferenceRepository prefs = new PreferenceRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        ModerationRepository moderation = new ModerationRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        PlayerNameRepository names = new PlayerNameRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        KitRepository kits = new KitRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        ImportLedger ledger = new ImportLedger(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        travel.migrate();
        teleports.migrate();
        mail.migrate();
        chat.migrate();
        ignores.migrate();
        prefs.migrate();
        moderation.migrate();
        names.migrate();
        kits.migrate();
        ledger.migrate();
        var reviewCheck = ImportLifecycle.loadReviewedPlan(planFile, relative(planFile), runId, ledger);
        if (reviewCheck instanceof ImportLifecycle.ReviewCheck.Refused refused) {
            tell(sender, Messages.IMPORT_ESSENTIALS_REFUSED, "{reason}", refused.reason());
            return false;
        }
        ImportLifecycle.ReviewedPlan reviewed = ((ImportLifecycle.ReviewCheck.Valid) reviewCheck).plan();
        long reviewedAt = reviewed.plannedAt();
        EssentialsSnapshot snapshot = EssentialsSnapshot.read(settings.source());
        EconomyEngine econ = economyEngine();
        EssentialsImportPlan plan = EssentialsImportPlan.build(snapshot, worlds(settings, loadedWorlds),
                destination(false, econ != null), reviewedAt, econ != null ? econ.settings() : null);
        Optional<String> mismatch = ImportLifecycle.verifyFingerprints(reviewed, snapshot.sourceSha256(),
                plan.planSha256(), runId,
                "Essentials files changed since " + runId + "; run a new dry run",
                "the plan differs from " + runId + "; run a new dry run");
        if (mismatch.isPresent()) {
            tell(sender, Messages.IMPORT_ESSENTIALS_REFUSED, "{reason}", mismatch.get());
            return false;
        }
        ledger.startRun(runId, EssentialsImportPlan.SOURCE, plan.sourceSha256(), plan.planSha256());
        List<EssentialsImportApplier.Outcome> outcomes;
        try {
            var targets = new EssentialsImportApplier.Targets(serverOrigin(), econ, travel, teleports, mail, chat,
                    ignores, prefs, moderation, names, kits, kitLoader(),
                    plugin.getDataFolder().toPath().resolve("worth.yml"));
            outcomes = EssentialsImportApplier.apply(plan, runId, ledger, targets);
        } catch (SQLException | RuntimeException failure) {
            ledger.finishRun(runId, "failed: " + failure.getMessage());
            throw failure;
        }
        var counts = EssentialsImportApplier.counts(outcomes);
        ledger.finishRun(runId, "applied " + counts);
        writeOutcomes(dir.resolve("apply.jsonl"), outcomes);
        writeSummary(dir.resolve("apply-summary.txt"), runId, settings, plan, outcomes, -1);
        Path signMarker = plugin.getDataFolder().toPath().resolve(codes.castled.allium.sign.SignService.ESSENTIALS_SIGN_MARKER);
        Files.createDirectories(signMarker.getParent());
        Files.writeString(signMarker, "adopted-by=" + runId + "\n", StandardCharsets.UTF_8);
        long applied = counts.getOrDefault(EssentialsImportApplier.Result.APPLIED, 0L);
        long adopted = counts.getOrDefault(EssentialsImportApplier.Result.ADOPTED, 0L);
        long conflicts = counts.getOrDefault(EssentialsImportApplier.Result.CONFLICT, 0L);
        boolean appliedKits = outcomes.stream().anyMatch(o -> o.record().domain() == Domain.KIT
                && o.result() == EssentialsImportApplier.Result.APPLIED);
        boolean appliedWorth = outcomes.stream().anyMatch(o -> o.record().domain() == Domain.WORTH
                && o.result() == EssentialsImportApplier.Result.APPLIED);
        plugin.logger().info("Essentials import " + runId + " applied: " + applied + " applied, "
                + adopted + " adopted, " + conflicts + " conflicts.");
        if (plugin.getPlayerNames() != null) {
            long seen = System.currentTimeMillis();
            var nameCache = plugin.getPlayerNames().cache();
            for (var o : outcomes) {
                if (o.record().domain() == Domain.NAME
                        && (o.result() == EssentialsImportApplier.Result.APPLIED || o.result() == EssentialsImportApplier.Result.ADOPTED)
                        && o.record().owner() != null
                        && codes.castled.allium.naming.NameCache.isValidName(o.record().textValue())) {
                    nameCache.put(o.record().owner(), o.record().textValue(), seen);
                }
            }
        }
        var freshWarps = travel.warpEntries();
        var freshSpawn = teleports.load(EssentialsImportPlan.SERVER_SPAWN_OWNER, "spawn").orElse(null);
        var freshGroupSpawns = teleports.loadGroupSpawns();
        var freshMutes = moderation.loadActiveMutes();
        var freshJails = moderation.loadJails();
        var freshPrisoners = moderation.loadJailedPlayers();
        String summaryPath = relative(dir.resolve("apply-summary.txt"));
        return ImportLifecycle.publishAndRelease(plugin, lease, () -> running.set(false), () -> {
            if (plugin.getModuleManager().getModule("travel") instanceof TravelModule module && module.isEnabled() && module.travel() != null) {
                module.travel().publishWarps(freshWarps);
            }
            if (plugin.getModuleManager().getModule("teleport") instanceof codes.castled.allium.module.TeleportModule tp
                    && tp.isEnabled() && tp.teleports() != null) {
                tp.teleports().publishImportedSpawns(freshSpawn, freshGroupSpawns);
            }
            if (plugin.getModuleManager().getModule("moderation") instanceof ModerationModule mod && mod.isEnabled()) {
                if (mod.moderation() != null) mod.moderation().publishImportedMutes(freshMutes);
                if (mod.discipline() != null) mod.discipline().publishImportedJails(freshJails, freshPrisoners);
            }
            if (appliedKits && plugin.getModuleManager().getModule("kits") instanceof KitsModule km
                    && km.isEnabled() && km.kits() != null) {
                km.kits().reloadDefinitions();
            }
            if (appliedWorth && plugin.getModuleManager().getModule("worth") instanceof WorthModule wm
                    && wm.isEnabled() && wm.worth() != null) {
                wm.reloadSettings();
            }
            if (!plan.supportedSigns().isEmpty()) {
                mergeEnabledSigns(plan.supportedSigns());
            }
            if (plugin.getModuleManager().getModule("signs") instanceof codes.castled.allium.module.SignsModule sm
                    && sm.isEnabled() && sm.signs() != null) {
                if (!plan.supportedSigns().isEmpty()) sm.reloadSettings();
                int migratedSigns = sm.signs().adoptLoadedChunks();
                plugin.logger().info("Essentials sign migration enabled: adopted " + migratedSigns
                        + " sign(s) in loaded chunks (unloaded chunks migrate automatically on load or use).");
            }
        }, () -> tell(sender, Messages.IMPORT_ESSENTIALS_APPLIED,
                "{run}", runId,
                "{applied}", Long.toString(applied),
                "{adopted}", Long.toString(adopted),
                "{conflicts}", Long.toString(conflicts),
                "{summary}", summaryPath));
    }

    private EssentialsImportPlan.Worlds worlds(Settings settings, ImportLifecycle.LoadedWorlds loaded) {
        return (worldUuidOrName, worldName) -> {
            Optional<ResolvedWorld> byNameMatch = loaded.resolve(settings.worlds(), worldName);
            if (byNameMatch.isPresent()) return byNameMatch;
            if (worldUuidOrName != null) {
                Optional<ResolvedWorld> mappedByName = loaded.resolve(settings.worlds(), worldUuidOrName);
                if (mappedByName.isPresent()) return mappedByName;
                String mapped = settings.worlds().getOrDefault(worldUuidOrName, worldUuidOrName);
                try {
                    return loaded.resolve(settings.worlds(), null, UUID.fromString(mapped));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return Optional.empty();
        };
    }

    private Destination destination(boolean tablesMayBeMissing, boolean readEconomy) throws SQLException, IOException {
        var db = plugin.getDatabaseManager();
        TravelRepository travel = new TravelRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        TeleportRepository teleports = new TeleportRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        ModerationRepository moderation = new ModerationRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        KitRepository kitsRepo = new KitRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        ImportLedger ledger = new ImportLedger(db.connectionSource(), db.dialect(), System::currentTimeMillis);

        Map<UUID, ExistingAccount> balances = readEconomy ? readBalances() : Map.of();
        var homes = (!tablesMayBeMissing || exists("allium_travel_homes")) ? travel.allHomes() : Map.<UUID, Map<String, codes.castled.allium.teleport.SavedLocation>>of();
        var warps = (!tablesMayBeMissing || exists("allium_travel_warps")) ? travel.warps() : Map.<String, codes.castled.allium.teleport.SavedLocation>of();
        Map<String, codes.castled.allium.teleport.SavedLocation> spawns = new HashMap<>();
        boolean hasLocationsTable = !tablesMayBeMissing || exists(TeleportRepository.LOCATIONS_TABLE);
        if (hasLocationsTable) {
            teleports.load(EssentialsImportPlan.SERVER_SPAWN_OWNER, "spawn").ifPresent(loc -> spawns.put("spawn", loc));
            for (var entry : teleports.loadGroupSpawns().entrySet()) {
                spawns.put("spawn:" + entry.getKey(), entry.getValue());
            }
        }
        var logouts = hasLocationsTable ? teleports.slot("logout") : Map.<UUID, codes.castled.allium.teleport.SavedLocation>of();
        var backs = hasLocationsTable ? teleports.slot("back") : Map.<UUID, codes.castled.allium.teleport.SavedLocation>of();
        Set<String> mailRefs = (!tablesMayBeMissing || exists("allium_mail")) ? readMailRefs() : Set.of();
        Map<UUID, String> nicks = (!tablesMayBeMissing || exists("allium_nicknames")) ? readNicknames() : Map.of();
        Map<UUID, Set<UUID>> ignores = (!tablesMayBeMissing || exists("allium_ignores")) ? readIgnores() : Map.of();
        Map<UUID, StoredPreferences> prefs = (!tablesMayBeMissing || exists(PreferenceRepository.TABLE)) ? readPreferences() : Map.of();

        Map<String, JailRecord> jails = new HashMap<>();
        if (!tablesMayBeMissing || exists("allium_jails")) {
            for (JailRecord j : moderation.loadJails()) jails.put(j.name().toLowerCase(Locale.ROOT), j);
        }
        Map<UUID, MuteRecord> mutes = new HashMap<>();
        if (!tablesMayBeMissing || exists("allium_mutes")) {
            for (MuteRecord m : moderation.loadActiveMutes()) mutes.put(m.playerUuid(), m);
        }
        Map<UUID, JailAssignment> jailed = new HashMap<>();
        if (!tablesMayBeMissing || exists("allium_jailed")) {
            for (JailAssignment j : moderation.loadJailedPlayers()) jailed.put(j.playerUuid(), j);
        }
        Map<UUID, String> names = (!tablesMayBeMissing || exists("allium_player_names")) ? readNames() : Map.of();
        Map<String, KitDefinition> existingKits = kitLoader().load(new ItemSpecParser(true)).definitions();
        Map<String, KitUsageRecord> kitUsages = (!tablesMayBeMissing || exists("allium_kit_usages"))
                ? kitsRepo.loadAllUsages() : Map.of();
        Map<String, BigDecimal> worthPrices = readWorthPrices();
        Map<String, String> hashes = (!tablesMayBeMissing || exists(ImportLedger.RECORDS))
                ? ledger.hashes(EssentialsImportPlan.SOURCE) : Map.of();

        return new Destination(balances, homes, warps, Map.copyOf(spawns), logouts, backs, mailRefs, nicks, ignores, prefs,
                jails, mutes, jailed, names, existingKits, kitUsages, worthPrices, hashes);
    }

    private KitDefinitionLoader kitLoader() {
        return new KitDefinitionLoader(plugin.getDataFolder(), plugin::getResource, ignored -> {}, ignored -> {});
    }

    private Map<String, BigDecimal> readWorthPrices() throws IOException {
        Path worthFile = plugin.getDataFolder().toPath().resolve("worth.yml");
        if (!Files.isRegularFile(worthFile)) return Map.of();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(worthFile, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException bad) {
            throw new IOException("Allium worth.yml could not be parsed: " + bad.getMessage(), bad);
        }
        return WorthSettings.read(yaml, this::worthAlias, ignored -> {}).prices();
    }

    private Material worthAlias(String name) {
        return plugin.getModuleManager() != null && plugin.getModuleManager().getModule("items") instanceof ItemsModule items
                && items.isEnabled() ? items.material(name) : null;
    }

    private void mergeEnabledSigns(List<String> importedSigns) {
        try {
            var current = new java.util.LinkedHashSet<>(plugin.getConfig().getStringList("enabledSigns"));
            boolean changed = current.addAll(importedSigns);
            if (!changed) return;
            List<String> merged = List.copyOf(current);
            plugin.getConfig().set("enabledSigns", merged);
            Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
            if (Files.isRegularFile(configPath)) {
                String text = Files.readString(configPath, StandardCharsets.UTF_8);
                String updated = codes.castled.allium.config.SignConfigComments.format(text, merged);
                Files.writeString(configPath, updated, StandardCharsets.UTF_8);
            }
            plugin.logger().info("Merged Essentials enabledSigns into config.yml: " + importedSigns);
        } catch (IOException | RuntimeException failure) {
            plugin.logger().warning("Could not write imported enabledSigns to config.yml: " + failure.getMessage());
        }
    }

    private Map<UUID, ExistingAccount> readBalances() throws SQLException {
        Map<UUID, ExistingAccount> out = new HashMap<>();
        try (Connection c = plugin.getDatabaseManager().connectionSource().connection();
             PreparedStatement ps = c.prepareStatement("SELECT account_uuid, balance_minor, reserved_minor FROM " + EconomySchema.ACCOUNTS);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.put(UUID.fromString(rs.getString(1)), new ExistingAccount(rs.getLong(2), rs.getLong(3)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    private Set<String> readMailRefs() throws SQLException {
        Set<String> out = new HashSet<>();
        try (Connection c = plugin.getDatabaseManager().connectionSource().connection();
             PreparedStatement ps = c.prepareStatement("SELECT source_ref FROM allium_mail WHERE source = ? AND source_ref IS NOT NULL")) {
            ps.setString(1, EssentialsImportPlan.SOURCE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    private Map<UUID, String> readNicknames() throws SQLException {
        Map<UUID, String> out = new HashMap<>();
        try (Connection c = plugin.getDatabaseManager().connectionSource().connection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, raw_text FROM allium_nicknames WHERE raw_text IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    private Map<UUID, Set<UUID>> readIgnores() throws SQLException {
        Map<UUID, Set<UUID>> out = new HashMap<>();
        try (Connection c = plugin.getDatabaseManager().connectionSource().connection();
             PreparedStatement ps = c.prepareStatement("SELECT owner, ignored FROM allium_ignores");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.computeIfAbsent(UUID.fromString(rs.getString(1)), ignored -> new HashSet<>())
                            .add(UUID.fromString(rs.getString(2)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    private Map<UUID, StoredPreferences> readPreferences() throws SQLException {
        Map<UUID, StoredPreferences> out = new HashMap<>();
        try (Connection c = plugin.getDatabaseManager().connectionSource().connection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, accept_messages, accept_payments, accept_teleport_requests, "
                     + "message_sound_volume, message_sound_restore, social_spy, pay_confirm, tp_auto, version FROM " + PreferenceRepository.TABLE);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    UUID id = UUID.fromString(rs.getString(1));
                    out.put(id, new StoredPreferences(id, bool(rs, 2), bool(rs, 3), bool(rs, 4),
                            dbl(rs, 5), dbl(rs, 6), bool(rs, 7), bool(rs, 8), bool(rs, 9), rs.getLong(10)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    private Map<UUID, String> readNames() throws SQLException {
        Map<UUID, String> out = new HashMap<>();
        try (Connection c = plugin.getDatabaseManager().connectionSource().connection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name FROM allium_player_names");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    private static Boolean bool(ResultSet rs, int col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }

    private static Double dbl(ResultSet rs, int col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private boolean exists(String table) throws SQLException {
        return ImportLifecycle.tableExists(plugin.getDatabaseManager().connectionSource(), table);
    }

    private EconomyEngine economyEngine() {
        if (plugin.getModuleManager().getModule("economy") instanceof EconomyModule mod && mod.isEnabled()
                && mod.runtime() != null && mod.runtime().ready()) {
            return mod.runtime().engine();
        }
        return null;
    }

    private String serverOrigin() {
        Path file = plugin.getDataFolder().toPath().resolve(".server-id");
        try {
            if (Files.isRegularFile(file)) {
                String id = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) return id;
            }
        } catch (IOException ignored) {
        }
        return "essentials-import";
    }

    // -- Settings & reports ------------------------------------------------------------------------

    private record Settings(Path source, Map<String, String> worlds) {}

    private Settings settings() throws IOException {
        Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path pluginsDir = data.getParent() != null ? data.getParent() : data;
        Path defaultSource = pluginsDir.resolve("Essentials").normalize();
        Path file = data.resolve(SETTINGS);
        Map<String, String> worlds = new LinkedHashMap<>();
        Path source = defaultSource;
        if (Files.isRegularFile(file)) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file.toFile());
            } catch (Exception bad) {
                throw new IOException("could not parse " + SETTINGS + ": " + bad.getMessage(), bad);
            }
            String configured = yaml.getString("source");
            if (configured != null && !configured.isBlank()) {
                Path p = Path.of(configured.trim());
                source = p.isAbsolute() ? p.normalize() : data.resolve(p).normalize();
            }
            var w = yaml.getConfigurationSection("worlds");
            if (w != null) {
                for (String key : w.getKeys(false)) {
                    String target = w.getString(key);
                    if (target != null && !target.isBlank()) worlds.put(key, target.trim());
                }
            }
        }
        return new Settings(source, Map.copyOf(worlds));
    }

    private Path runDir(String runId) {
        return plugin.getDataFolder().toPath().resolve("import/essentials").resolve(runId);
    }

    private String newRunId() throws IOException {
        String base = "essentials-" + RUN_ID.format(Instant.now());
        Path root = plugin.getDataFolder().toPath().resolve("import/essentials");
        Files.createDirectories(root);
        if (!Files.exists(root.resolve(base))) return base;
        for (int n = 1; n < 1000; n++) {
            String candidate = base + "-" + n;
            if (!Files.exists(root.resolve(candidate))) return candidate;
        }
        throw new IOException("too many runs in the same second");
    }

    private void writePlan(Path dir, String runId, Settings settings, EssentialsImportPlan plan, long plannedAt) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("runId", runId);
        doc.put("source", EssentialsImportPlan.SOURCE);
        doc.put("sourcePath", settings.source().toString());
        doc.put("sourceSha256", plan.sourceSha256());
        doc.put("planSha256", plan.planSha256());
        doc.put("plannedAt", plannedAt);
        doc.put("npcSkipped", plan.npcSkipped());
        doc.put("nonUuidFiles", plan.nonUuidFiles());
        Files.writeString(dir.resolve("plan.json"), Json.write(doc) + "\n", StandardCharsets.UTF_8);
    }

    private void writeRecords(Path file, EssentialsImportPlan plan) throws IOException {
        StringBuilder out = new StringBuilder();
        for (EssentialsImportPlan.Record r : plan.records()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("domain", r.domain().id());
            row.put("key", r.key());
            row.put("status", r.status().name());
            row.put("reason", r.reason());
            row.put("payloadSha256", r.payloadHash());
            out.append(Json.write(row)).append('\n');
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    private void writeOutcomes(Path file, List<EssentialsImportApplier.Outcome> outcomes) throws IOException {
        StringBuilder out = new StringBuilder();
        for (var o : outcomes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("domain", o.record().domain().id());
            row.put("key", o.record().key());
            row.put("result", o.result().name());
            row.put("payloadSha256", o.record().payloadHash());
            out.append(Json.write(row)).append('\n');
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    private void writeSummary(Path file, String runId, Settings settings, EssentialsImportPlan plan,
                              List<EssentialsImportApplier.Outcome> outcomes, int loadedSigns) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Essentials import ").append(outcomes == null ? "dry run " : "apply ").append(runId).append('\n');
        sb.append("# Source: ").append(settings.source()).append('\n');
        sb.append("# Source SHA-256: ").append(plan.sourceSha256()).append('\n');
        sb.append("# Plan SHA-256: ").append(plan.planSha256()).append('\n');
        sb.append("# NPCs skipped: ").append(plan.npcSkipped())
                .append(" | Non-UUID/malformed userdata files: ").append(plan.nonUuidFiles());
        if (loadedSigns >= 0) {
            sb.append(" | Loaded Essentials signs ready to adopt: ").append(loadedSigns);
        }
        sb.append('\n');
        if (!plan.supportedSigns().isEmpty() || !plan.unsupportedSigns().isEmpty()) {
            sb.append("# Essentials enabledSigns -> Allium: ").append(plan.supportedSigns())
                    .append(" (unsupported: ").append(plan.unsupportedSigns()).append(")\n");
        }
        sb.append("\n");
        for (Domain d : Domain.values()) {
            var counts = plan.counts(d);
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            if (total == 0) continue;
            sb.append(String.format(Locale.ROOT, "%-11s import=%d present=%d replayed=%d conflict=%d blocked=%d skipped=%d%n",
                    d.id(), counts.get(Status.IMPORT), counts.get(Status.PRESENT), counts.get(Status.REPLAYED),
                    counts.get(Status.CONFLICT), counts.get(Status.BLOCKED), counts.get(Status.SKIPPED)));
        }
        if (outcomes != null) {
            sb.append("\nApply outcomes: ").append(EssentialsImportApplier.counts(outcomes)).append('\n');
        }
        if (!plan.notes().isEmpty()) {
            sb.append("\nNotes:\n");
            for (String note : plan.notes()) sb.append("- ").append(note).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private String relative(Path path) {
        return plugin.getDataFolder().toPath().toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString();
    }

    private void refuse(CommandSender sender, String reason) {
        plugin.getMessageManager().send(sender, Messages.IMPORT_ESSENTIALS_REFUSED, "{reason}", reason);
    }

    private void tell(CommandSender sender, Messages key, String... placeholders) {
        codes.castled.allium.command.essentials.UtilitySupport.send(plugin, sender, key, placeholders);
    }
}
