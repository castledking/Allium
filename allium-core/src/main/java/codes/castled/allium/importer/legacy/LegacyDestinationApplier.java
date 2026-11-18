package codes.castled.allium.importer.legacy;

import codes.castled.allium.importer.ImportLedger;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Domain;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Record;
import codes.castled.allium.importer.legacy.LegacyDestinationPlan.Status;
import codes.castled.allium.teleport.TeleportRepository;
import codes.castled.allium.travel.TravelRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies a reviewed {@link LegacyDestinationPlan}. Each chunk is one transaction holding both the
 * destination rows and their ledger rows, so a crash leaves every record either fully applied
 * (and ledgered) or untouched; a rerun skips what the ledger already holds. Only {@code IMPORT}
 * and {@code PRESENT} records are touched; everything is rechecked inside the transaction.
 */
public final class LegacyDestinationApplier {

    static final int CHUNK = 200;

    /** What happened to one record at apply time. */
    public enum Result { APPLIED, ADOPTED, REPLAYED, CONFLICT, LIMIT, NEWER }

    public record Outcome(Record record, Result result) {
    }

    private LegacyDestinationApplier() {
    }

    public static List<Outcome> apply(LegacyDestinationPlan plan, String runId, ImportLedger ledger, TravelRepository travel,
                                      TeleportRepository teleports) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        List<Record> work = plan.records().stream().filter(r -> r.status() == Status.IMPORT || r.status() == Status.PRESENT).toList();
        List<Outcome> out = new ArrayList<>();
        for (int start = 0; start < work.size(); start += CHUNK) {
            List<Record> chunk = work.subList(start, Math.min(work.size(), start + CHUNK));
            out.addAll(ledger.transaction(c -> {
                List<Outcome> done = new ArrayList<>();
                for (Record r : chunk) done.add(new Outcome(r, one(c, r, runId, ledger, travel, teleports)));
                return done;
            }));
        }
        return out;
    }

    public static Map<Result, Long> counts(List<Outcome> outcomes) {
        Map<Result, Long> out = new EnumMap<>(Result.class);
        for (Result result : Result.values()) out.put(result, 0L);
        for (Outcome o : outcomes) out.merge(o.result(), 1L, Long::sum);
        return out;
    }

    private static Result one(Connection c, Record r, String runId, ImportLedger ledger, TravelRepository travel,
                              TeleportRepository teleports) throws SQLException {
        String source = LegacyDestinationPlan.SOURCE;
        var ledgered = ledger.hash(c, source, r.domain().id(), r.key());
        if (ledgered.isPresent()) return ledgered.get().equals(r.payloadHash()) ? Result.REPLAYED : Result.CONFLICT;
        Result result = switch (r.domain()) {
            case HOME -> switch (travel.importHome(c, r.owner(), r.name(), r.location())) {
                case CREATED -> Result.APPLIED;
                case PRESENT -> Result.ADOPTED;
                case CONFLICT -> Result.CONFLICT;
                case LIMIT -> Result.LIMIT;
            };
            case WARP -> switch (travel.importWarp(c, r.name(), r.location())) {
                case CREATED -> Result.APPLIED;
                case PRESENT -> Result.ADOPTED;
                case CONFLICT, LIMIT -> Result.CONFLICT;
            };
            case LOGOUT -> switch (teleports.importLocation(c, r.owner(), "logout", r.location())) {
                case CREATED -> Result.APPLIED;
                case PRESENT -> Result.ADOPTED;
                case NEWER -> Result.NEWER;
            };
        };
        if (result == Result.APPLIED || result == Result.ADOPTED) {
            ledger.record(c, source, r.domain().id(), r.key(), r.payloadHash(), result.name(), runId);
        }
        return result;
    }

    /** Domains in report order. */
    public static List<Domain> domains() {
        return List.of(Domain.HOME, Domain.WARP, Domain.LOGOUT);
    }
}
