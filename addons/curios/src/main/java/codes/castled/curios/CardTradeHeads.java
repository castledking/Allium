package codes.castled.curios;

import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * A Bukkit service another addon (SpawnerCraft) registers to pay card trade-ins in mob heads,
 * as legacy's trading cards did. Called on the server thread.
 */
public interface CardTradeHeads {
    /** {@code amount} of {@code mob}'s head (a lower-case entity id such as {@code allay}), if there is one. */
    Optional<ItemStack> head(String mob, int amount);
}
