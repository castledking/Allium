package codes.castled.menus;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.function.BiConsumer;

import java.util.List;

interface DialogRenderer {
    record Button(String label, String command) {}

    boolean supports(Player player);
    void show(Player player, String title, List<Button> buttons);
    void form(Player player, String title, String field, ItemStack preview, List<String> values,
              int maxFields, int maxLength, BiConsumer<List<String>, Boolean> submit);
}
