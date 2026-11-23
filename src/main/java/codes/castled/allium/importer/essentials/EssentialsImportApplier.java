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
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.travel.TravelRepository;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
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
            PlayerNameRepository names
    ) {}

    private EssentialsImportApplier() {}

    public static List<Outcome> apply(EssentialsImportPlan plan, String runId, ImportLedger ledger, Targets targets) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        List<Record> work = plan.records().stream()
                .filter(r -> r.status() == Status.IMPORT || r.status() == Status.PRESENT || r.status() == Status.REPLAYED)
                .toList();
        List<Outcome> out = new ArrayList<>();

        // Transactional SQL domains (HOME, WARP, LOGOUT, MAIL) share a single ledger transaction per chunk.
        List<Record> transactional = work.stream()
                .filter(r -> switch (r.domain()) {
                    case HOME, WARP, SPAWN, LOGOUT, MAIL -> true;
                    default -> false;
                })
                .toList();
        for (int start = 0; start < transactional.size(); start += CHUNK) {
            List<Record> chunk = transactional.subList(start, Math.min(transactional.size(), start + CHUNK));
            out.addAll(ledger.transaction(c -> {
                List<Outcome> done = new ArrayList<>();
                for (Record r : chunk) done.add(new Outcome(r, applyTransactional(c, r, runId, ledger, targets)));
                return done;
            }));
        }

        // Repository-managed domains (JAIL before JAILED, BALANCE, NICKNAME, IGNORE, PREFERENCE, MUTE, NAME).
        List<Record> repoWork = work.stream()
                .filter(r -> switch (r.domain()) {
                    case HOME, WARP, SPAWN, LOGOUT, MAIL -> false;
                    default -> true;
                })
                .toList();
        for (Record r : repoWork) {
            out.add(new Outcome(r, applyRepository(r, runId, ledger, targets)));
        }
        return out;
    }

    public static Map<Result, Long> counts(List<Outcome> outcomes) {
        Map<Result, Long> out = new EnumMap<>(Result.class);
        for (Result r : Result.values()) out.put(r, 0L);
        for (Outcome o : outcomes) out.merge(o.result(), 1L, Long::sum);
        return out;
    }

    private static Result applyTransactional(Connection c, Record r, String runId, ImportLedger ledger, Targets targets) throws SQLException {
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
            default -> Result.SKIPPED;
        };
        if (result == Result.APPLIED || result == Result.ADOPTED) {
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), result.name(), runId);
        }
        return result;
    }

    private static Result applyRepository(Record r, String runId, ImportLedger ledger, Targets targets) throws SQLException {
        String source = EssentialsImportPlan.SOURCE;
        Result precheck = ledger.transaction(c -> {
            var ledgered = ledger.hash(c, source, r.domain().id(), r.key());
            if (ledgered.isPresent()) return ledgered.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
            if (r.status() == Status.REPLAYED) return Result.CONFLICT;
            return null;
        });
        if (precheck != null) return precheck;
        if (r.domain() == Domain.BALANCE) return applyBalance(r, runId, ledger, targets.economy());

        Result result = switch (r.domain()) {
            case NICKNAME -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                var state = targets.chat().load(r.owner());
                if (state.nickname() != null && !state.nickname().isBlank()) {
                    yield state.nickname().equals(r.textValue()) ? Result.ADOPTED : Result.CONFLICT;
                }
                targets.chat().nickname(r.owner(), r.textValue());
                yield Result.APPLIED;
            }
            case IGNORE -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                boolean added = targets.ignores().add(r.owner(), r.targetUuid());
                yield added ? Result.APPLIED : Result.ADOPTED;
            }
            case PREFERENCE -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                targets.preferences().apply(r.owner(), PreferenceChange.set(r.preferenceKey(), r.preferenceBool()));
                yield Result.APPLIED;
            }
            case JAIL -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                var existing = targets.moderation().loadJail(r.name());
                if (existing.isPresent()) {
                    yield EssentialsImportPlan.sameCoords(existing.get().location(), r.location()) ? Result.ADOPTED : Result.CONFLICT;
                }
                targets.moderation().saveJail(new JailRecord(r.name(), r.location()), "import:essentials");
                yield Result.APPLIED;
            }
            case MUTE -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                var m = r.mute();
                var existing = targets.moderation().loadActiveMute(r.owner());
                if (existing.isPresent()) {
                    yield Objects.equals(existing.get().expiresAt(), m.expiresAt())
                            && existing.get().reason().equals(m.reason()) ? Result.ADOPTED : Result.CONFLICT;
                }
                targets.moderation().saveMute(new MuteRecord(r.owner(), m.playerName(), "import:essentials", m.reason(), m.createdAt(), m.expiresAt()));
                yield Result.APPLIED;
            }
            case JAILED -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                var j = r.jailed();
                var existing = targets.moderation().loadJailed(r.owner());
                if (existing.isPresent()) {
                    yield existing.get().jailName().equalsIgnoreCase(j.jailName())
                            && Objects.equals(existing.get().expiresAt(), j.expiresAt()) ? Result.ADOPTED : Result.CONFLICT;
                }
                boolean saved = targets.moderation().setJailed(new JailAssignment(
                        r.owner(), j.playerName(), "import:essentials", "Imported from Essentials",
                        j.createdAt(), j.jailName(), j.returnLocation(), j.expiresAt()));
                yield saved ? Result.APPLIED : Result.CONFLICT;
            }
            case NAME -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                if (targets.names().byUuid(r.owner()).isPresent()) yield Result.ADOPTED;
                targets.names().record(r.owner(), r.textValue(), System.currentTimeMillis());
                yield Result.APPLIED;
            }
            default -> Result.SKIPPED;
        };

        if (result == Result.APPLIED || result == Result.ADOPTED) {
            Result finalResult = result;
            ledger.transaction(c -> {
                if (ledger.hash(c, source, r.domain().id(), r.key()).isEmpty()) {
                    ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), finalResult.name(), runId);
                }
                return null;
            });
        }
        return result;
    }

    private static Result applyBalance(Record r, String runId, ImportLedger ledger, @Nullable EconomyEngine engine)
            throws SQLException {
        if (engine == null) throw new SQLException("Allium economy became unavailable during import");
        if (r.status() == Status.PRESENT) return adoptBalance(r, runId, ledger);

        // The import receipt identifies the payload across attempts. Each attempt gets its own economy request.
        String source = EssentialsImportPlan.SOURCE;
        UUID operation = UUID.randomUUID();
        EconomyRequest request = engine.request(operation, "import:essentials", "balance:" + operation,
                EconomyRequest.Kind.OPEN_IMPORTED, "import:essentials", r.owner(), null, r.money());
        var mutation = engine.execute(request, c -> {
            if (ledger.hash(c, source, r.domain().id(), r.key()).isPresent()) return false;
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), Result.APPLIED.name(), runId);
            return true;
        });
        if (mutation instanceof EconomyResult.Committed) return Result.APPLIED;
        if (mutation instanceof EconomyResult.Rejected rejected) {
            if ("ACCOUNT_EXISTS".equals(rejected.reason())) return adoptBalance(r, runId, ledger);
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

    private static Result adoptBalance(Record r, String runId, ImportLedger ledger) throws SQLException {
        return ledger.transaction(c -> {
            String source = EssentialsImportPlan.SOURCE;
            var recorded = ledger.hash(c, source, r.domain().id(), r.key());
            if (recorded.isPresent()) return recorded.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
            try (var statement = c.prepareStatement("SELECT balance_minor, reserved_minor FROM "
                    + EconomySchema.ACCOUNTS + " WHERE account_uuid = ?")) {
                statement.setString(1, r.owner().toString());
                try (var row = statement.executeQuery()) {
                    if (!row.next() || row.getLong(1) != r.money().minor() || row.getLong(2) != 0) {
                        return Result.CONFLICT;
                    }
                }
            }
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), Result.ADOPTED.name(), runId);
            return Result.ADOPTED;
        });
    }
}
