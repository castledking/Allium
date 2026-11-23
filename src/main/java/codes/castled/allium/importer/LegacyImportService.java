package codes.castled.allium.importer;

import codes.castled.allium.PluginStart;
import codes.castled.allium.importer.legacy.LegacyAlliumReader;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot;
import codes.castled.allium.importer.legacy.LegacyDestinationApplier;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Destination;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Domain;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.ResolvedWorld;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Status;
import codes.castled.allium.importer.legacy.LegacyH2Source;
import codes.castled.allium.message.Messages;
import codes.castled.allium.module.TravelModule;
import codes.castled.allium.pack.Json;
import codes.castled.allium.scheduler.AlliumScheduler;
import codes.castled.allium.scheduler.TaskHandle;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.travel.TravelRepository;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * {@code /allium import legacy [apply <run>]}: imports legacy Allium's homes, warps and logout
 * positions from {@code plugins/Allium/database/sfcore.mv.db}.
 *
 * <p>A dry run copies the database into {@code import/legacy/<run>/}, reads only the copy and
 * writes the plan and reports there; nothing else changes. Apply needs that run's id, an empty
 * server (logins are refused until it ends), the same source file (same SHA-256) and the same
 * plan; it then writes each destination row and its ledger row in one transaction. Legacy
 * max-homes values are written as a {@code homes.staff-overrides} snippet, never applied.
 */
public final class LegacyImportService {

    static final String SETTINGS = "import/legacy-allium.yml";
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final PluginStart plugin;
    private final Executor io;
    private final ImportApplyCoordinator coordinator;
    private final AtomicBoolean running = new AtomicBoolean();

    public LegacyImportService(PluginStart plugin, Executor io) {
        this(plugin, io, plugin.getImportApplyCoordinator());
    }

    public LegacyImportService(PluginStart plugin, Executor io, ImportApplyCoordinator coordinator) {
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
        if (!runId.matches("legacy-[0-9]{8}-[0-9]{6}(-[0-9]{1,3})?")) {
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
            refuse(sender, "another legacy import is running");
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
            var acquisition = coordinator.tryAcquireApply(LegacyDestinationPlan.SOURCE, applyRun);
            if (acquisition instanceof ImportApplyCoordinator.Refused refused) {
                running.set(false);
                refuse(sender, refused.reason());
                return;
            }
            lease = ((ImportApplyCoordinator.Acquired) acquisition).lease();
        }
        // Worlds are read here, on the server thread, and handed to the worker.
        Map<String, ResolvedWorld> loaded = new LinkedHashMap<>();
        for (World world : plugin.getServer().getWorlds()) loaded.put(world.getName(), new ResolvedWorld(world.getUID(), world.getName()));
        plugin.getMessageManager().send(sender, Messages.IMPORT_LEGACY_STARTED, "{mode}", applyRun == null ? "a dry run" : "apply " + applyRun);
        final ImportApplyCoordinator.Lease acquiredLease = lease;
        try {
            io.execute(() -> {
                boolean handedOff = false;
                try {
                    if (applyRun == null) runDry(sender, settings, loaded);
                    else handedOff = runApply(sender, settings, loaded, applyRun, acquiredLease);
                } catch (Exception | LinkageError failure) {
                    String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage().lines().findFirst().orElse("");
                    // Missing or unreadable inputs are the operator's to fix: their message is enough.
                    boolean expected = failure instanceof IOException || failure instanceof SQLException;
                    plugin.logger().warning("Legacy import failed: " + (expected ? reason : failure.toString()));
                    tell(sender, Messages.IMPORT_LEGACY_FAILED, "{reason}", reason);
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

    // -- Dry run and apply (worker thread) ---------------------------------------------------------

    private record Read(LegacyH2Source.Snapshot snapshot, LegacyAlliumSnapshot data, String driver, LegacyDestinationPlan plan) {
    }

    private Read read(Settings settings, Map<String, ResolvedWorld> loaded, Path copy, boolean destinationTablesMayBeMissing) throws Exception {
        LegacyH2Source.Snapshot snapshot = LegacyH2Source.snapshot(settings.source(), copy);
        List<Path> jars = settings.driver() != null ? List.of(settings.driver()) : importJars();
        try (LegacyH2Source source = LegacyH2Source.load(jars).orElseThrow(() -> new IOException(
                "no H2 driver: put the legacy Allium jar (or an h2 jar) in plugins/" + plugin.getDataFolder().getName() + "/import/"))) {
            LegacyAlliumSnapshot data;
            try (Connection c = source.openReadOnly(snapshot.copy(), settings.user(), settings.password())) {
                data = LegacyAlliumReader.read(c);
            }
            LegacyDestinationPlan.Worlds worlds = name -> {
                String target = settings.worlds().getOrDefault(name, name);
                return Optional.ofNullable(loaded.get(target));
            };
            Destination destination = destination(destinationTablesMayBeMissing);
            return new Read(snapshot, data, source.jar().getFileName() + " (" + source.driverClass() + ")",
                    LegacyDestinationPlan.build(data, worlds, destination));
        }
    }

    private void runDry(CommandSender sender, Settings settings, Map<String, ResolvedWorld> loaded) throws Exception {
        String runId = newRunId();
        Path dir = runDir(runId);
        Read read = read(settings, loaded, dir.resolve("source.mv.db"), true);
        writePlan(dir, runId, settings, read);
        writeRecords(dir.resolve("records.jsonl"), read.plan());
        writeSummary(dir.resolve("summary.txt"), runId, settings, read, null);
        writeOverrides(dir, read.plan());
        plugin.logger().info("Legacy import dry run " + runId + ": " + line(read.plan(), Domain.HOME) + " homes; "
                + line(read.plan(), Domain.WARP) + " warps; " + line(read.plan(), Domain.LOGOUT) + " logout positions. Reports: " + relative(dir));
        tell(sender, Messages.IMPORT_LEGACY_DRY_RUN, "{run}", runId, "{homes}", line(read.plan(), Domain.HOME),
                "{warps}", line(read.plan(), Domain.WARP), "{logouts}", line(read.plan(), Domain.LOGOUT),
                "{summary}", relative(dir.resolve("summary.txt")));
    }

    private boolean runApply(CommandSender sender, Settings settings, Map<String, ResolvedWorld> loaded, String runId,
                             ImportApplyCoordinator.Lease lease) throws Exception {
        Path dir = runDir(runId);
        Path planFile = dir.resolve("plan.json");
        if (!Files.isRegularFile(planFile)) {
            tell(sender, Messages.IMPORT_LEGACY_REFUSED, "{reason}", "no dry run " + runId + " (expected " + relative(planFile) + ")");
            return false;
        }
        Map<String, Object> reviewed = Json.parseObject(Files.readString(planFile, StandardCharsets.UTF_8));
        var db = plugin.getDatabaseManager();
        TravelRepository travel = new TravelRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        TeleportRepository teleports = new TeleportRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        ImportLedger ledger = new ImportLedger(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        travel.migrate();
        teleports.migrate();
        ledger.migrate();
        if (ledger.ran(runId)) {
            tell(sender, Messages.IMPORT_LEGACY_REFUSED, "{reason}", runId + " was already applied; run a new dry run");
            return false;
        }
        Read read = read(settings, loaded, dir.resolve("apply-source.mv.db"), false);
        if (!read.snapshot().sha256().equals(reviewed.get("sourceSha256"))) {
            tell(sender, Messages.IMPORT_LEGACY_REFUSED, "{reason}", "the legacy database changed since " + runId + "; run a new dry run");
            return false;
        }
        if (!read.plan().sha256().equals(reviewed.get("planSha256"))) {
            tell(sender, Messages.IMPORT_LEGACY_REFUSED, "{reason}", "the plan differs from " + runId + " (this server's homes, warps or the world map changed); run a new dry run");
            return false;
        }
        ledger.startRun(runId, LegacyDestinationPlan.SOURCE, read.snapshot().sha256(), read.plan().sha256());
        List<LegacyDestinationApplier.Outcome> outcomes;
        try {
            outcomes = LegacyDestinationApplier.apply(read.plan(), runId, ledger, travel, teleports);
        } catch (SQLException | RuntimeException failure) {
            ledger.finishRun(runId, "failed: " + failure.getMessage());
            throw failure;
        }
        var counts = LegacyDestinationApplier.counts(outcomes);
        ledger.finishRun(runId, "applied " + counts);
        writeOutcomes(dir.resolve("apply.jsonl"), outcomes);
        writeSummary(dir.resolve("apply-summary.txt"), runId, settings, read, outcomes);
        var freshWarps = travel.warpEntries();
        String homes = applied(outcomes, Domain.HOME), warps = applied(outcomes, Domain.WARP), logouts = applied(outcomes, Domain.LOGOUT);
        plugin.logger().info("Legacy import " + runId + " applied: " + homes + " homes; " + warps + " warps; " + logouts + " logout positions.");
        String summaryPath = relative(dir.resolve("apply-summary.txt"));
        var scheduler = plugin.getScheduler();
        TaskHandle handle = scheduler.runOnServer(() -> {
            try {
                if (plugin.getModuleManager().getModule("travel") instanceof TravelModule module && module.isEnabled() && module.travel() != null) {
                    module.travel().publishWarps(freshWarps);
                }
            } finally {
                finish(lease);
            }
            tell(sender, Messages.IMPORT_LEGACY_APPLIED, "{run}", runId, "{homes}", homes, "{warps}", warps, "{logouts}", logouts,
                    "{summary}", summaryPath);
        });
        if (scheduler instanceof AlliumScheduler && handle == TaskHandle.NONE) {
            return false;
        }
        return true;
    }

    /** What this server holds. A dry run never creates tables: missing ones read as empty. */
    private Destination destination(boolean tablesMayBeMissing) throws SQLException {
        var db = plugin.getDatabaseManager();
        TravelRepository travel = new TravelRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        TeleportRepository teleports = new TeleportRepository(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        ImportLedger ledger = new ImportLedger(db.connectionSource(), db.dialect(), System::currentTimeMillis);
        boolean homes = !tablesMayBeMissing || exists("allium_travel_homes");
        boolean warps = !tablesMayBeMissing || exists("allium_travel_warps");
        boolean locations = !tablesMayBeMissing || exists(TeleportRepository.LOCATIONS_TABLE);
        boolean records = !tablesMayBeMissing || exists(ImportLedger.RECORDS);
        return new Destination(homes ? travel.allHomes() : Map.of(), warps ? travel.warps() : Map.of(),
                locations ? teleports.slot("logout") : Map.of(), records ? ledger.hashes(LegacyDestinationPlan.SOURCE) : Map.of());
    }

    private boolean exists(String table) throws SQLException {
        try (Connection c = plugin.getDatabaseManager().connectionSource().connection()) {
            for (String name : List.of(table, table.toUpperCase(Locale.ROOT))) {
                try (ResultSet r = c.getMetaData().getTables(null, null, name, null)) {
                    if (r.next()) return true;
                }
            }
            return false;
        }
    }

    // -- Settings ------------------------------------------------------------------------------------

    private record Settings(Path source, Path driver, String user, String password, Map<String, String> worlds) {
    }

    private Settings settings() throws IOException {
        Path file = plugin.getDataFolder().toPath().resolve(SETTINGS);
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    # Legacy Allium import: /allium import legacy (dry run), then /allium import legacy apply <run>.
                    # Paths are relative to this plugin folder. See docs/legacy-import.md.
                    source: database/sfcore.mv.db
                    # The jar with the H2 driver: the legacy Allium jar or an h2 jar. Empty: the first such jar in import/.
                    driver: ""
                    # The database login. Legacy Allium used sa with no password.
                    user: sa
                    password: ""
                    # Legacy world name -> world on this server. Unlisted worlds use a loaded world of the same name.
                    worlds: {}
                    """, StandardCharsets.UTF_8);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        Path base = plugin.getDataFolder().toPath();
        String sourceName = yaml.getString("source", "database/sfcore.mv.db");
        Path source = base.resolve(sourceName).normalize();
        if (source.equals(base.resolve("database/sfcore.mv.db").normalize())) {
            source = LegacyTidy.source(base, "database/sfcore.mv.db");
        }
        String driverText = yaml.getString("driver", "");
        Path driver = driverText == null || driverText.isBlank() ? null : base.resolve(driverText).normalize();
        Map<String, String> worlds = new LinkedHashMap<>();
        var section = yaml.getConfigurationSection("worlds");
        if (section != null) for (String key : section.getKeys(false)) worlds.put(key, String.valueOf(section.getString(key)));
        return new Settings(source, driver, yaml.getString("user", LegacyH2Source.LEGACY_USER), yaml.getString("password", ""),
                Map.copyOf(worlds));
    }

    private List<Path> importJars() throws IOException {
        Path dir = plugin.getDataFolder().toPath().resolve("import");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList();
        }
    }

    private Path runDir(String runId) {
        return plugin.getDataFolder().toPath().resolve("import/legacy").resolve(runId);
    }

    private String newRunId() {
        String base = "legacy-" + RUN_ID.format(Instant.now());
        String id = base;
        for (int n = 2; Files.exists(runDir(id)); n++) id = base + "-" + n;
        return id;
    }

    private String relative(Path path) {
        return "plugins/" + plugin.getDataFolder().getName() + "/" + plugin.getDataFolder().toPath().relativize(path).toString().replace('\\', '/');
    }

    // -- Reports ------------------------------------------------------------------------------------

    private static String line(LegacyDestinationPlan plan, Domain domain) {
        Map<Status, Long> c = plan.counts(domain);
        return c.get(Status.IMPORT) + " to import, " + c.get(Status.PRESENT) + " already there, " + c.get(Status.REPLAYED) + " already imported, "
                + c.get(Status.CONFLICT) + " conflicts, " + c.get(Status.BLOCKED) + " blocked, " + c.get(Status.SKIPPED) + " skipped";
    }

    private static String applied(List<LegacyDestinationApplier.Outcome> outcomes, Domain domain) {
        var c = LegacyDestinationApplier.counts(outcomes.stream().filter(o -> o.record().domain() == domain).toList());
        return c.get(LegacyDestinationApplier.Result.APPLIED) + " imported, " + c.get(LegacyDestinationApplier.Result.ADOPTED) + " already there, "
                + c.get(LegacyDestinationApplier.Result.REPLAYED) + " already imported, "
                + (c.get(LegacyDestinationApplier.Result.CONFLICT) + c.get(LegacyDestinationApplier.Result.LIMIT)) + " conflicts, "
                + c.get(LegacyDestinationApplier.Result.NEWER) + " kept newer";
    }

    private void writePlan(Path dir, String runId, Settings settings, Read read) throws IOException {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("run", runId);
        plan.put("createdAt", Instant.now().toString());
        plan.put("source", relative(settings.source()));
        plan.put("sourceSha256", read.snapshot().sha256());
        plan.put("sourceBytes", read.snapshot().bytes());
        plan.put("driver", read.driver());
        plan.put("h2", read.data().h2Version());
        plan.put("planSha256", read.plan().sha256());
        plan.put("worldMap", new LinkedHashMap<>(settings.worlds()));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Domain d : LegacyDestinationApplier.domains()) {
            Map<String, Object> per = new LinkedHashMap<>();
            read.plan().counts(d).forEach((s, n) -> per.put(s.name().toLowerCase(Locale.ROOT), n));
            counts.put(d.id(), per);
        }
        plan.put("counts", counts);
        plan.put("unnamedHomes", read.data().unnamedHomes());
        plan.put("supersededLogoutRows", read.data().supersededLogouts());
        plan.put("otherLocationTypes", new LinkedHashMap<>(read.data().otherLocationTypes()));
        plan.put("notes", new ArrayList<>(read.data().notes()));
        Files.writeString(dir.resolve("plan.json"), Json.write(plan) + "\n", StandardCharsets.UTF_8);
    }

    private static Map<String, Object> json(LegacyDestinationPlan.Record r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", r.domain().id());
        m.put("key", r.key());
        m.put("status", r.status().name().toLowerCase(Locale.ROOT));
        if (!r.reason().isEmpty()) m.put("reason", r.reason());
        m.put("legacy", r.legacy());
        if (r.location() != null) {
            var l = r.location();
            m.put("world", l.worldName());
            m.put("x", l.x());
            m.put("y", l.y());
            m.put("z", l.z());
            m.put("yaw", (double) l.yaw());
            m.put("pitch", (double) l.pitch());
        }
        if (!r.payloadHash().isEmpty()) m.put("payloadSha256", r.payloadHash());
        return m;
    }

    private static void writeRecords(Path file, LegacyDestinationPlan plan) throws IOException {
        StringBuilder out = new StringBuilder();
        for (var r : plan.records()) out.append(Json.write(json(r))).append('\n');
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeOutcomes(Path file, List<LegacyDestinationApplier.Outcome> outcomes) throws IOException {
        StringBuilder out = new StringBuilder();
        for (var o : outcomes) {
            Map<String, Object> m = json(o.record());
            m.put("result", o.result().name().toLowerCase(Locale.ROOT));
            out.append(Json.write(m)).append('\n');
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    private void writeSummary(Path file, String runId, Settings settings, Read read, List<LegacyDestinationApplier.Outcome> outcomes) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append(outcomes == null ? "Legacy Allium import dry run " : "Legacy Allium import applied: ").append(runId).append('\n');
        out.append("Source: ").append(relative(settings.source())).append(" (SHA-256 ").append(read.snapshot().sha256()).append(", ")
                .append(read.snapshot().bytes()).append(" bytes)\n");
        out.append("Read with: ").append(read.driver()).append(", ").append(read.data().h2Version()).append('\n');
        for (String note : read.data().notes()) out.append("Note: ").append(note).append('\n');
        out.append('\n');
        for (Domain d : LegacyDestinationApplier.domains()) {
            out.append(switch (d) { case HOME -> "Homes"; case WARP -> "Warps"; case LOGOUT -> "Logout positions"; }).append(": ")
                    .append(outcomes == null ? line(read.plan(), d) : applied(outcomes, d)).append('\n');
        }
        out.append("HOME rows without a name (legacy never used them): ").append(read.data().unnamedHomes()).append('\n');
        out.append("Older LOGOUT rows behind each player's newest: ").append(read.data().supersededLogouts()).append('\n');
        if (!read.data().otherLocationTypes().isEmpty()) {
            out.append("Location rows with no destination (not imported): ").append(read.data().otherLocationTypes()).append('\n');
        }
        if (!read.plan().overrides().isEmpty()) {
            out.append("Legacy staff-set home limits: ").append(read.plan().overrides().size())
                    .append(" (see staff-overrides.yml; Allium's override is a minimum, legacy's was the limit)\n");
        }
        List<LegacyDestinationPlan.Record> attention = read.plan().records().stream()
                .filter(r -> r.status() == Status.CONFLICT || r.status() == Status.BLOCKED).toList();
        if (!attention.isEmpty()) {
            out.append("\nNeeds a decision (").append(attention.size()).append("; nothing was guessed):\n");
            for (var r : attention.subList(0, Math.min(500, attention.size()))) {
                out.append("  ").append(r.status().name().toLowerCase(Locale.ROOT)).append(' ').append(r.domain().id()).append(' ')
                        .append(r.key()).append(": ").append(r.reason()).append(" [").append(r.legacy()).append("]\n");
            }
            if (attention.size() > 500) out.append("  ... ").append(attention.size() - 500).append(" more in records.jsonl\n");
        }
        if (outcomes == null) out.append("\nApply with: /allium import legacy apply ").append(runId).append(" (on an empty server)\n");
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeOverrides(Path dir, LegacyDestinationPlan plan) throws IOException {
        if (plan.overrides().isEmpty()) return;
        StringBuilder out = new StringBuilder("# Legacy Allium staff-set home limits. Allium's homes.staff-overrides is a minimum:\n"
                + "# it never lowers a higher rank limit. Paste what you want to keep into config.yml.\n"
                + "homes:\n  staff-overrides:\n");
        for (var row : plan.overrides()) out.append("    ").append(row.owner()).append(": ").append(Math.min(row.maxHomes(), 1000)).append('\n');
        Files.writeString(dir.resolve("staff-overrides.yml"), out.toString(), StandardCharsets.UTF_8);
    }

    private void refuse(CommandSender sender, String reason) {
        plugin.getMessageManager().send(sender, Messages.IMPORT_LEGACY_REFUSED, "{reason}", reason);
    }

    private void tell(CommandSender sender, Messages key, String... replacements) {
        codes.castled.allium.command.essentials.UtilitySupport.send(plugin, sender, key, replacements);
    }

}
