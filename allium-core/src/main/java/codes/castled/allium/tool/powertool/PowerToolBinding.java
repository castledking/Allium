package codes.castled.allium.tool.powertool;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A trigger binding on a powertool: activation conditions, cooldown settings, and ordered actions.
 *
 * @param fishing for {@code on-fish}, the fishing rod states it fires on ({@link #FISH_STATES}); empty
 *                for every state
 */
public record PowerToolBinding(
        PowerToolTrigger trigger,
        @Nullable String requirePermission,
        boolean cancelInteraction,
        String hand,
        String sneak,
        List<String> worlds,
        List<String> fishing,
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

    /**
     * Fishing rod states, as Bukkit's {@code PlayerFishEvent} names them: {@code cast} (the line is
     * thrown), {@code bite} (a fish bites), {@code lured} (a fish swims to the hook, 1.20.5+),
     * {@code caught-fish}, {@code caught-entity} (reeling in a hooked player or mob),
     * {@code in-ground} (reeling in a hook stuck in a block), {@code failed} (reeling in too early or
     * late) and {@code reel-in} (reeling in an empty line).
     */
    public static final List<String> FISH_STATES = List.of(
            "cast", "bite", "lured", "caught-fish", "caught-entity", "in-ground", "failed", "reel-in");

    public PowerToolBinding {
        Objects.requireNonNull(trigger, "trigger");
        if (requirePermission != null && requirePermission.isBlank()) {
            requirePermission = null;
        }
        hand = hand == null || hand.isBlank() ? HAND_MAIN : hand.trim().toLowerCase(Locale.ROOT);
        sneak = sneak == null || sneak.isBlank() ? SNEAK_ANY : sneak.trim().toLowerCase(Locale.ROOT);
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        fishing = fishing == null ? List.of() : fishing.stream().map(PowerToolBinding::fishState)
                .filter(Objects::nonNull).distinct().toList();
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

    /** Whether a fish event in {@code state} (a {@link #fishState} name or Bukkit's) fires this binding. */
    public boolean matchesFishing(@Nullable String state) {
        if (fishing.isEmpty()) {
            return true;
        }
        String canonical = fishState(state);
        return canonical != null && fishing.contains(canonical);
    }

    /**
     * The {@link #FISH_STATES} name of a fishing rod state, also from Bukkit's names
     * ({@code FISHING}, {@code CAUGHT_ENTITY}, {@code FAILED_ATTEMPT}, ...) and a few aliases
     * ({@code hooked}, {@code reel}); null when it is none.
     */
    @Nullable
    public static String fishState(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (key) {
            case "cast", "fishing", "throw" -> "cast";
            case "bite" -> "bite";
            case "lured", "lure" -> "lured";
            case "caught-fish", "catch", "caught" -> "caught-fish";
            case "caught-entity", "hooked", "hook", "hook-entity", "entity" -> "caught-entity";
            case "in-ground", "ground", "block" -> "in-ground";
            case "failed", "failed-attempt", "fail", "miss" -> "failed";
            case "reel-in", "reel", "retract" -> "reel-in";
            default -> null;
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
        return new PowerToolBinding(trigger, requirePermission, cancelInteraction, hand, sneak, worlds, fishing,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, next);
    }

    public PowerToolBinding withCooldown(long millis, PowerToolCooldownScope scope, @Nullable String id, boolean visual) {
        return new PowerToolBinding(trigger, requirePermission, cancelInteraction, hand, sneak, worlds, fishing,
                millis, scope, id, cooldownMessage, visual, consumeUse, actions);
    }

    public PowerToolBinding withPermission(@Nullable String permission) {
        return new PowerToolBinding(trigger, permission, cancelInteraction, hand, sneak, worlds, fishing,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, actions);
    }

    public PowerToolBinding withCancelInteraction(boolean cancel) {
        return new PowerToolBinding(trigger, requirePermission, cancel, hand, sneak, worlds, fishing,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, actions);
    }

    public PowerToolBinding withSneak(String nextSneak) {
        return new PowerToolBinding(trigger, requirePermission, cancelInteraction, hand, nextSneak, worlds, fishing,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, actions);
    }

    public PowerToolBinding withHand(String nextHand) {
        return new PowerToolBinding(trigger, requirePermission, cancelInteraction, nextHand, sneak, worlds, fishing,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, actions);
    }

    public PowerToolBinding withFishing(List<String> nextFishing) {
        return new PowerToolBinding(trigger, requirePermission, cancelInteraction, hand, sneak, worlds, nextFishing,
                cooldownMillis, cooldownScope, cooldownId, cooldownMessage, visualCooldown, consumeUse, actions);
    }
}
