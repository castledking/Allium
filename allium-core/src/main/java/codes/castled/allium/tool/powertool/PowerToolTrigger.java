package codes.castled.allium.tool.powertool;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Activation triggers supported by Allium powertools.
 */
public enum PowerToolTrigger {
    LEFT("left", false, "l", "lmb", "left-click", "left_click"),
    RIGHT("right", true, "r", "rmb", "right-click", "right_click"),
    SHIFT_LEFT("shift-left", false, "sl", "shift_left", "shift-lmb", "shift_lmb"),
    SHIFT_RIGHT("shift-right", true, "sr", "shift_right", "shift-rmb", "shift_rmb"),
    SWAP("swap", false, "f", "swap-hands", "swap_hands"),
    DROP("drop", true, "q", "drop-item", "drop_item"),
    ON_BREAK("on-break", false, "on_break", "break", "block-break", "block_break"),
    ON_INTERACT_ENTITY("on-interact-entity", true, "on_interact_entity", "interact-entity", "interact_entity", "entity-interact", "right-entity", "right_entity"),
    ON_HIT("on-hit", false, "on_hit", "hit", "attack", "damage-entity", "damage_entity"),
    ON_CONSUME("on-consume", false, "on_consume", "consume", "eat");

    private final String id;
    private final boolean defaultCancel;
    private final List<String> aliases;

    PowerToolTrigger(String id, boolean defaultCancel, String... aliases) {
        this.id = id;
        this.defaultCancel = defaultCancel;
        this.aliases = List.of(aliases);
    }

    public String id() {
        return id;
    }

    public boolean defaultCancelInteraction() {
        return defaultCancel;
    }

    public List<String> aliases() {
        return aliases;
    }

    @Nullable
    public static PowerToolTrigger parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (PowerToolTrigger trigger : values()) {
            if (trigger.id.equals(normalized)) {
                return trigger;
            }
            for (String alias : trigger.aliases) {
                if (alias.replace('_', '-').equals(normalized)) {
                    return trigger;
                }
            }
        }
        return null;
    }

    public static List<String> canonicalIds() {
        return java.util.Arrays.stream(values()).map(PowerToolTrigger::id).toList();
    }
}
