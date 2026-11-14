package codes.castled.allium.naming;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Request-time visibility for online targets, shared by online and known-name suggestions. */
public final class PlayerNameVisibility {

    private static volatile Predicate<Player> vanishPredicate = player -> false;
    private static volatile BiConsumer<Player, Player> visibilitySync = (viewer, target) -> {};

    private PlayerNameVisibility() {
    }

    public static void setVanishHooks(
            Predicate<Player> vanishCheck,
            BiConsumer<Player, Player> syncViewerTarget
    ) {
        vanishPredicate = vanishCheck == null ? (player -> false) : vanishCheck;
        visibilitySync = syncViewerTarget == null ? ((viewer, target) -> {}) : syncViewerTarget;
    }

    public static boolean isVanished(Player target) {
        if (target == null) {
            return false;
        }
        if (vanishPredicate.test(target)) {
            return true;
        }
        var values = target.getMetadata("vanished");
        if (values != null) {
            for (MetadataValue value : values) {
                if (value != null && value.asBoolean()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Evaluates whether {@code sender} can see {@code target} in tab completions and player lists.
     */
    public static boolean canSee(CommandSender sender, Player target) {
        if (!(sender instanceof Player viewer)) {
            return true;
        }
        if (viewer == target) {
            return true;
        }
        visibilitySync.accept(viewer, target);
        if (!viewer.canSee(target)) {
            return false;
        }
        if (sender.hasPermission("allium.vanish.see")) {
            return true;
        }
        return !isVanished(target);
    }

    /**
     * Evaluates whether {@code sender} can target {@code target} in command execution without
     * revealing a vanished player to senders lacking {@code allium.vanish.see}.
     */
    public static boolean canSeeTarget(CommandSender sender, Player target) {
        if (target == null) {
            return false;
        }
        if (!(sender instanceof Player viewer) || viewer == target) {
            return true;
        }
        if (sender.hasPermission("allium.vanish.see")) {
            return true;
        }
        return !isVanished(target);
    }
}
