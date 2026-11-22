package codes.castled.curios;

import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/** Curios' Bukkit service for crafting a bound trading card on the server thread. */
public interface CardCrafting {
    boolean supports(String mob);

    /** Model of the lowest eligible rarity, for display without a signed card or a roll. */
    default OptionalInt previewModel(String mob, Map<String, Double> weights) {
        return OptionalInt.empty();
    }

    /** Empty weights use Curios' configured mob pool. */
    Optional<ItemStack> craft(String mob, Map<String, Double> weights);
}
