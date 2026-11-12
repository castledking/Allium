package codes.castled.allium.tool.felltree;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Decides whether a broken log belongs to a natural tree, and which logs to fell with it.
 *
 * <p>Steps, all of which must pass:
 * <ol>
 *   <li>The broken block is a log of an enabled family.</li>
 *   <li>A search over the 26 neighbours collects logs of the <b>same species</b> (oak with oak,
 *       stripped or not; mangrove logs with their roots). It is bounded by the block cap, the
 *       horizontal radius and the height, and reads only loaded chunks. Over the cap: too large,
 *       and nothing beyond the broken log is felled.</li>
 *   <li>No creaking heart is part of the trunk.</li>
 *   <li>At least {@code minNaturalLeaves} leaves of the family touch the found logs and are
 *       <b>not persistent</b>. Leaves a player places are persistent, so a log build never passes.
 *       Nether wart blocks have no such flag ({@link TreeFamily#leafless()}).</li>
 *   <li>The lowest log stands on the family's ground.</li>
 *   <li>No found log touches a building block (planks, glass, doors, ...).</li>
 * </ol>
 */
public final class TreeScanner {

    public enum Result { FELL, NOT_A_TREE, TOO_LARGE }

    public record Pos(int x, int y, int z) {
        Pos offset(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }
    }

    /**
     * @param logs the logs to break after the broken one, bottom up; empty unless {@code FELL}
     * @param size every log found, the broken one included
     */
    public record Scan(Result result, List<Pos> logs, int size, String reason, @Nullable TreeFamily family, @Nullable String species) {
        public Scan {
            logs = List.copyOf(logs);
        }

        static Scan no(String reason) {
            return new Scan(Result.NOT_A_TREE, List.of(), 0, reason, null, null);
        }
    }

    public record Limits(int maxBlocks, int maxRadius, int maxHeight, int minNaturalLeaves, boolean stripped) {
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Name parts of blocks players build with; touching one stops a fell. */
    private static final List<String> STRUCTURE = List.of("planks", "_slab", "_stairs", "fence", "door", "glass", "wool", "concrete",
            "terracotta", "brick", "sign", "chest", "barrel", "torch", "lantern", "crafting_table", "furnace", "smoker", "bookshelf",
            "banner", "rail", "ladder", "button", "pressure_plate", "_wall", "cobblestone", "smooth_stone", "iron_block", "gold_block",
            "lectern", "anvil", "campfire", "composter", "hopper", "bell", "scaffolding", "chain", "lever", "item_frame");

    private TreeScanner() {
    }

    public static Scan scan(BlockView view, Pos broken, List<TreeFamily> families, Limits limits) {
        Objects.requireNonNull(view, "view");
        String material = view.material(broken.x(), broken.y(), broken.z());
        if (material == null) {
            return Scan.no("its chunk is not loaded");
        }
        TreeFamily family = families.stream().filter(f -> f.isLog(material, limits.stripped())).findFirst().orElse(null);
        if (family == null) {
            return Scan.no(material + " is not a log of an enabled family");
        }
        String species = species(material);

        Set<Pos> logs = new LinkedHashSet<>();
        Deque<Pos> queue = new ArrayDeque<>();
        logs.add(broken);
        queue.add(broken);
        while (!queue.isEmpty()) {
            Pos at = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Pos next = at.offset(dx, dy, dz);
                        if (logs.contains(next) || Math.abs(next.x() - broken.x()) > limits.maxRadius()
                                || Math.abs(next.z() - broken.z()) > limits.maxRadius()
                                || Math.abs(next.y() - broken.y()) > limits.maxHeight()
                                || next.y() < view.minY() || next.y() > view.maxY()) {
                            continue;
                        }
                        String m = view.material(next.x(), next.y(), next.z());
                        if (m == null) {
                            continue; // unloaded: never read, never felled
                        }
                        if (TreeFamily.NEVER_BREAK.contains(m)) {
                            return Scan.no("a " + m + " is part of the trunk");
                        }
                        if (family.isLog(m, limits.stripped()) && species(m).equals(species)) {
                            logs.add(next);
                            if (logs.size() > limits.maxBlocks()) {
                                return new Scan(Result.TOO_LARGE, List.of(), logs.size(), "more than " + limits.maxBlocks() + " logs", family, species);
                            }
                            queue.add(next);
                        }
                    }
                }
            }
        }

        Set<Pos> naturalLeaves = new LinkedHashSet<>();
        int lowest = Integer.MAX_VALUE;
        for (Pos log : logs) {
            lowest = Math.min(lowest, log.y());
            for (int[] face : FACES) {
                Pos side = log.offset(face[0], face[1], face[2]);
                if (logs.contains(side)) {
                    continue;
                }
                String m = view.material(side.x(), side.y(), side.z());
                if (m == null) {
                    continue;
                }
                if (family.leaves().contains(m)) {
                    BlockView.Leaf leaf = view.leaf(side.x(), side.y(), side.z());
                    if (family.leafless() || (leaf != null && !leaf.persistent())) {
                        naturalLeaves.add(side);
                    }
                } else if (isStructure(m)) {
                    return Scan.no("a log touches " + m + ", a building block");
                }
            }
        }
        if (naturalLeaves.size() < limits.minNaturalLeaves()) {
            return Scan.no(naturalLeaves.size() + " natural (non-persistent) leaves touch it; " + limits.minNaturalLeaves() + " are needed");
        }
        boolean grounded = false;
        for (Pos log : logs) {
            if (log.y() == lowest) {
                String below = view.material(log.x(), log.y() - 1, log.z());
                if (below != null && family.ground().contains(below)) {
                    grounded = true;
                    break;
                }
            }
        }
        if (!grounded) {
            return Scan.no("its lowest log does not stand on " + family.id() + " ground");
        }
        List<Pos> toBreak = new ArrayList<>(logs);
        toBreak.remove(broken);
        toBreak.sort(Comparator.comparingInt(Pos::y).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
        return new Scan(Result.FELL, toBreak, logs.size(), "a natural " + species + " tree", family, species);
    }

    /** oak_log, stripped_oak_wood and oak_wood are all oak; mangrove roots belong to the mangrove. */
    public static String species(String material) {
        String m = material.toLowerCase(Locale.ROOT);
        if (m.equals("mangrove_roots") || m.equals("muddy_mangrove_roots")) {
            return "mangrove";
        }
        if (m.startsWith("stripped_")) {
            m = m.substring("stripped_".length());
        }
        for (String suffix : List.of("_log", "_wood", "_stem", "_hyphae")) {
            if (m.endsWith(suffix)) {
                return m.substring(0, m.length() - suffix.length());
            }
        }
        return m;
    }

    public static boolean isStructure(String material) {
        if (material.equals("moss_carpet") || material.endsWith("_leaves") || material.startsWith("torchflower")) {
            return false;
        }
        if (material.endsWith("carpet") || material.endsWith("_bed")) {
            return true;
        }
        for (String part : STRUCTURE) {
            if (material.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
