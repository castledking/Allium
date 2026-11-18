package codes.castled.allium.creative;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * Which inventory a player holds in each gamemode, when creative and survival inventories are kept
 * apart.
 *
 * <p>Survival and adventure share the {@link Group#SURVIVAL} inventory; creative has its own.
 * Spectator has no inventory of its own: the player keeps whichever inventory they held, so going
 * survival, spectator, creative still swaps exactly once. The inventory a player holds is recorded
 * with them (the {@code active} group), so the decision never guesses from the previous gamemode.
 */
public final class CreativeInventoryPolicy {

    public enum Group {
        SURVIVAL,
        CREATIVE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Group> parse(@Nullable String id) {
            if (id == null) {
                return Optional.empty();
            }
            for (Group group : values()) {
                if (group.id().equals(id)) {
                    return Optional.of(group);
                }
            }
            return Optional.empty();
        }
    }

    private CreativeInventoryPolicy() {
    }

    /** The inventory group of a gamemode by its Bukkit name, or empty for spectator (and unknown modes). */
    public static Optional<Group> groupOf(String gamemode) {
        return switch (gamemode.toUpperCase(Locale.ROOT)) {
            case "SURVIVAL", "ADVENTURE" -> Optional.of(Group.SURVIVAL);
            case "CREATIVE" -> Optional.of(Group.CREATIVE);
            default -> Optional.empty();
        };
    }

    /**
     * The group to load when a player who holds {@code active} enters {@code newMode}, or empty when
     * their inventory stays as it is.
     *
     * @param active the group the player holds now; {@code null} when it was never recorded, in which
     *               case the group of {@code currentMode} is assumed
     */
    public static Optional<Group> swapTo(@Nullable Group active, String currentMode, String newMode) {
        Group holding = active != null ? active : groupOf(currentMode).orElse(null);
        Optional<Group> target = groupOf(newMode);
        if (target.isEmpty() || holding == null || target.get() == holding) {
            return Optional.empty();
        }
        return target;
    }
}
