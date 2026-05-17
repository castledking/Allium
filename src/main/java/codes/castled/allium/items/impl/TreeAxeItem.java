package codes.castled.allium.items.impl;

import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import codes.castled.allium.items.CustomItem;

import java.util.Arrays;
import java.util.List;

public class TreeAxeItem extends CustomItem {

    public TreeAxeItem(final Plugin plugin) {
        super(plugin, "tree_axe");
    }

    @Override
    public Material getMaterial() {
        return Material.NETHERITE_AXE;
    }

    @Override
    public String getDisplayName() {
        return "§dLazy Axe";
    }

    @Override
    public List<String> getLore() {
        return Arrays.asList(
            "§7A magical netherite axe that",
            "§7chops down entire trees with one hit!"
        );
    }

    @Override
    public String getTextureType() {
        return "item_model";
    }

    @Override
    public Object getItemModel() {
        return "oraxen:tree_axe";
    }

    @Override
    public int getCustomModelData() {
        return 1001;
    }
}
