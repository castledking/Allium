package codes.castled.allium.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Core operations and snapshots for menus. Call on the viewer's owning thread. */
public interface MenuData {
    record Travel(List<String> names, List<String> locked, int limit, boolean unlimited, boolean canCreate, boolean ready) {}
    record Tool(String id, boolean enabled) {}
    record Target(UUID id, String name) {}

    Travel travel(Player player, boolean warps);
    List<Target> onlinePlayers(Player viewer);
    List<Tool> powerTools(Player viewer);
    boolean sellable(ItemStack stack);
    /** Base unit price from Worth, if the item may be sold. */
    default Optional<Double> sellPrice(ItemStack stack) { return Optional.empty(); }
    void sell(Player player, ItemStack captured);
    void sellQuoted(Player player, ItemStack sample, double price);
    Optional<Boolean> toggle(Player player, String id);
    boolean canRun(Player player, String command);
}
