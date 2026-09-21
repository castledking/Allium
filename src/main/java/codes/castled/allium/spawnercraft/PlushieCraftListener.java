package codes.castled.allium.spawnercraft;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;

import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Plushie crafting: a mob head (More Mob Heads datapack, Allium spawner head or a
 * vanilla skull) in the center of a crafting grid, surrounded by eight wool of any
 * color, makes that mob's Nexo plushie, e.g. {@code nexo:axolotl_plushie}.
 *
 * Touches Nexo classes directly, so it must only be registered when Nexo is enabled.
 */
public class PlushieCraftListener implements Listener {

    private static final int CENTER = 4;
    // Mobs whose Nexo plushie id doesn't follow <mob>_plushie.
    private static final Map<String, String> PLUSHIE_ALIASES = Map.of(
            "ender_dragon", "enderdragon",
            "copper_golem", "copper_golem_unoxidized",
            "zombie_nautilus", "zombie_nautilus_temperate"
    );

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        String plushieId = plushieFor(event.getInventory().getMatrix());
        if (plushieId == null) return;
        ItemStack plushie = createPlushie(plushieId);
        if (plushie != null) {
            event.getInventory().setResult(plushie);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onResultClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof CraftingInventory inventory)) return;
        if (event.getSlotType() != InventoryType.SlotType.RESULT) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String plushieId = plushieFor(inventory.getMatrix());
        if (plushieId == null || event.getCurrentItem() == null
                || !plushieId.equals(NexoItems.idFromItem(event.getCurrentItem()))) return;

        // No real recipe backs this result, so consume the grid and hand out the plushie here.
        event.setCancelled(true);
        ItemStack[] matrix = inventory.getMatrix();
        for (ItemStack item : matrix) {
            if (item != null) {
                item.setAmount(item.getAmount() - 1);
            }
        }
        inventory.setMatrix(matrix);

        ItemStack plushie = createPlushie(plushieId);
        if (plushie != null) {
            player.getInventory().addItem(plushie).values().forEach(overflow ->
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        }
        String next = plushieFor(inventory.getMatrix());
        inventory.setResult(next == null ? null : createPlushie(next));
    }

    private static String plushieFor(ItemStack[] matrix) {
        if (matrix == null || matrix.length != 9) return null;
        for (int i = 0; i < matrix.length; i++) {
            if (i == CENTER) continue;
            ItemStack item = matrix[i];
            if (item == null || !Tag.WOOL.isTagged(item.getType())) return null;
        }
        String mob = MobHeadRegistry.getMobKey(matrix[CENTER]);
        if (mob == null) return null;
        String id = plushieIdForMob(mob);
        return NexoItems.exists(id) ? id : null;
    }

    static String plushieIdForMob(String mob) {
        return PLUSHIE_ALIASES.getOrDefault(mob, mob) + "_plushie";
    }

    private static ItemStack createPlushie(String id) {
        ItemBuilder builder = NexoItems.itemFromId(id);
        return builder == null ? null : builder.build();
    }
}
