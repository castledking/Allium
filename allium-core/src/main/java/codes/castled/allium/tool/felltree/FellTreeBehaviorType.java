package codes.castled.allium.tool.felltree;

import codes.castled.allium.tool.definition.ConfigReader;
import codes.castled.allium.tool.definition.PermissionDefault;
import codes.castled.allium.tool.definition.ToolBehaviorType;
import codes.castled.allium.tool.definition.ToolEnvironment;
import codes.castled.allium.tool.definition.ToolSound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code allium:fell_tree}: breaking one log of a natural tree with the axe fells the whole tree.
 * The behavior owns the steps; a definition supplies only the settings read here. Off by default
 * in the shipped definition ({@code tools/lazy_axe.yml}).
 */
public final class FellTreeBehaviorType implements ToolBehaviorType<FellTreeParameters> {

    public static final String ID = "allium:fell_tree";

    public static final String PERMISSION_USE = "use";

    public static final String MSG_TOO_LARGE = "too-large";
    public static final String MSG_BUSY = "busy";
    public static final String MSG_STOPPED = "stopped-axe";

    public static final List<String> DEFAULT_FAMILIES = List.of("overworld_logs", "mangrove", "cherry", "pale_oak");

    private static final Map<String, PermissionDefault> PERMISSIONS = Map.of(PERMISSION_USE, PermissionDefault.EVERYONE);
    private static final Map<String, String> MESSAGES = messages();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, PermissionDefault> permissionSlots() {
        return PERMISSIONS;
    }

    @Override
    public Map<String, String> messageSlots() {
        return MESSAGES;
    }

    @Override
    public Map<String, ToolSound> soundSlots() {
        return Map.of();
    }

    @Override
    public FellTreeParameters parse(ConfigReader root, ToolEnvironment environment) {
        List<String> raw = root.stringList("families", DEFAULT_FAMILIES);
        List<TreeFamily> families = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String id = raw.get(i).trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
            TreeFamily family = TreeFamily.of(id).orElse(null);
            if (family == null) {
                root.error("families[" + i + "]", "'" + raw.get(i) + "' is not a tree family; known: " + String.join(", ", TreeFamily.ALL.keySet().stream().sorted().toList()));
            } else if (!families.contains(family)) {
                families.add(family);
            }
        }
        if (raw.isEmpty()) {
            root.error("families", "must name at least one tree family");
        }
        boolean stripped = root.bool("stripped", true);

        ConfigReader search = root.section("search");
        int maxBlocks = search.integer("max-blocks", 256, 1, 2048);
        int maxRadius = search.integer("max-radius", 8, 1, 32);
        int maxHeight = search.integer("max-height", 48, 1, 256);
        int minLeaves = search.integer("min-natural-leaves", 4, 1, 64);

        ConfigReader breaking = root.section("breaking");
        int perTick = breaking.integer("blocks-per-tick", 6, 1, 64);
        String mode = breaking.choice("mode", "break-as-player", List.of("break-as-player", "direct"));
        boolean stopBeforeBreak = breaking.bool("stop-before-break", true);

        String sneak = root.choice("sneak", "disables", List.of("disables", "required", "ignored"));
        long cooldown = root.whole("cooldown-millis", 0L, 0L, 3_600_000L);
        boolean importLegacy = root.section("legacy").bool("import", true);

        return new FellTreeParameters(families, stripped, maxBlocks, maxRadius, maxHeight, minLeaves, perTick,
                mode.equals("break-as-player"), stopBeforeBreak, sneak, cooldown, importLegacy);
    }

    private static Map<String, String> messages() {
        Map<String, String> slots = new LinkedHashMap<>();
        for (String slot : List.of(MSG_TOO_LARGE, MSG_BUSY, MSG_STOPPED)) {
            slots.put(slot, "tool.lazy-axe." + slot);
        }
        return Collections.unmodifiableMap(slots);
    }
}
