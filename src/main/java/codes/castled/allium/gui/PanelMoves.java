package codes.castled.allium.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Shift-click moves into a panel's item cells, never into icons, controls or the held-slot cell. */
public final class PanelMoves {

    private PanelMoves() {
    }

    /**
     * Adds {@code stack} to cells {@code 0..cells-1} of {@code panel} except {@code skip}, filling
     * similar stacks first and then empty cells; returns the amount that did not fit.
     */
    public static int moveInto(Inventory panel, int cells, int skip, ItemStack stack) {
        int remaining = stack.getAmount();
        int max = Math.min(stack.getMaxStackSize(), panel.getMaxStackSize());
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int cell = 0; cell < cells && remaining > 0; cell++) {
                if (cell == skip) continue;
                ItemStack existing = panel.getItem(cell);
                if (pass == 0 && existing != null && !GuiIcons.isIcon(existing) && existing.isSimilar(stack) && existing.getAmount() < max) {
                    int moved = Math.min(remaining, max - existing.getAmount());
                    ItemStack merged = existing.clone();
                    merged.setAmount(existing.getAmount() + moved);
                    panel.setItem(cell, merged);
                    remaining -= moved;
                } else if (pass == 1 && (existing == null || existing.getType().isAir())) {
                    int moved = Math.min(remaining, max);
                    ItemStack placed = stack.clone();
                    placed.setAmount(moved);
                    panel.setItem(cell, placed);
                    remaining -= moved;
                }
            }
        }
        return remaining;
    }
}
