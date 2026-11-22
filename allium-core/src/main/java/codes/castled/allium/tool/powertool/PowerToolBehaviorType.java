package codes.castled.allium.tool.powertool;

import codes.castled.allium.tool.definition.ConfigReader;
import codes.castled.allium.tool.definition.PermissionDefault;
import codes.castled.allium.tool.definition.ToolBehaviorType;
import codes.castled.allium.tool.definition.ToolEnvironment;
import codes.castled.allium.tool.definition.ToolSound;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code allium:powertool} behavior: trigger-bound interactive actions with conditions,
 * cooldowns, delays, uses/recharge, and pluggable Allium actions ({@code tree_fell}, {@code disarm},
 * {@code obliterate}, commands, messages, actionbar, sounds, particles, potion effects).
 */
public final class PowerToolBehaviorType implements ToolBehaviorType<PowerToolParameters> {

    public static final String ID = "allium:powertool";
    public static final String INLINE_BEHAVIOR_ID = "allium:powertool_inline";

    public static final String PERM_USE = "use";
    public static final String PERM_BYPASS = "bypass";

    public static final String MSG_NO_PERMISSION = "no-permission";
    public static final String MSG_COOLDOWN = "cooldown";
    public static final String MSG_NO_USES = "no-uses";
    public static final String MSG_RECHARGING = "recharging";
    public static final String MSG_SINGLE_ITEM = "single-item";

    private static final Pattern ACTION_TYPE = Pattern.compile("^[a-z0-9_:-]{1,64}$");
    private static final Pattern PERM_NODE = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,127}$");

    private static final Map<String, PermissionDefault> PERMISSIONS;
    private static final Map<String, String> MESSAGES;

    static {
        Map<String, PermissionDefault> perms = new LinkedHashMap<>();
        perms.put(PERM_USE, PermissionDefault.EVERYONE);
        perms.put(PERM_BYPASS, PermissionDefault.OPERATORS);
        PERMISSIONS = Map.copyOf(perms);

        Map<String, String> msgs = new LinkedHashMap<>();
        msgs.put(MSG_NO_PERMISSION, "error.no-permission");
        msgs.put(MSG_COOLDOWN, "tool.powertool.cooldown");
        msgs.put(MSG_NO_USES, "tool.powertool.no-uses");
        msgs.put(MSG_RECHARGING, "tool.powertool.recharging");
        msgs.put(MSG_SINGLE_ITEM, "tool.powertool.single-item");
        MESSAGES = Map.copyOf(msgs);
    }

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
    public PowerToolParameters parse(ConfigReader root, ToolEnvironment environment) {
        return parseParameters(root);
    }

    /** Parses {@code uses}, {@code timing} and {@code bindings} from a root config or inline map. */
    public static PowerToolParameters parseParameters(ConfigReader root) {
        PowerToolUses uses = PowerToolUses.UNLIMITED;
        if (root.has("uses")) {
            ConfigReader usesSection = root.section("uses");
            int max = usesSection.integer("max", -1, -1, 10_000);
            if (max == 0) {
                usesSection.error("max", "must be -1 (unlimited) or between 1 and 10000 (got 0)");
            }
            boolean consumeOnEmpty = usesSection.bool("consume-on-empty", false);
            int rechargeSeconds = usesSection.integer("recharge-seconds", 0, 0, 86_400);
            boolean requireSingle = usesSection.bool("require-single-item", max > 0 && !consumeOnEmpty);
            boolean bypassable = usesSection.bool("bypassable", true);
            uses = new PowerToolUses(max, consumeOnEmpty, rechargeSeconds, requireSingle, bypassable);
        }

        long clickGuardMillis = PowerToolParameters.DEFAULT_CLICK_GUARD_MILLIS;
        if (root.has("timing")) {
            ConfigReader timing = root.section("timing");
            clickGuardMillis = timing.whole("click-guard-millis", PowerToolParameters.DEFAULT_CLICK_GUARD_MILLIS, 0L, 10_000L);
        }

        Map<PowerToolTrigger, PowerToolBinding> bindings = new LinkedHashMap<>();
        if (root.has("bindings")) {
            ConfigReader bindingsSection = root.section("bindings");
            for (String triggerKey : bindingsSection.keys()) {
                PowerToolTrigger trigger = PowerToolTrigger.parse(triggerKey);
                if (trigger == null) {
                    bindingsSection.error(triggerKey, "unknown trigger '" + triggerKey + "'; expected one of "
                            + String.join(", ", PowerToolTrigger.canonicalIds()));
                    continue;
                }
                ConfigReader b = bindingsSection.section(triggerKey);
                PowerToolBinding binding = parseBinding(trigger, b);
                if (binding != null && !binding.actions().isEmpty()) {
                    if (bindings.containsKey(trigger)) {
                        bindingsSection.error(triggerKey, "duplicate trigger binding '" + trigger.id() + "'");
                    } else {
                        bindings.put(trigger, binding);
                    }
                }
            }
        }
        return new PowerToolParameters(uses, clickGuardMillis, bindings);
    }

    private static PowerToolBinding parseBinding(PowerToolTrigger trigger, ConfigReader b) {
        String requirePermission = b.string("require-permission", null);
        if (requirePermission != null && !requirePermission.isBlank()
                && !PERM_NODE.matcher(requirePermission.trim().toLowerCase(Locale.ROOT)).matches()) {
            b.error("require-permission", "'" + requirePermission + "' is not a valid permission node");
        }
        boolean cancelInteraction = b.bool("cancel-interaction", trigger.defaultCancelInteraction());
        String hand = b.choice("hand", PowerToolBinding.HAND_MAIN,
                List.of(PowerToolBinding.HAND_MAIN, PowerToolBinding.HAND_OFF, PowerToolBinding.HAND_BOTH));
        String sneak = b.choice("sneak", PowerToolBinding.SNEAK_ANY,
                List.of(PowerToolBinding.SNEAK_ANY, PowerToolBinding.SNEAK_REQUIRE, PowerToolBinding.SNEAK_DISALLOW));
        List<String> worlds = b.stringList("worlds", List.of());
        List<String> fishing = new ArrayList<>();
        for (String state : b.stringList("fishing", List.of())) {
            if (PowerToolBinding.fishState(state) == null) {
                b.error("fishing", "unknown fishing state '" + state + "'; expected " + String.join(", ", PowerToolBinding.FISH_STATES));
            } else {
                fishing.add(state);
            }
        }
        if (!fishing.isEmpty() && trigger != PowerToolTrigger.ON_FISH) {
            b.error("fishing", "only applies to the " + PowerToolTrigger.ON_FISH.id() + " trigger");
        }

        long cooldownMillis = parseDurationField(b, "cooldown", 0L);
        String scopeRaw = b.choice("cooldown-scope", PowerToolCooldownScope.PLAYER.id(),
                List.of("player", "item", "global"));
        PowerToolCooldownScope scope = PowerToolCooldownScope.parse(scopeRaw);
        String cooldownId = b.string("cooldown-id", null);
        String cooldownMessage = b.string("cooldown-message", null);
        boolean visualCooldown = b.bool("visual-cooldown", false);
        boolean consumeUse = b.bool("consume-use", true);

        List<PowerToolAction> actions = new ArrayList<>();
        Object rawActions = b.raw("actions").orElse(null);
        if (rawActions == null) {
            b.error("actions", "is required and must contain at least one action");
        } else if (!(rawActions instanceof List<?> list)) {
            b.error("actions", "must be a list of action maps");
        } else {
            if (list.isEmpty()) {
                b.error("actions", "must contain at least one action");
            }
            for (int i = 0; i < list.size(); i++) {
                Object elem = list.get(i);
                if (!(elem instanceof Map<?, ?> map)) {
                    b.error("actions[" + i + "]", "must be a section of action settings");
                    continue;
                }
                ConfigReader ar = ConfigReader.of(map);
                PowerToolAction action = parseAction(ar);
                ar.rejectUnknown();
                for (String err : ar.errors()) {
                    b.error("actions[" + i + "]", err);
                }
                if (action != null) {
                    actions.add(action);
                }
            }
        }

        return new PowerToolBinding(
                trigger,
                requirePermission == null ? null : requirePermission.trim().toLowerCase(Locale.ROOT),
                cancelInteraction,
                hand,
                sneak,
                worlds,
                fishing,
                cooldownMillis,
                scope == null ? PowerToolCooldownScope.PLAYER : scope,
                cooldownId,
                cooldownMessage,
                visualCooldown,
                consumeUse,
                actions
        );
    }

    private static PowerToolAction parseAction(ConfigReader ar) {
        String commandShorthand = ar.has("command") ? ar.string("command", null) : null;
        String rawType = ar.has("type") ? ar.string("type", null) : (commandShorthand != null ? PowerToolAction.TYPE_COMMAND : null);
        if (rawType == null || rawType.isBlank()) {
            ar.error("type", "is required (or provide 'command')");
            return null;
        }
        String type = PowerToolAction.normalizeType(rawType);
        if (!ACTION_TYPE.matcher(type).matches()) {
            ar.error("type", "'" + rawType + "' is not a valid action type");
            return null;
        }
        String value = ar.has("value") ? ar.string("value", null) : commandShorthand;
        String asRaw = ar.choice("as", PowerToolAction.TYPE_CONSOLE.equals(type) ? "console" : "player",
                List.of("player", "console"));
        boolean asConsole = "console".equals(asRaw) || PowerToolAction.TYPE_CONSOLE.equals(type);

        if ((PowerToolAction.TYPE_COMMAND.equals(type) || PowerToolAction.TYPE_CONSOLE.equals(type)
                || PowerToolAction.TYPE_MESSAGE.equals(type) || PowerToolAction.TYPE_ACTIONBAR.equals(type)
                || PowerToolAction.TYPE_SOUND.equals(type) || PowerToolAction.TYPE_PARTICLE.equals(type)
                || PowerToolAction.TYPE_POTION.equals(type) || PowerToolAction.TYPE_GRANT.equals(type))
                && (value == null || value.isBlank())) {
            ar.error("value", "is required for action type '" + type + "'");
        }
        if (PowerToolAction.TYPE_GRANT.equals(type) && value != null && !value.isBlank()) {
            String invalid = PowerToolGrants.firstInvalid(value);
            if (invalid != null) {
                ar.error("value", "'" + invalid + "' is not a permission node or weight:node");
            }
        }

        long cooldownMillis = parseDurationField(ar, "cooldown", 0L);
        String scopeRaw = ar.choice("cooldown-scope", PowerToolCooldownScope.PLAYER.id(),
                List.of("player", "item", "global"));
        PowerToolCooldownScope scope = PowerToolCooldownScope.parse(scopeRaw);
        String cooldownId = ar.string("cooldown-id", null);
        long delayMillis = parseDurationField(ar, "delay", 0L);
        long delayTicks = PowerToolDurations.millisToTicks(delayMillis);

        String requirePermission = ar.string("require-permission", null);
        if (requirePermission != null && !requirePermission.isBlank()
                && !PERM_NODE.matcher(requirePermission.trim().toLowerCase(Locale.ROOT)).matches()) {
            ar.error("require-permission", "'" + requirePermission + "' is not a valid permission node");
        }

        boolean required = ar.bool("required", false);
        if (required && delayMillis > 0L) {
            ar.error("required", "a required action cannot have a delay: whether it worked is only known afterwards");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (ar.has("params")) {
            Object rawParams = ar.raw("params").orElse(null);
            if (rawParams instanceof Map<?, ?> map) {
                map.forEach((k, v) -> {
                    if (k != null && v != null) {
                        params.put(String.valueOf(k), v);
                    }
                });
            } else if (rawParams != null) {
                ar.error("params", "must be a section of action parameters");
            }
        }

        return new PowerToolAction(
                type,
                value,
                asConsole,
                cooldownMillis,
                scope == null ? PowerToolCooldownScope.PLAYER : scope,
                cooldownId,
                delayTicks,
                requirePermission == null ? null : requirePermission.trim().toLowerCase(Locale.ROOT),
                params,
                required
        );
    }

    private static long parseDurationField(ConfigReader reader, String key, long fallback) {
        if (!reader.has(key)) {
            return fallback;
        }
        Object raw = reader.raw(key).orElse(null);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            long val = number.longValue();
            if (val < 0L) {
                reader.error(key, "must be >= 0 (got " + val + ")");
                return fallback;
            }
            return val;
        }
        if (raw instanceof String text) {
            try {
                return PowerToolDurations.parseMillis(text);
            } catch (IllegalArgumentException e) {
                reader.error(key, e.getMessage());
                return fallback;
            }
        }
        reader.error(key, "must be a duration like 5s, 500ms, 20t, or milliseconds");
        return fallback;
    }
}
