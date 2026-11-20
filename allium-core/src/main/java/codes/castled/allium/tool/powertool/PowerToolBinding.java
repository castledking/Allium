package codes.castled.allium.tool.powertool;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A trigger binding on a powertool: activation conditions, cooldown settings, and ordered actions.
 */
public record PowerToolBinding(
        PowerToolTrigger trigger,
        @Nullable String requirePermission,
        boolean cancelInteraction,
        String hand,
        String sneak,
        List<String> worlds,
        long cooldownMillis,
        PowerToolCooldownScope cooldownScope,
        @Nullable String cooldownId,
        @Nullable String cooldownMessage,
        boolean visualCooldown,
        boolean consumeUse,
        List<PowerToolAction> actions
) {
    public static final String HAND_MAIN = "main";
    public static final String HAND_OFF = "off";
    public static final String HAND_BOTH = "both";

    public static final String SNEAK_ANY = "any";
    public static final String SNEAK_REQUIRE = "require";
    public static final String SNEAK_DISALLOW = "disallow";

    public PowerToolBinding {
        Objects.requireNonNull(trigger, "trigger");
        if (requirePermission != null && requirePermission.isBlank()) {
            requirePermission = null;
        }
        hand = hand == null || hand.isBlank() ? HAND_MAIN : hand.trim().toLowerCase(Locale.ROOT);
        sneak = sneak == null || sneak.isBlank() ? SNEAK_ANY : sneak.trim().toLowerCase(Locale.ROOT);
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        cooldownMillis = Math.max(0L, cooldownMillis);
        cooldownScope = cooldownScope == null ? PowerToolCooldownScope.PLAYER : cooldownScope;
        if (cooldownId != null && cooldownId.isBlank()) {
            cooldownId = null;
        }
        if (cooldownMessage != null && cooldownMessage.isBlank()) {
            cooldownMessage = null;
        }
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public static PowerToolBinding empty(PowerToolTrigger trigger) {
        return new PowerToolBinding(
                trigger,
                null,
                trigger.defaultCancelInteraction(),
                HAND_MAIN,
                SNEAK_ANY,
                List.of(),
                0L,
                PowerToolCooldownScope.PLAYER,
                null,
                null,
                false,
                true,
                List.of()
        );
    }

    public boolean hasPermission() {
        return requirePermission != null && !requirePermission.isBlank();
    }

    public boolean hasCooldown() {
        return cooldownMillis > 0L;
    }

    public boolean matchesHand(String activeHand) {
        return HAND_BOTH.equals(hand) || hand.equalsIgnoreCase(activeHand);
    }

    public boolean matchesSneak(boolean sneaking) {
        return switch (sneak) {
            case SNEAK_REQUIRE -> sneaking;
            case SNEAK_DISALLOW -> !sneaking;
            default -> true;
        };
    }

    public boolean matchesWorld(String worldName) {
        if (worlds.isEmpty()) {
            return true;
        }
        for (String allowed : worlds) {
            if (allowed.equalsIgnoreCase(worldName)) {
                return true;
            }
        }
        return false;
    }

    public PowerToolBinding withActionAdded(PowerToolAction action) {
        List<PowerToolAction> next = new ArrayList<>(actions);
        next.add(Objects.requireNonNull(action, "action"));
        return withActions(next);
    }

    public PowerToolBinding withActionReplaced(int index, PowerToolAction action) {
        List<PowerToolAction> next = new ArrayList<>(actions);
        next.set(index, Objects.requireNonNull(action, "action"));
        return withActions(next);
    }

    public PowerToolBinding withActionRemoved(int index) {
        List<PowerToolAction> next = new ArrayList<>(actions);
        next.remove(index);
        return withActions(next);
    }

    public PowerToolBinding withActions(List<PowerToolAction> next) {
        return new PowerToolBinding(trigger, requirePermission, cancelInteraction, hand, sneak, worlds,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, next);
    }

    public PowerToolBinding withCooldown(long millis, PowerToolCooldownScope scope, @Nullable String id, boolean visual) {
        return new PowerToolBinding(trigger, requirePermission, cancelInteraction, hand, sneak, worlds,
                millis, scope, id, cooldownMessage, visual, consumeUse, actions);
    }

    public PowerToolBinding withPermission(@Nullable String permission) {
        return new PowerToolBinding(trigger, permission, cancelInteraction, hand, sneak, worlds,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, actions);
    }

    public PowerToolBinding withCancelInteraction(boolean cancel) {
        return new PowerToolBinding(trigger, requirePermission, cancel, hand, sneak, worlds,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, actions);
    }

    public PowerToolBinding withSneak(String nextSneak) {
        return new PowerToolBinding(trigger, requirePermission, cancelInteraction, hand, nextSneak, worlds,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, actions);
    }

    public PowerToolBinding withHand(String nextHand) {
        return new PowerToolBinding(trigger, requirePermission, cancelInteraction, nextHand, sneak, worlds,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, actions);
    }
}
