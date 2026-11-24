package codes.castled.allium.importer.essentials;

import codes.castled.allium.chat.ChatRepository;
import codes.castled.allium.economy.EconomyEngine;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomySchema;
import codes.castled.allium.importer.ImportLedger;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Domain;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Record;
import codes.castled.allium.importer.essentials.EssentialsImportPlan.Status;
import codes.castled.allium.kit.KitDefinition;
import codes.castled.allium.kit.KitDefinitionLoader;
import codes.castled.allium.kit.KitRepository;
import codes.castled.allium.mail.MailKind;
import codes.castled.allium.mail.MailLimits;
import codes.castled.allium.mail.MailRecord;
import codes.castled.allium.mail.MailRepository;
import codes.castled.allium.mail.MailState;
import codes.castled.allium.messaging.IgnoreRepository;
import codes.castled.allium.moderation.JailAssignment;
import codes.castled.allium.moderation.JailRecord;
import codes.castled.allium.moderation.ModerationRepository;
import codes.castled.allium.moderation.MuteRecord;
import codes.castled.allium.naming.PlayerNameRepository;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceRepository;
import codes.castled.allium.storage.SqlDialect;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.travel.TravelRepository;
import codes.castled.allium.worth.WorthSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies a reviewed {@link EssentialsImportPlan} into Allium's repositories, recording every
 * applied or adopted entry in {@link ImportLedger} under {@code source = "essentials"}.
 */
public final class EssentialsImportApplier {

    static final int CHUNK = 100;

    public enum Result { APPLIED, ADOPTED, REPLAYED, CONFLICT, LIMIT, NEWER, SKIPPED }

    public record Outcome(Record record, Result result) {}

    public record Targets(
            String serverOrigin,
            @Nullable EconomyEngine economy,
            TravelRepository travel,
            TeleportRepository teleports,
            MailRepository mail,
            ChatRepository chat,
            IgnoreRepository ignores,
            PreferenceRepository preferences,
            ModerationRepository moderation,
            PlayerNameRepository names,
            @Nullable KitRepository kits,
            @Nullable KitDefinitionLoader kitLoader,
            @Nullable Path worthFile
    ) {
        public Targets(
                String serverOrigin,
                @Nullable EconomyEngine economy,
                TravelRepository travel,
                TeleportRepository teleports,
                MailRepository mail,
                ChatRepository chat,
                IgnoreRepository ignores,
                PreferenceRepository preferences,
                ModerationRepository moderation,
                PlayerNameRepository names
        ) {
            this(serverOrigin, economy, travel, teleports, mail, chat, ignores, preferences, moderation, names,
                    null, null, null);
        }
    }

    @FunctionalInterface
    interface BeforeLedgerRecord {
        void beforeRecord(Connection c, Record record, Result result) throws SQLException;
    }

    private EssentialsImportApplier() {}

    public static List<Outcome> apply(EssentialsImportPlan plan, String runId, ImportLedger ledger, Targets targets) throws SQLException {
        return apply(plan, runId, ledger, targets, null);
    }

    static List<Outcome> apply(EssentialsImportPlan plan, String runId, ImportLedger ledger, Targets targets,
                               @Nullable BeforeLedgerRecord beforeLedger) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        List<Record> work = plan.records().stream()
                .filter(r -> r.status() == Status.IMPORT || r.status() == Status.PRESENT || r.status() == Status.REPLAYED)
                .toList();
        List<Outcome> out = new ArrayList<>();

        // All shared-database SQL domains share one atomic transaction with their ImportLedger receipts.
        // Preserve plan ordering (JAIL precedes JAILED) and apply in bounded transactional chunks.
        List<Record> transactional = work.stream()
                .filter(r -> r.domain() != Domain.BALANCE && r.domain() != Domain.KIT && r.domain() != Domain.WORTH)
                .toList();
        for (int start = 0; start < transactional.size(); start += CHUNK) {
            List<Record> chunk = transactional.subList(start, Math.min(transactional.size(), start + CHUNK));
            out.addAll(ledger.transaction(c -> {
                List<Outcome> done = new ArrayList<>();
                for (Record r : chunk) done.add(new Outcome(r, applyTransactional(c, r, runId, ledger, targets, beforeLedger)));
                return done;
            }));
        }

        // File-backed kit definitions write plugins/Allium/kits/<name>.yml and then record their receipts.
        for (Record r : work) {
            if (r.domain() == Domain.KIT) {
                out.add(new Outcome(r, applyKitRecord(r, runId, ledger, targets.kitLoader(), beforeLedger)));
            }
        }

        // File-backed worth prices merge atomically into plugins/Allium/worth.yml and then record receipts.
        List<Record> worthWork = work.stream().filter(r -> r.domain() == Domain.WORTH).toList();
        if (!worthWork.isEmpty()) {
            out.addAll(applyWorthRecords(worthWork, runId, ledger, targets.worthFile(), beforeLedger));
        }

        // Balance imports use EconomyEngine's atomic transaction callback (account + operation journal + ledger receipt).
        for (Record r : work) {
            if (r.domain() == Domain.BALANCE) {
                out.add(new Outcome(r, applyBalanceRecord(r, runId, ledger, targets.economy(), beforeLedger)));
            }
        }
        return out;
    }

    public static Map<Result, Long> counts(List<Outcome> outcomes) {
        Map<Result, Long> out = new EnumMap<>(Result.class);
        for (Result r : Result.values()) out.put(r, 0L);
        for (Outcome o : outcomes) out.merge(o.result(), 1L, Long::sum);
        return out;
    }

    private static Result applyTransactional(Connection c, Record r, String runId, ImportLedger ledger, Targets targets,
                                             @Nullable BeforeLedgerRecord beforeLedger) throws SQLException {
        String source = EssentialsImportPlan.SOURCE;
        var ledgered = ledger.hash(c, source, r.domain().id(), r.key());
        if (ledgered.isPresent()) return ledgered.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
        if (r.status() == Status.REPLAYED) return Result.CONFLICT;
        Result result = switch (r.domain()) {
            case HOME -> switch (targets.travel().importHome(c, r.owner(), r.name(), r.location())) {
                case CREATED -> Result.APPLIED;
                case PRESENT -> Result.ADOPTED;
                case CONFLICT -> Result.CONFLICT;
                case LIMIT -> Result.LIMIT;
            };
            case WARP -> switch (targets.travel().importWarp(c, r.name(), r.location())) {
                case CREATED -> Result.APPLIED;
                case PRESENT -> Result.ADOPTED;
                case CONFLICT, LIMIT -> Result.CONFLICT;
            };
            case SPAWN -> switch (targets.teleports().importLocation(c, EssentialsImportPlan.SERVER_SPAWN_OWNER, r.name(), r.location())) {
                case CREATED -> Result.APPLIED;
                case PRESENT -> Result.ADOPTED;
                case NEWER -> Result.NEWER;
            };
            case LOGOUT -> switch (targets.teleports().importLocation(c, r.owner(), "logout", r.location())) {
                case CREATED -> Result.APPLIED;
                case PRESENT -> Result.ADOPTED;
                case NEWER -> Result.NEWER;
            };
            case BACK -> switch (targets.teleports().importLocation(c, r.owner(), "back", r.location())) {
                case CREATED -> Result.APPLIED;
                case PRESENT -> Result.ADOPTED;
                case NEWER -> Result.NEWER;
            };
            case KIT_USAGE -> {
                if (targets.kits() == null || r.kitUsage() == null) yield Result.SKIPPED;
                var u = r.kitUsage();
                yield switch (targets.kits().importUsage(c, r.owner(), u.kitName(), u.lastClaimedAt(), u.oneTimeClaimed())) {
                    case CREATED -> Result.APPLIED;
                    case PRESENT -> Result.ADOPTED;
                    case NEWER -> Result.NEWER;
                };
            }
            case MAIL -> {
                var m = r.mail();
                UUID mailId = UUID.nameUUIDFromBytes(("essentials-mail:" + m.sourceRef()).getBytes(StandardCharsets.UTF_8));
                var mailRecord = new MailRecord(
                        mailId, r.owner(), m.senderUuid(), m.senderName(), MailKind.TEXT,
                        m.body(), null, 0, MailState.READY, null, null, targets.serverOrigin(),
                        m.unread(), m.createdAt(), m.expiresAt(), EssentialsImportPlan.SOURCE,
                        m.sourceRef(), 0L, 0);
                if (r.status() == Status.PRESENT) {
                    yield Result.ADOPTED;
                }
                targets.mail().send(c, mailRecord, MailLimits.DEFAULTS, true);
                yield Result.APPLIED;
            }
            case NICKNAME -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                var state = targets.chat().load(c, r.owner());
                if (state.nickname() != null && !state.nickname().isBlank()) {
                    yield state.nickname().equals(r.textValue()) ? Result.ADOPTED : Result.CONFLICT;
                }
                targets.chat().nickname(c, r.owner(), r.textValue());
                yield Result.APPLIED;
            }
            case IGNORE -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                boolean added = targets.ignores().add(c, r.owner(), r.targetUuid());
                yield added ? Result.APPLIED : Result.ADOPTED;
            }
            case PREFERENCE -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                targets.preferences().apply(c, r.owner(), PreferenceChange.set(r.preferenceKey(), r.preferenceBool()));
                yield Result.APPLIED;
            }
            case JAIL -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                var existing = targets.moderation().loadJail(c, r.name());
                if (existing.isPresent()) {
                    yield EssentialsImportPlan.sameCoords(existing.get().location(), r.location()) ? Result.ADOPTED : Result.CONFLICT;
                }
                targets.moderation().saveJail(c, new JailRecord(r.name(), r.location()), "import:essentials");
                yield Result.APPLIED;
            }
            case MUTE -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                var m = r.mute();
                var existing = targets.moderation().loadActiveMute(c, r.owner());
                if (existing.isPresent()) {
                    yield Objects.equals(existing.get().expiresAt(), m.expiresAt())
                            && existing.get().reason().equals(m.reason()) ? Result.ADOPTED : Result.CONFLICT;
                }
                targets.moderation().saveMute(c, new MuteRecord(r.owner(), m.playerName(), "import:essentials", m.reason(), m.createdAt(), m.expiresAt()));
                yield Result.APPLIED;
            }
            case JAILED -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                var j = r.jailed();
                var existing = targets.moderation().loadJailed(c, r.owner());
                if (existing.isPresent()) {
                    yield existing.get().jailName().equalsIgnoreCase(j.jailName())
                            && Objects.equals(existing.get().expiresAt(), j.expiresAt()) ? Result.ADOPTED : Result.CONFLICT;
                }
                boolean saved = targets.moderation().setJailed(c, new JailAssignment(
                        r.owner(), j.playerName(), "import:essentials", "Imported from Essentials",
                        j.createdAt(), j.jailName(), j.returnLocation(), j.expiresAt()));
                yield saved ? Result.APPLIED : Result.CONFLICT;
            }
            case NAME -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                if (targets.names().byUuid(c, r.owner()).isPresent()) yield Result.ADOPTED;
                targets.names().record(c, r.owner(), r.textValue(), System.currentTimeMillis());
                yield Result.APPLIED;
            }
            default -> Result.SKIPPED;
        };
        if (result == Result.APPLIED || result == Result.ADOPTED) {
            if (beforeLedger != null) beforeLedger.beforeRecord(c, r, result);
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), result.name(), runId);
        }
        return result;
    }

    private static Result applyBalanceRecord(Record r, String runId, ImportLedger ledger, @Nullable EconomyEngine engine,
                                             @Nullable BeforeLedgerRecord beforeLedger) throws SQLException {
        String source = EssentialsImportPlan.SOURCE;
        Result precheck = ledger.transaction(c -> {
            var ledgered = ledger.hash(c, source, r.domain().id(), r.key());
            if (ledgered.isPresent()) return ledgered.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
            if (r.status() == Status.REPLAYED) return Result.CONFLICT;
            return null;
        });
        if (precheck != null) return precheck;
        return applyBalance(r, runId, ledger, engine, beforeLedger);
    }

    private static Result applyBalance(Record r, String runId, ImportLedger ledger, @Nullable EconomyEngine engine,
                                       @Nullable BeforeLedgerRecord beforeLedger)
            throws SQLException {
        if (engine == null) throw new SQLException("Allium economy became unavailable during import");
        if (r.status() == Status.PRESENT) return adoptBalance(r, runId, ledger, engine.dialect(), beforeLedger);

        // The import receipt identifies the payload across attempts. Each attempt gets its own economy request.
        String source = EssentialsImportPlan.SOURCE;
        UUID operation = UUID.randomUUID();
        EconomyRequest request = engine.request(operation, "import:essentials", "balance:" + operation,
                EconomyRequest.Kind.OPEN_IMPORTED, "import:essentials", r.owner(), null, r.money());
        var mutation = engine.execute(request, c -> {
            if (ledger.hash(c, source, r.domain().id(), r.key()).isPresent()) return false;
            if (beforeLedger != null) beforeLedger.beforeRecord(c, r, Result.APPLIED);
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), Result.APPLIED.name(), runId);
            return true;
        });
        if (mutation instanceof EconomyResult.Committed) return Result.APPLIED;
        if (mutation instanceof EconomyResult.Rejected rejected) {
            if ("ACCOUNT_EXISTS".equals(rejected.reason())) return adoptBalance(r, runId, ledger, engine.dialect(), beforeLedger);
            if ("SETTINGS_CHANGED".equals(rejected.reason())) throw new SQLException("Economy settings changed during import");
            if (!"WORKFLOW_REFUSED".equals(rejected.reason())) return Result.CONFLICT;
        }

        // A lost commit acknowledgment can be resolved by the receipt written with the account.
        return ledger.transaction(c -> {
            var recorded = ledger.hash(c, source, r.domain().id(), r.key());
            if (recorded.isPresent()) return recorded.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
            String detail = "missing import receipt";
            if (mutation instanceof EconomyResult.Unknown unknown) detail = unknown.diagnostic();
            if (mutation instanceof EconomyResult.Unavailable unavailable) detail = unavailable.diagnostic();
            throw new SQLException("Could not confirm imported balance for " + r.owner() + ": " + detail);
        });
    }

    private static Result adoptBalance(Record r, String runId, ImportLedger ledger, SqlDialect dialect,
                                       @Nullable BeforeLedgerRecord beforeLedger) throws SQLException {
        return ledger.transaction(c -> {
            String source = EssentialsImportPlan.SOURCE;
            var recorded = ledger.hash(c, source, r.domain().id(), r.key());
            if (recorded.isPresent()) return recorded.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
            String rowLock = dialect == SqlDialect.MYSQL ? " FOR UPDATE" : "";
            try (var statement = c.prepareStatement("SELECT balance_minor, reserved_minor FROM "
                    + EconomySchema.ACCOUNTS + " WHERE account_uuid = ?" + rowLock)) {
                statement.setString(1, r.owner().toString());
                try (var row = statement.executeQuery()) {
                    if (!row.next() || row.getLong(1) != r.money().minor() || row.getLong(2) != 0) {
                        return Result.CONFLICT;
                    }
                }
            }
            if (beforeLedger != null) beforeLedger.beforeRecord(c, r, Result.ADOPTED);
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), Result.ADOPTED.name(), runId);
            return Result.ADOPTED;
        });
    }

    private static Result applyKitRecord(Record r, String runId, ImportLedger ledger, @Nullable KitDefinitionLoader kitLoader,
                                         @Nullable BeforeLedgerRecord beforeLedger) throws SQLException {
        String source = EssentialsImportPlan.SOURCE;
        Result precheck = ledger.transaction(c -> {
            var ledgered = ledger.hash(c, source, r.domain().id(), r.key());
            if (ledgered.isPresent()) return ledgered.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
            if (r.status() == Status.REPLAYED) return Result.CONFLICT;
            return null;
        });
        if (precheck != null) return precheck;
        if (kitLoader == null || r.kit() == null) return Result.SKIPPED;

        var kit = r.kit();
        Result outcome;
        if (r.status() == Status.PRESENT) {
            if (!kitLoader.exists(kit.name())) return Result.CONFLICT;
            outcome = Result.ADOPTED;
        } else {
            if (kitLoader.exists(kit.name())) return Result.CONFLICT;
            KitDefinition def = new KitDefinition(
                    kit.name(), true, kit.cooldownSeconds(), kit.oneTime(),
                    KitDefinition.ZERO_COST, kit.items(),
                    KitDefinitionLoader.DIRECTORY + "/" + kit.name() + ".yml");
            try {
                kitLoader.save(def);
            } catch (IOException io) {
                throw new SQLException("Could not write imported kit '" + kit.name() + "': " + io.getMessage(), io);
            }
            outcome = Result.APPLIED;
        }

        final Result finalOutcome = outcome;
        return ledger.transaction(c -> {
            var recorded = ledger.hash(c, source, r.domain().id(), r.key());
            if (recorded.isPresent()) return recorded.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
            if (beforeLedger != null) beforeLedger.beforeRecord(c, r, finalOutcome);
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), finalOutcome.name(), runId);
            return finalOutcome;
        });
    }

    private static List<Outcome> applyWorthRecords(List<Record> worthWork, String runId, ImportLedger ledger,
                                                   @Nullable Path worthFile,
                                                   @Nullable BeforeLedgerRecord beforeLedger) throws SQLException {
        if (worthFile == null) {
            return worthWork.stream().map(r -> new Outcome(r, Result.SKIPPED)).toList();
        }
        String source = EssentialsImportPlan.SOURCE;
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.isRegularFile(worthFile)) {
            try {
                yaml.loadFromString(Files.readString(worthFile, StandardCharsets.UTF_8));
            } catch (IOException | InvalidConfigurationException e) {
                throw new SQLException("Could not read Allium worth.yml: " + e.getMessage(), e);
            }
        }
        WorthSettings existingSettings = WorthSettings.read(yaml, ignored -> null, ignored -> {});
        ConfigurationSection worthSection = yaml.getConfigurationSection("worth");
        if (worthSection == null) worthSection = yaml.createSection("worth");
        Map<String, String> existingKeys = new HashMap<>();
        for (String k : worthSection.getKeys(false)) {
            existingKeys.put(WorthSettings.key(k), k);
        }

        Map<String, String> ledgerMap = ledger.transaction(c -> {
            Map<String, String> found = new HashMap<>();
            for (Record r : worthWork) {
                ledger.hash(c, source, r.domain().id(), r.key()).ifPresent(h -> found.put(r.key(), h));
            }
            return found;
        });

        List<Outcome> plannedOutcomes = new ArrayList<>(worthWork.size());
        List<Outcome> toRecord = new ArrayList<>();
        boolean dirty = false;
        for (Record r : worthWork) {
            String ledgered = ledgerMap.get(r.key());
            if (ledgered != null) {
                plannedOutcomes.add(new Outcome(r, ledgered.equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT));
                continue;
            }
            if (r.status() == Status.REPLAYED) {
                plannedOutcomes.add(new Outcome(r, Result.CONFLICT));
                continue;
            }
            if (r.worth() == null) {
                plannedOutcomes.add(new Outcome(r, Result.SKIPPED));
                continue;
            }
            var w = r.worth();
            String normKey = WorthSettings.key(w.materialKey());
            BigDecimal existingPrice = existingSettings.prices().get(normKey);
            if (r.status() == Status.PRESENT) {
                if (existingPrice != null && existingPrice.compareTo(w.price()) == 0) {
                    Outcome o = new Outcome(r, Result.ADOPTED);
                    plannedOutcomes.add(o);
                    toRecord.add(o);
                } else {
                    plannedOutcomes.add(new Outcome(r, Result.CONFLICT));
                }
                continue;
            }
            if (existingKeys.containsKey(normKey)) {
                if (existingPrice != null && existingPrice.compareTo(w.price()) == 0) {
                    Outcome o = new Outcome(r, Result.ADOPTED);
                    plannedOutcomes.add(o);
                    toRecord.add(o);
                } else {
                    plannedOutcomes.add(new Outcome(r, Result.CONFLICT));
                }
                continue;
            }
            worthSection.set(w.materialKey(), w.price().signum() < 0 ? -1 : w.price().doubleValue());
            existingKeys.put(normKey, w.materialKey());
            dirty = true;
            Outcome o = new Outcome(r, Result.APPLIED);
            plannedOutcomes.add(o);
            toRecord.add(o);
        }

        if (dirty) {
            try {
                writeWorthFileAtomically(worthFile, yaml);
            } catch (IOException io) {
                throw new SQLException("Could not write Allium worth.yml: " + io.getMessage(), io);
            }
        }

        for (int start = 0; start < toRecord.size(); start += CHUNK) {
            List<Outcome> chunk = toRecord.subList(start, Math.min(toRecord.size(), start + CHUNK));
            ledger.transaction(c -> {
                for (Outcome o : chunk) {
                    Record r = o.record();
                    if (ledger.hash(c, source, r.domain().id(), r.key()).isPresent()) continue;
                    if (beforeLedger != null) beforeLedger.beforeRecord(c, r, o.result());
                    ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), o.result().name(), runId);
                }
                return null;
            });
        }
        return plannedOutcomes;
    }

    private static void writeWorthFileAtomically(Path file, YamlConfiguration yaml) throws IOException {
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent != null ? parent : Path.of("."), ".allium-worth-", ".tmp");
        try {
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling("worth.yml.before-edit"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
