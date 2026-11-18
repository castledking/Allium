package codes.castled.allium.importer.legacy;

import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.HomeRow;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.LogoutRow;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.MaxHomesRow;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.Point;
import codes.castled.allium.importer.legacy.LegacyAlliumSnapshot.WarpRow;
import codes.castled.allium.teleport.SavedLocation;
import codes.castled.allium.travel.HomePolicy;
import codes.castled.allium.travel.TravelNames;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The reviewed plan for importing a legacy Allium snapshot's homes, warps and logout positions:
 * one record per natural key with what will happen to it and why. Pure: the caller supplies the
 * world map and what the destination and the import ledger already hold.
 *
 * <ul>
 *   <li>Names follow Allium's rules (1-32 ASCII letters, digits, {@code _} or {@code -},
 *       lowercase). A name that does not fit, or two legacy names of one owner that become the
 *       same name, block those records instead of being guessed.</li>
 *   <li>Worlds resolve through the world map; an unknown world blocks the record, never
 *       substituting another world.</li>
 *   <li>An existing destination with the same location is {@code PRESENT} (recorded in the
 *       ledger, nothing written); a different one is a {@code CONFLICT} left as it is. A logout
 *       position already saved by this server is newer than legacy's and is kept.</li>
 *   <li>A ledger entry with the same payload is {@code REPLAYED}; with a different payload it is a
 *       {@code CONFLICT}.</li>
 * </ul>
 */
public final class LegacyDestinationPlan {

    /** The ledger's source name for every legacy Allium record. */
    public static final String SOURCE = "legacy-allium";
    static final double HORIZONTAL_LIMIT = 30_000_000;

    public enum Domain { HOME, WARP, LOGOUT;
        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public enum Status { IMPORT, PRESENT, REPLAYED, CONFLICT, BLOCKED, SKIPPED }

    /**
     * @param key        the ledger key: {@code <owner>/<name>} for homes, the name for warps, the
     *                   owner for logout positions (normalized names; the legacy spelling is in
     *                   {@code legacy})
     * @param legacy     where the record came from, for the report
     * @param location   the destination, when the world resolved
     */
    public record Record(Domain domain, String key, Status status, String reason, String payloadHash,
                         UUID owner, String name, SavedLocation location, String legacy) {
    }

    public record ResolvedWorld(UUID id, String name) {
    }

    @FunctionalInterface
    public interface Worlds {
        Optional<ResolvedWorld> resolve(String legacyName);
    }

    /** What the destination and the ledger hold before the import. */
    public record Destination(Map<UUID, Map<String, SavedLocation>> homes, Map<String, SavedLocation> warps,
                              Map<UUID, SavedLocation> logouts, Map<String, String> ledger) {
        public static Destination empty() {
            return new Destination(Map.of(), Map.of(), Map.of(), Map.of());
        }

        static String ledgerKey(Domain domain, String key) {
            return domain.id() + ":" + key;
        }
    }

    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final List<Record> records;
    private final List<MaxHomesRow> overrides;

    private LegacyDestinationPlan(List<Record> records, List<MaxHomesRow> overrides) {
        this.records = List.copyOf(records);
        this.overrides = List.copyOf(overrides);
    }

    public List<Record> records() {
        return records;
    }

    /** Staff-set legacy limits, for the {@code homes.staff-overrides} snippet (never applied automatically). */
    public List<MaxHomesRow> overrides() {
        return overrides;
    }

    public Map<Status, Long> counts(Domain domain) {
        Map<Status, Long> out = new EnumMap<>(Status.class);
        for (Status status : Status.values()) out.put(status, 0L);
        for (Record r : records) if (r.domain() == domain) out.merge(r.status(), 1L, Long::sum);
        return out;
    }

    /** Identifies the reviewed plan: apply refuses when the plan it would run differs. */
    public String sha256() {
        StringBuilder text = new StringBuilder();
        records.stream().sorted(Comparator.comparing((Record r) -> r.domain().ordinal()).thenComparing(Record::key).thenComparing(Record::legacy))
                .forEach(r -> text.append(r.domain().id()).append('|').append(r.key()).append('|').append(r.status()).append('|')
                        .append(r.payloadHash()).append('|').append(r.reason()).append('\n'));
        for (MaxHomesRow row : overrides) text.append("limit|").append(row.owner()).append('|').append(row.maxHomes()).append('\n');
        return sha256(text.toString());
    }

    public static LegacyDestinationPlan build(LegacyAlliumSnapshot snapshot, Worlds worlds, Destination destination) {
        List<Record> out = new ArrayList<>();
        planHomes(snapshot, worlds, destination, out);
        for (var row : snapshot.supersededHomes()) {
            out.add(new Record(Domain.HOME, row.owner() + "/" + row.name() + "#" + row.id(), Status.SKIPPED,
                    "superseded by newer row " + row.keptId(), "", null, row.name(), null, "player_locations id " + row.id()));
        }
        planWarps(snapshot, worlds, destination, out);
        planLogouts(snapshot, worlds, destination, out);
        List<MaxHomesRow> limits = snapshot.maxHomes().stream().filter(r -> r.maxHomes() >= 1 && uuid(r.owner()) != null).toList();
        return new LegacyDestinationPlan(out, limits);
    }

    private static void planHomes(LegacyAlliumSnapshot snapshot, Worlds worlds, Destination destination, List<Record> out) {
        // Group by owner and Allium name first: two legacy spellings of one name are a collision.
        Map<String, List<HomeRow>> byKey = new LinkedHashMap<>();
        List<HomeRow> rows = new ArrayList<>(snapshot.homes());
        rows.sort(Comparator.comparing(HomeRow::owner).thenComparing(HomeRow::name).thenComparingLong(HomeRow::id));
        Map<UUID, Integer> perOwner = new LinkedHashMap<>();
        for (HomeRow row : rows) {
            String legacy = "player_locations id " + row.id() + " (" + row.owner() + " home '" + row.name() + "')";
            UUID owner = uuid(row.owner());
            if (owner == null) {
                out.add(blocked(Domain.HOME, row.owner() + "/" + row.name(), "invalid owner UUID", null, row.name(), legacy));
                continue;
            }
            String name;
            try {
                name = TravelNames.normalize(row.name());
            } catch (IllegalArgumentException invalid) {
                out.add(blocked(Domain.HOME, owner + "/" + row.name(), "name does not fit Allium's rules (1-32 of A-Z a-z 0-9 _ -)", owner, row.name(), legacy));
                continue;
            }
            byKey.computeIfAbsent(owner + "/" + name, k -> new ArrayList<>()).add(row);
        }
        for (var entry : byKey.entrySet()) {
            List<HomeRow> group = entry.getValue();
            HomeRow row = group.get(0);
            UUID owner = uuid(row.owner());
            String name = TravelNames.normalize(row.name());
            String legacy = "player_locations id " + row.id() + " (" + row.owner() + " home '" + row.name() + "')";
            if (group.size() > 1) {
                for (HomeRow each : group) {
                    out.add(blocked(Domain.HOME, entry.getKey(), "legacy names " + group.stream().map(h -> "'" + h.name() + "'").distinct().toList()
                            + " all become '" + name + "'", owner, each.name(), "player_locations id " + each.id()));
                }
                continue;
            }
            int count = perOwner.merge(owner, 1, Integer::sum);
            if (count > HomePolicy.HARD_CAP) {
                out.add(blocked(Domain.HOME, entry.getKey(), "over the hard cap of " + HomePolicy.HARD_CAP + " homes", owner, name, legacy));
                continue;
            }
            Optional<SavedLocation> location = locate(row.point(), worlds);
            if (location.isEmpty()) {
                out.add(blocked(Domain.HOME, entry.getKey(), unresolved(row.point()), owner, name, legacy));
                continue;
            }
            SavedLocation at = location.get();
            String hash = payload("home", entry.getKey(), at);
            SavedLocation existing = destination.homes().getOrDefault(owner, Map.of()).get(name);
            out.add(decide(Domain.HOME, entry.getKey(), hash, owner, name, at, legacy, existing, destination, false));
        }
    }

    private static void planWarps(LegacyAlliumSnapshot snapshot, Worlds worlds, Destination destination, List<Record> out) {
        Map<String, List<WarpRow>> byName = new LinkedHashMap<>();
        for (WarpRow row : snapshot.warps()) {
            String legacy = "warps '" + row.name() + "'" + (row.creatorName() == null ? "" : " by " + row.creatorName());
            String name;
            try {
                name = TravelNames.normalize(row.name());
            } catch (IllegalArgumentException invalid) {
                out.add(blocked(Domain.WARP, String.valueOf(row.name()), "name does not fit Allium's rules (1-32 of A-Z a-z 0-9 _ -)", null, row.name(), legacy));
                continue;
            }
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(row);
        }
        for (var entry : byName.entrySet()) {
            List<WarpRow> group = entry.getValue();
            if (group.size() > 1) {
                for (WarpRow each : group) {
                    out.add(blocked(Domain.WARP, entry.getKey(), "legacy warps " + group.stream().map(w -> "'" + w.name() + "'").toList()
                            + " all become '" + entry.getKey() + "'", null, each.name(), "warps '" + each.name() + "'"));
                }
                continue;
            }
            WarpRow row = group.get(0);
            String legacy = "warps '" + row.name() + "'" + (row.creatorName() == null ? "" : " by " + row.creatorName());
            Optional<SavedLocation> location = locate(row.point(), worlds);
            if (location.isEmpty()) {
                out.add(blocked(Domain.WARP, entry.getKey(), unresolved(row.point()), null, entry.getKey(), legacy));
                continue;
            }
            String hash = payload("warp", entry.getKey(), location.get());
            out.add(decide(Domain.WARP, entry.getKey(), hash, null, entry.getKey(), location.get(), legacy,
                    destination.warps().get(entry.getKey()), destination, false));
        }
    }

    private static void planLogouts(LegacyAlliumSnapshot snapshot, Worlds worlds, Destination destination, List<Record> out) {
        for (LogoutRow row : snapshot.logouts()) {
            String legacy = "player_locations id " + row.id() + " (LOGOUT)";
            UUID owner = uuid(row.owner());
            if (owner == null) {
                out.add(blocked(Domain.LOGOUT, row.owner(), "invalid owner UUID", null, null, legacy));
                continue;
            }
            Optional<SavedLocation> location = locate(row.point(), worlds);
            if (location.isEmpty()) {
                out.add(blocked(Domain.LOGOUT, owner.toString(), unresolved(row.point()), owner, null, legacy));
                continue;
            }
            String hash = payload("logout", owner.toString(), location.get());
            out.add(decide(Domain.LOGOUT, owner.toString(), hash, owner, null, location.get(), legacy,
                    destination.logouts().get(owner), destination, true));
        }
    }

    private static Record decide(Domain domain, String key, String hash, UUID owner, String name, SavedLocation at, String legacy,
                                 SavedLocation existing, Destination destination, boolean newerWins) {
        String ledgered = destination.ledger().get(Destination.ledgerKey(domain, key));
        if (ledgered != null) {
            return ledgered.equals(hash)
                    ? new Record(domain, key, Status.REPLAYED, "already imported", hash, owner, name, at, legacy)
                    : new Record(domain, key, Status.CONFLICT, "imported earlier with different data", hash, owner, name, at, legacy);
        }
        if (existing != null) {
            if (existing.equals(at)) return new Record(domain, key, Status.PRESENT, "already there with the same location", hash, owner, name, at, legacy);
            return newerWins
                    ? new Record(domain, key, Status.SKIPPED, "this server saved a newer logout position", hash, owner, name, at, legacy)
                    : new Record(domain, key, Status.CONFLICT, "a different " + domain.id() + " with this name exists; it is kept", hash, owner, name, at, legacy);
        }
        return new Record(domain, key, Status.IMPORT, "", hash, owner, name, at, legacy);
    }

    private static Record blocked(Domain domain, String key, String reason, UUID owner, String name, String legacy) {
        return new Record(domain, key, Status.BLOCKED, reason, "", owner, name, null, legacy);
    }

    private static Optional<SavedLocation> locate(Point p, Worlds worlds) {
        if (p.world() == null || !Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())
                || !Float.isFinite(p.yaw()) || !Float.isFinite(p.pitch())
                || Math.abs(p.x()) > HORIZONTAL_LIMIT || Math.abs(p.z()) > HORIZONTAL_LIMIT) return Optional.empty();
        return worlds.resolve(p.world()).map(w -> new SavedLocation(w.id(), w.name(), p.x(), p.y(), p.z(), p.yaw(), p.pitch()));
    }

    private static String unresolved(Point p) {
        if (p.world() == null) return "no world";
        if (!Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z()) || !Float.isFinite(p.yaw()) || !Float.isFinite(p.pitch())
                || Math.abs(p.x()) > HORIZONTAL_LIMIT || Math.abs(p.z()) > HORIZONTAL_LIMIT) return "coordinates outside the world";
        return "world '" + p.world() + "' is not loaded or mapped";
    }

    static UUID uuid(String text) {
        if (text == null || !UUID_TEXT.matcher(text).matches()) return null;
        return UUID.fromString(text);
    }

    static String payload(String domain, String key, SavedLocation at) {
        return sha256(domain + "|" + key + "|" + at.worldId() + "|" + at.worldName() + "|" + Double.toString(at.x()) + "|"
                + Double.toString(at.y()) + "|" + Double.toString(at.z()) + "|" + Float.toString(at.yaw()) + "|" + Float.toString(at.pitch()));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
