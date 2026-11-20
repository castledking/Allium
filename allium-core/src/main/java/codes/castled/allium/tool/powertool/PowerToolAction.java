package codes.castled.allium.tool.powertool;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One action executed when a {@link PowerToolBinding} fires.
 *
 * <p>Built-in action types include {@code command}, {@code console}, {@code message},
 * {@code actionbar}, {@code sound}, {@code particle}, {@code potion}, and Allium's own tool
 * actions ({@code tree_fell}, {@code disarm}, {@code obliterate}). Unknown custom action IDs
 * matching {@code [a-z0-9_:-]+} are also permitted so addons or future Allium modules can plug
 * into the runtime action registry.
 */
public record PowerToolAction(
        String type,
        @Nullable String value,
        boolean asConsole,
        long cooldownMillis,
        PowerToolCooldownScope cooldownScope,
        @Nullable String cooldownId,
        long delayTicks,
        @Nullable String requirePermission,
        Map<String, Object> params
) {
    public static final String TYPE_COMMAND = "command";
    public static final String TYPE_CONSOLE = "console";
    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_ACTIONBAR = "actionbar";
    public static final String TYPE_SOUND = "sound";
    public static final String TYPE_PARTICLE = "particle";
    public static final String TYPE_POTION = "potion";
    public static final String TYPE_TREE_FELL = "tree_fell";
    public static final String TYPE_DISARM = "disarm";
    public static final String TYPE_OBLITERATE = "obliterate";

    public static final Set<String> BUILT_IN_TYPES = Set.of(
            TYPE_COMMAND, TYPE_CONSOLE, TYPE_MESSAGE, TYPE_ACTIONBAR,
            TYPE_SOUND, TYPE_PARTICLE, TYPE_POTION,
            TYPE_TREE_FELL, TYPE_DISARM, TYPE_OBLITERATE
    );

    public PowerToolAction {
        Objects.requireNonNull(type, "type");
        type = normalizeType(type);
        if (TYPE_CONSOLE.equals(type)) {
            asConsole = true;
        }
        cooldownMillis = Math.max(0L, cooldownMillis);
        cooldownScope = cooldownScope == null ? PowerToolCooldownScope.PLAYER : cooldownScope;
        if (cooldownId != null && cooldownId.isBlank()) {
            cooldownId = null;
        }
        delayTicks = Math.max(0L, delayTicks);
        if (requirePermission != null && requirePermission.isBlank()) {
            requirePermission = null;
        }
        params = params == null || params.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(params));
    }

    public static String normalizeType(String raw) {
        String lower = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (lower) {
            case "cmd", "player", "p", "player_command" -> TYPE_COMMAND;
            case "c", "console_command", "server" -> TYPE_CONSOLE;
            case "msg", "chat" -> TYPE_MESSAGE;
            case "ab", "action_bar" -> TYPE_ACTIONBAR;
            case "playsound" -> TYPE_SOUND;
            case "particles" -> TYPE_PARTICLE;
            case "effect", "potioneffect" -> TYPE_POTION;
            case "fell_tree", "allium:fell_tree", "allium:tree_fell" -> TYPE_TREE_FELL;
            case "mob_disarm", "allium:disarm" -> TYPE_DISARM;
            case "obliterate_phantoms", "obliterate_entities", "area_kill", "allium:obliterate", "allium:obliterate_phantoms" -> TYPE_OBLITERATE;
            default -> lower;
        };
    }

    public boolean isConsoleExecution() {
        return asConsole || TYPE_CONSOLE.equals(type);
    }

    public boolean hasCooldown() {
        return cooldownMillis > 0L;
    }

    public boolean hasDelay() {
        return delayTicks > 0L;
    }

    public boolean hasPermission() {
        return requirePermission != null && !requirePermission.isBlank();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (TYPE_COMMAND.equals(type) || TYPE_CONSOLE.equals(type)) {
            sb.append(isConsoleExecution() ? "console" : "command").append(": ").append(value == null ? "" : value);
        } else {
            sb.append(type);
            if (value != null && !value.isBlank()) {
                sb.append(": ").append(value);
            }
        }
        if (hasCooldown()) {
            sb.append(" [cd=").append(PowerToolDurations.formatMillis(cooldownMillis));
            if (cooldownScope != PowerToolCooldownScope.PLAYER) {
                sb.append('/').append(cooldownScope.id());
            }
            sb.append(']');
        }
        if (hasDelay()) {
            sb.append(" [delay=").append(PowerToolDurations.formatTicks(delayTicks)).append(']');
        }
        if (hasPermission()) {
            sb.append(" [perm=").append(requirePermission).append(']');
        }
        return sb.toString();
    }
}
