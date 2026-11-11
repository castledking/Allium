package codes.castled.allium.tool.obliterator;

import codes.castled.allium.tool.charge.ChargeSettings;
import codes.castled.allium.tool.definition.ConfigReader;
import codes.castled.allium.tool.definition.PermissionDefault;
import codes.castled.allium.tool.definition.ToolBehaviorType;
import codes.castled.allium.tool.definition.ToolEnvironment;
import codes.castled.allium.tool.definition.ToolSound;
import codes.castled.allium.tool.disarmer.DisarmTargetPolicy;
import codes.castled.allium.tool.disarmer.DisarmerRules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code allium:obliterate_phantoms}: right-click air to kill the unnamed phantoms around you.
 * The behavior owns the steps; a definition supplies only the settings read here. Defaults are the
 * pre-recode Phantom Obliterator's (three charges, a two minute recharge, 64 blocks), with
 * {@code break-chance.preset: legacy} available for its old 3% / 5% break chances.
 */
public final class ObliterateBehaviorType implements ToolBehaviorType<ObliterateParameters> {

    public static final String ID = "allium:obliterate_phantoms";

    public static final String PERMISSION_USE = "use";
    public static final String PERMISSION_BYPASS = "bypass";

    public static final String MSG_NO_PERMISSION = "no-permission";
    public static final String MSG_OBLITERATED = "obliterated";
    public static final String MSG_OBLITERATED_BYPASS = "obliterated-bypass";
    public static final String MSG_NOTHING_IN_RANGE = "nothing-in-range";
    public static final String MSG_PROTECTED = "protected";
    public static final String MSG_LAST_CHARGE = "last-charge";
    public static final String MSG_BROKE = "broke";
    public static final String MSG_BROKEN = "broken";
    public static final String MSG_RECHARGING = "recharging";
    public static final String MSG_SINGLE_ITEM = "single-item";
    public static final String MSG_UPDATE_FAILED = "update-failed";

    public static final String SOUND_OBLITERATE = "obliterate";
    public static final String SOUND_RECHARGING = "recharging";
    public static final String PARTICLES_TARGET = "target";
    public static final String PARTICLES_USER = "user";

    public static final int DEFAULT_MAX_CHARGES = 3;
    public static final long DEFAULT_RECHARGE_SECONDS = 120L;
    public static final double DEFAULT_RADIUS = 64.0D;
    public static final int DEFAULT_MAX_TARGETS = 64;

    private static final Map<String, PermissionDefault> PERMISSIONS = permissions();
    private static final Map<String, String> MESSAGES = messages();
    private static final Map<String, ToolSound> SOUNDS = sounds();
    private static final Map<String, List<String>> PARTICLES = particles();

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
        return SOUNDS;
    }

    @Override
    public Map<String, List<String>> particleSlots() {
        return PARTICLES;
    }

    @Override
    public ObliterateParameters parse(ConfigReader root, ToolEnvironment environment) {
        DisarmerRules rules = ChargeSettings.rules(root, DEFAULT_MAX_CHARGES, DEFAULT_RECHARGE_SECONDS);

        ConfigReader targets = root.section("targets");
        double radius = targets.decimal("radius", DEFAULT_RADIUS, 1.0D, 128.0D);
        Set<String> types = new LinkedHashSet<>();
        List<String> raw = targets.stringList("entity-types", List.of("phantom"));
        for (int i = 0; i < raw.size(); i++) {
            String type = DisarmTargetPolicy.normalizeType(raw.get(i));
            if (type == null || !environment.isEntityType(type)) {
                targets.error("entity-types[" + i + "]", "'" + raw.get(i) + "' is not an entity type");
            } else if (type.equals("minecraft:player")) {
                targets.error("entity-types[" + i + "]", "players can never be targets");
            } else {
                types.add(type);
            }
        }
        if (raw.isEmpty()) {
            targets.error("entity-types", "must name at least one entity type");
        }
        boolean requireUnnamed = targets.bool("require-unnamed", true);
        boolean excludeNpcs = targets.bool("exclude-npcs", true);
        int maxTargets = targets.integer("max-targets", DEFAULT_MAX_TARGETS, 1, 256);

        String kill = root.choice("kill", "attributed", List.of("attributed", "silent"));

        ConfigReader timing = root.section("timing");
        long clickGuard = timing.whole("click-guard-millis", 200L, 0L, 10_000L);
        long statusCooldown = timing.whole("status-cooldown-millis", 1000L, 0L, 3_600_000L);

        ConfigReader legacy = root.section("legacy");
        boolean importLegacy = legacy.bool("import", true);

        return new ObliterateParameters(rules, ChargeSettings.variants(root.section("appearance"), rules.maxCharges()), radius, types,
                requireUnnamed, excludeNpcs, maxTargets, kill.equals("attributed"), clickGuard, statusCooldown, importLegacy);
    }

    private static Map<String, PermissionDefault> permissions() {
        Map<String, PermissionDefault> map = new LinkedHashMap<>();
        map.put(PERMISSION_USE, PermissionDefault.EVERYONE);
        map.put(PERMISSION_BYPASS, PermissionDefault.OPERATORS);
        return Collections.unmodifiableMap(map);
    }

    /** Each slot's default is the Phantom Obliterator's own lang entry; no-permission uses error.no-permission. */
    private static Map<String, String> messages() {
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put(MSG_NO_PERMISSION, "error.no-permission");
        for (String slot : List.of(MSG_OBLITERATED, MSG_OBLITERATED_BYPASS, MSG_NOTHING_IN_RANGE, MSG_PROTECTED, MSG_LAST_CHARGE, MSG_BROKE,
                MSG_BROKEN, MSG_RECHARGING, MSG_SINGLE_ITEM, MSG_UPDATE_FAILED)) {
            slots.put(slot, "tool.phantom-obliterator." + slot);
        }
        return Collections.unmodifiableMap(slots);
    }

    private static Map<String, ToolSound> sounds() {
        Map<String, ToolSound> map = new LinkedHashMap<>();
        map.put(SOUND_OBLITERATE, new ToolSound("minecraft:entity.evoker.cast_spell", 1.0F, 0.65F));
        map.put(SOUND_RECHARGING, new ToolSound("minecraft:block.note_block.bass", 0.6F, 0.6F));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<String>> particles() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(PARTICLES_TARGET, List.of("soul_fire_flame", "reverse_portal"));
        map.put(PARTICLES_USER, List.of("enchant"));
        return Collections.unmodifiableMap(map);
    }
}
