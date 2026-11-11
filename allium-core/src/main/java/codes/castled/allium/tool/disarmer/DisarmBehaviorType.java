package codes.castled.allium.tool.disarmer;

import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.tool.charge.ChargeSettings;
import codes.castled.allium.tool.definition.ConfigReader;
import codes.castled.allium.tool.definition.PermissionDefault;
import codes.castled.allium.tool.definition.ToolBehaviorType;
import codes.castled.allium.tool.definition.ToolEnvironment;
import codes.castled.allium.tool.definition.ToolSound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code allium:disarm}: right-click a mob to take its equipment. The behavior owns the steps
 * (take before drop, one drop roll per piece, rollback on a rejected write, a charge only on
 * success, protection and the click guard); a definition supplies only the settings read here.
 * Defaults are the Mob Disarmer's as it shipped before tool definitions.
 */
public final class DisarmBehaviorType implements ToolBehaviorType<DisarmParameters> {

    public static final String ID = "allium:disarm";

    public static final String PERMISSION_USE = "use";
    public static final String PERMISSION_BYPASS = "bypass";

    public static final String MSG_NO_PERMISSION = "no-permission";
    public static final String MSG_DISARMED = "disarmed";
    public static final String MSG_DISARMED_BYPASS = "disarmed-bypass";
    public static final String MSG_LAST_CHARGE = "last-charge";
    public static final String MSG_BROKE = "broke";
    public static final String MSG_BROKEN = "broken";
    public static final String MSG_RECHARGING = "recharging";
    public static final String MSG_STATUS = "status";
    public static final String MSG_STATUS_BYPASS = "status-bypass";
    public static final String MSG_PLAYER_TARGET = "player-target";
    public static final String MSG_EXCLUDED = "excluded";
    public static final String MSG_TOO_FAR = "too-far";
    public static final String MSG_PROTECTED = "protected";
    public static final String MSG_PROTECTED_REASON = "protected-reason";
    public static final String MSG_SINGLE_ITEM = "single-item";
    public static final String MSG_UPDATE_FAILED = "update-failed";

    public static final String SOUND_DISARM = "disarm";

    public static final int DEFAULT_MAX_CHARGES = 3;
    public static final long DEFAULT_RECHARGE_SECONDS = 120L;
    public static final double DEFAULT_RADIUS = 6.0D;
    public static final long DEFAULT_PICKUP_BLOCK_SECONDS = 120L;
    public static final long DEFAULT_CLICK_GUARD_MILLIS = 200L;
    public static final long DEFAULT_STATUS_COOLDOWN_MILLIS = 1000L;
    public static final ToolSound DEFAULT_DISARM_SOUND = new ToolSound("minecraft:item.shield.break", 1.0F, 1.2F);

    private static final long MAX_SECONDS = 31_536_000L;
    private static final Map<String, PermissionDefault> PERMISSIONS = ordered(
            PERMISSION_USE, PermissionDefault.EVERYONE,
            PERMISSION_BYPASS, PermissionDefault.OPERATORS);
    private static final Map<String, String> MESSAGES = messages();
    private static final Map<String, ToolSound> SOUNDS = Map.of(SOUND_DISARM, DEFAULT_DISARM_SOUND);

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
    public DisarmParameters parse(ConfigReader root, ToolEnvironment environment) {
        DisarmerRules rules = ChargeSettings.rules(root, DEFAULT_MAX_CHARGES, DEFAULT_RECHARGE_SECONDS);
        int max = rules.maxCharges();

        ConfigReader targets = root.section("targets");
        double radius = targets.decimal("radius", DEFAULT_RADIUS, 0.0D, 64.0D);
        List<String> excluded = new ArrayList<>(DisarmTargetPolicy.DEFAULT_EXCLUDED_TYPES);
        if (targets.has("excluded-mobs")) {
            excluded.clear();
            List<String> raw = targets.stringList("excluded-mobs", List.of());
            for (int i = 0; i < raw.size(); i++) {
                String type = DisarmTargetPolicy.normalizeType(raw.get(i));
                if (type == null || !environment.isEntityType(type)) {
                    targets.error("excluded-mobs[" + i + "]", "'" + raw.get(i) + "' is not an entity type");
                } else {
                    excluded.add(type);
                }
            }
        }
        boolean excludeNpcs = targets.bool("exclude-npcs", true);

        ConfigReader disarm = root.section("disarm");
        DisarmDropRule dropRule = DisarmDropRule.fromConfig(
                disarm.choice("drop-rule", DisarmDropRule.SLOT_CHANCE.configName(), List.of("always", "slot-chance")))
                .orElse(DisarmDropRule.SLOT_CHANCE);
        long pickupBlockSeconds = disarm.whole("pickup-block-seconds", DEFAULT_PICKUP_BLOCK_SECONDS, 0L, MAX_SECONDS);

        ConfigReader timing = root.section("timing");
        long clickGuard = timing.whole("click-guard-millis", DEFAULT_CLICK_GUARD_MILLIS, 0L, 10_000L);
        long statusCooldown = timing.whole("status-cooldown-millis", DEFAULT_STATUS_COOLDOWN_MILLIS, 0L, 3_600_000L);

        List<Map<Integer, ProviderKey>> variants = ChargeSettings.variants(root.section("appearance"), max);

        return new DisarmParameters(rules, variants, radius, new DisarmTargetPolicy(excluded, excludeNpcs), dropRule,
                pickupBlockSeconds * 1000L, clickGuard, statusCooldown);
    }

    /** Each slot's default is the Mob Disarmer's own lang entry, so a slot name is also its key suffix. */
    private static Map<String, String> messages() {
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put(MSG_NO_PERMISSION, "error.no-permission");
        for (String slot : List.of(MSG_DISARMED, MSG_DISARMED_BYPASS, MSG_LAST_CHARGE, MSG_BROKE, MSG_BROKEN, MSG_RECHARGING,
                MSG_STATUS, MSG_STATUS_BYPASS, MSG_PLAYER_TARGET, MSG_EXCLUDED, MSG_TOO_FAR, MSG_PROTECTED,
                MSG_PROTECTED_REASON, MSG_SINGLE_ITEM, MSG_UPDATE_FAILED)) {
            slots.put(slot, "tool.mob-disarmer." + slot);
        }
        return Collections.unmodifiableMap(slots);
    }

    private static Map<String, PermissionDefault> ordered(String k1, PermissionDefault v1, String k2, PermissionDefault v2) {
        Map<String, PermissionDefault> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return Collections.unmodifiableMap(map);
    }
}
