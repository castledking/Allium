package codes.castled.allium.tool.felltree;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A kind of tree the Lazy Axe can fell. Material names are vanilla IDs; a family whose blocks the
 * running server does not have (pale oak before 1.21.4) simply matches nothing, so one
 * definition works on every lane.
 *
 * @param logs       blocks that make up the tree and are felled
 * @param leaves     blocks that prove the tree is natural when non-persistent and touching a log
 * @param ground     what the lowest log may stand on
 * @param leafless   true when the "leaves" have no persistence flag (nether wart blocks): a
 *                   weaker check, so the family is off by default
 */
public record TreeFamily(String id, Set<String> logs, Set<String> leaves, Set<String> ground, boolean leafless) {

    public TreeFamily {
        logs = Set.copyOf(logs);
        leaves = Set.copyOf(leaves);
        ground = Set.copyOf(ground);
    }

    private static final List<String> OVERWORLD_GROUND = List.of("dirt", "grass_block", "podzol", "coarse_dirt", "rooted_dirt",
            "mycelium", "moss_block", "snow_block", "mud", "farmland");

    /** Every family, by its config ID. */
    public static final Map<String, TreeFamily> ALL = Map.of(
            "overworld_logs", family("overworld_logs", List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak"),
                    List.of("oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves",
                            "azalea_leaves", "flowering_azalea_leaves"), OVERWORLD_GROUND, false),
            "cherry", family("cherry", List.of("cherry"), List.of("cherry_leaves"), OVERWORLD_GROUND, false),
            "mangrove", new TreeFamily("mangrove",
                    Set.of("mangrove_log", "mangrove_wood", "stripped_mangrove_log", "stripped_mangrove_wood", "mangrove_roots", "muddy_mangrove_roots"),
                    Set.of("mangrove_leaves"), Set.of("mud", "muddy_mangrove_roots", "mangrove_roots", "dirt", "grass_block", "clay", "sand",
                    "gravel", "water"), false),
            "pale_oak", family("pale_oak", List.of("pale_oak"), List.of("pale_oak_leaves"),
                    concat(OVERWORLD_GROUND, List.of("pale_moss_block")), false),
            "stems", new TreeFamily("stems",
                    Set.of("crimson_stem", "warped_stem", "crimson_hyphae", "warped_hyphae", "stripped_crimson_stem", "stripped_warped_stem",
                            "stripped_crimson_hyphae", "stripped_warped_hyphae"),
                    Set.of("nether_wart_block", "warped_wart_block", "shroomlight"), Set.of("crimson_nylium", "warped_nylium", "netherrack"), true));

    /** A trunk with one of these never fells: the creaking heart is part of a pale oak and must stay. */
    public static final Set<String> NEVER_BREAK = Set.of("creaking_heart");

    public static Optional<TreeFamily> of(String id) {
        return Optional.ofNullable(ALL.get(id));
    }

    /** Whether {@code material} is a log of this family, with stripped variants only when allowed. */
    public boolean isLog(String material, boolean stripped) {
        return logs.contains(material) && (stripped || !material.startsWith("stripped_"));
    }

    private static TreeFamily family(String id, List<String> species, List<String> leaves, List<String> ground, boolean leafless) {
        Set<String> logs = new LinkedHashSet<>();
        for (String s : species) {
            for (String part : List.of("_log", "_wood")) {
                logs.add(s + part);
                logs.add("stripped_" + s + part);
            }
        }
        return new TreeFamily(id, logs, Set.copyOf(leaves), Set.copyOf(ground), leafless);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
