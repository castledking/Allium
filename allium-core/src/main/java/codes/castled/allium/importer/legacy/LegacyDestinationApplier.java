package codes.castled.allium.importer.legacy;

import codes.castled.allium.economy.EconomyEngine;
import codes.castled.allium.economy.EconomyRequest;
import codes.castled.allium.economy.EconomyResult;
import codes.castled.allium.economy.EconomySchema;
import codes.castled.allium.importer.ImportLedger;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Domain;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Record;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Status;
import codes.castled.allium.mail.MailKind;
import codes.castled.allium.mail.MailLimits;
import codes.castled.allium.mail.MailRecord;
import codes.castled.allium.mail.MailRepository;
import codes.castled.allium.mail.MailState;
import codes.castled.allium.moderation.ModerationRepository;
import codes.castled.allium.preference.PreferenceChange;
import codes.castled.allium.preference.PreferenceKey;
import codes.castled.allium.preference.PreferenceRepository;
import codes.castled.allium.storage.SqlDialect;
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
 * Applies a reviewed {@link LegacyDestinationPlan}. Shared-SQL domains execute in transactional
 * chunks alongside their {@link ImportLedger} receipts, while balance imports execute through
 * {@link EconomyEngine}'s atomic journaled callback.
 */
public final class LegacyDestinationApplier {

    static final int CHUNK = 200;

    /** What happened to one record at apply time. */
    public enum Result { APPLIED, ADOPTED, REPLAYED, CONFLICT, LIMIT, NEWER, SKIPPED }

    public record Outcome(Record record, Result result) {
    }

    public record Targets(
            String serverOrigin,
            @Nullable EconomyEngine economy,
            TravelRepository travel,
            TeleportRepository teleports,
            @Nullable ModerationRepository moderation,
            @Nullable MailRepository mail,
            @Nullable PreferenceRepository preferences
    ) {
        public Targets(
                String serverOrigin,
                @Nullable EconomyEngine economy,
                TravelRepository travel,
                TeleportRepository teleports,
                @Nullable ModerationRepository moderation,
                @Nullable MailRepository mail
        ) {
            this(serverOrigin, economy, travel, teleports, moderation, mail, null);
        }
    }

    private LegacyDestinationApplier() {
    }

    public static List<Outcome> apply(LegacyDestinationPlan plan, String runId, ImportLedger ledger, TravelRepository travel,
                                      TeleportRepository teleports) throws SQLException {
        return apply(plan, runId, ledger, new Targets("legacy-import", null, travel, teleports, null, null));
    }

    public static List<Outcome> apply(LegacyDestinationPlan plan, String runId, ImportLedger ledger, Targets targets) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(targets, "targets");
        List<Record> work = plan.records().stream().filter(r -> switch (r.status()) {
            case IMPORT, PRESENT, REPLAYED -> true;
            default -> false;
        }).toList();
        List<Outcome> out = new ArrayList<>();

        List<Record> transactional = work.stream().filter(r -> r.domain() != Domain.BALANCE).toList();
        for (int start = 0; start < transactional.size(); start += CHUNK) {
            List<Record> chunk = transactional.subList(start, Math.min(transactional.size(), start + CHUNK));
            out.addAll(ledger.transaction(c -> {
                List<Outcome> done = new ArrayList<>();
                for (Record r : chunk) done.add(new Outcome(r, one(c, r, runId, ledger, targets)));
                return done;
            }));
        }

        for (Record r : work) {
            if (r.domain() == Domain.BALANCE) {
                out.add(new Outcome(r, applyBalanceRecord(r, runId, ledger, targets.economy())));
            }
        }
        return out;
    }

    public static Map<Result, Long> counts(List<Outcome> outcomes) {
        Map<Result, Long> out = new EnumMap<>(Result.class);
        for (Result result : Result.values()) out.put(result, 0L);
        for (Outcome o : outcomes) out.merge(o.result(), 1L, Long::sum);
        return out;
    }

    private static Result one(Connection c, Record r, String runId, ImportLedger ledger, Targets targets) throws SQLException {
        String source = LegacyDestinationPlan.SOURCE;
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
            case LOGOUT -> switch (targets.teleports().importLocation(c, r.owner(), "logout", r.location())) {
                case CREATED -> Result.APPLIED;
                case PRESENT -> Result.ADOPTED;
                case NEWER -> Result.NEWER;
            };
            case NOTE -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                if (targets.moderation() == null || r.note() == null) yield Result.SKIPPED;
                var n = r.note();
                targets.moderation().importNote(c, r.owner(), r.owner().toString(), n.actorName(), n.reason(), n.createdAt());
                yield Result.APPLIED;
            }
            case MAIL -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                if (targets.mail() == null || r.mail() == null) yield Result.SKIPPED;
                var m = r.mail();
                UUID mailId = UUID.nameUUIDFromBytes(("legacy-mail:" + m.sourceRef()).getBytes(StandardCharsets.UTF_8));
                var mailRecord = new MailRecord(
                        mailId, r.owner(), m.senderUuid(), m.senderName(), MailKind.TEXT,
                        m.body(), null, 0, MailState.READY, null, null, targets.serverOrigin(),
                        m.unread(), m.createdAt(), 0L, LegacyDestinationPlan.SOURCE,
                        m.sourceRef(), 0L, 0);
                targets.mail().send(c, mailRecord, MailLimits.DEFAULTS, true);
                yield Result.APPLIED;
            }
            case PVP -> {
                if (r.status() == Status.PRESENT) yield Result.ADOPTED;
                if (targets.preferences() == null || r.pvpEnabled() == null) yield Result.SKIPPED;
                targets.preferences().apply(c, r.owner(), PreferenceChange.set(PreferenceKey.PVP_ENABLED, r.pvpEnabled()));
                yield Result.APPLIED;
            }
            default -> Result.SKIPPED;
        };
        if (result == Result.APPLIED || result == Result.ADOPTED) {
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), result.name(), runId);
        }
        return result;
    }

    private static Result applyBalanceRecord(Record r, String runId, ImportLedger ledger, @Nullable EconomyEngine engine)
            throws SQLException {
        String source = LegacyDestinationPlan.SOURCE;
        Result precheck = ledger.transaction(c -> {
            var ledgered = ledger.hash(c, source, r.domain().id(), r.key());
            if (ledgered.isPresent()) return ledgered.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
            if (r.status() == Status.REPLAYED) return Result.CONFLICT;
            return null;
        });
        if (precheck != null) return precheck;
        if (engine == null) throw new SQLException("Allium economy became unavailable during legacy import");
        if (r.status() == Status.PRESENT) return adoptBalance(r, runId, ledger, engine.dialect());

        UUID operation = UUID.randomUUID();
        EconomyRequest request = engine.request(operation, "import:legacy", "balance:" + operation,
                EconomyRequest.Kind.OPEN_IMPORTED, "import:legacy", r.owner(), null, r.money());
        var mutation = engine.execute(request, c -> {
            if (ledger.hash(c, source, r.domain().id(), r.key()).isPresent()) return false;
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), Result.APPLIED.name(), runId);
            return true;
        });
        if (mutation instanceof EconomyResult.Committed) return Result.APPLIED;
        if (mutation instanceof EconomyResult.Rejected rejected) {
            if ("ACCOUNT_EXISTS".equals(rejected.reason())) return adoptBalance(r, runId, ledger, engine.dialect());
            if ("SETTINGS_CHANGED".equals(rejected.reason())) throw new SQLException("Economy settings changed during import");
            if (!"WORKFLOW_REFUSED".equals(rejected.reason())) return Result.CONFLICT;
        }
        return ledger.transaction(c -> {
            var recorded = ledger.hash(c, source, r.domain().id(), r.key());
            if (recorded.isPresent()) return recorded.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
            String detail = "missing import receipt";
            if (mutation instanceof EconomyResult.Unknown unknown) detail = unknown.diagnostic();
            if (mutation instanceof EconomyResult.Unavailable unavailable) detail = unavailable.diagnostic();
            throw new SQLException("Could not confirm imported legacy balance for " + r.owner() + ": " + detail);
        });
    }

    private static Result adoptBalance(Record r, String runId, ImportLedger ledger, SqlDialect dialect) throws SQLException {
        return ledger.transaction(c -> {
            String source = LegacyDestinationPlan.SOURCE;
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
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), Result.ADOPTED.name(), runId);
            return Result.ADOPTED;
        });
    }

    /** Domains in report order. */
    public static List<Domain> domains() {
        return List.of(Domain.HOME, Domain.WARP, Domain.LOGOUT, Domain.BALANCE, Domain.NOTE, Domain.MAIL, Domain.PVP);
    }
}
