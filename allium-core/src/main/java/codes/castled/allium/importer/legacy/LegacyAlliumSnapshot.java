package codes.castled.allium.importer.legacy;

import java.util.List;
import java.util.Map;

/**
 * What a legacy Allium H2 database holds for travel: one candidate per home (owner and exact name)
 * and per logout position, the warps and the staff-set home limits, plus what was left out.
 *
 * <p>Legacy Allium wrote {@code player_locations} with {@code INSERT ... ON DUPLICATE KEY} and
 * {@code REPLACE} on a table without a unique key, so every re-set home and every logout added a
 * row. The newest row (latest timestamp, then highest id) is the candidate; the others are
 * superseded and reported.
 *
 * @param h2Version             the H2 version that read the file
 * @param unnamedHomes          HOME rows without a name (legacy never showed or used them)
 * @param otherLocationTypes    row counts of location types with no destination (DEATH, LOGIN, ...)
 * @param supersededLogouts     older LOGOUT rows behind each player's newest one
 */
public record LegacyAlliumSnapshot(String h2Version, List<HomeRow> homes, List<SupersededHome> supersededHomes,
                                   long unnamedHomes, List<LogoutRow> logouts, long supersededLogouts, List<WarpRow> warps,
                                   List<MaxHomesRow> maxHomes, Map<String, Long> otherLocationTypes, List<String> notes) {

    public LegacyAlliumSnapshot {
        homes = List.copyOf(homes);
        supersededHomes = List.copyOf(supersededHomes);
        logouts = List.copyOf(logouts);
        warps = List.copyOf(warps);
        maxHomes = List.copyOf(maxHomes);
        otherLocationTypes = Map.copyOf(otherLocationTypes);
        notes = List.copyOf(notes);
    }

    /** A position as legacy stored it: a world name, never a world UUID. */
    public record Point(String world, double x, double y, double z, float yaw, float pitch) {
    }

    public record HomeRow(long id, String owner, String name, Point point, long timestamp) {
    }

    public record SupersededHome(long id, String owner, String name, long keptId) {
    }

    public record LogoutRow(long id, String owner, Point point, long timestamp) {
    }

    /** {@code isPublic} and the creator are provenance only: legacy access was {@code allium.warps.<name>}. */
    public record WarpRow(String name, Point point, String creatorUuid, String creatorName, Boolean isPublic) {
    }

    /** {@code player_max_homes}: a staff-set limit; -1 or a missing row meant "use permissions". */
    public record MaxHomesRow(String owner, int maxHomes) {
    }
}
